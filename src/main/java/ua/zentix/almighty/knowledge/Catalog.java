package ua.zentix.almighty.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.platform.GameEvents;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.platform.Platform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Live catalog of what this server contains, built from the running game so the agent can learn any modpack the way
 * a player uses a wiki: mods, registry contents, tags, recipes, the command tree (one level per root, never expanded
 * whole: redirects explode it), game-bus events and mods' config values (keys that look like secrets are left out;
 * files in {@code config/} are never read). Never dumped whole: queries are paged and capped below client output
 * limits.
 * <p>
 * Threads: registries are frozen after startup and are read off the server thread. What {@code /reload} replaces
 * (tags, commands, config values, the recipe manager reference) is snapshotted on the server thread once per
 * reload (names and counts, one level of usage, config values; tens of ms at most once per reload on a large pack);
 * recipe details are then built off the server thread from that reference, which a reload replaces rather than
 * mutates. Config values are as of the last reload, not a later file-watcher reload.
 */
public final class Catalog {
    public static final List<String> KINDS = List.of("mods", "blocks", "items", "entities", "effects", "enchantments",
            "biomes", "dimensions", "tags", "recipes", "recipe_types", "loot_tables", "advancements", "commands", "events", "config");
    /** Config keys whose values are never shown. */
    static final Pattern SECRET = Pattern.compile("(?i).*(token|secret|pass(word|wd|phrase)?|pwd|credential|key|webhook|auth(?!or)|cookie|session|jdbc|dsn|(database|db)[._-]?url).*");
    private static final int MAX_TEXT = 300;

    /** One catalog entry: what a query matches on ({@code id} and {@code text}) and how it is shown. */
    record Entry(String kind, String id, String text, Supplier<JsonObject> detail) {
        JsonObject show() {
            JsonObject out = new JsonObject();
            out.addProperty("kind", kind);
            out.addProperty("id", id);
            try {
                detail.get().entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
            } catch (RuntimeException e) {
                out.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return out;
        }

        boolean matches(String[] words) {
            String hay = (id + " " + text).toLowerCase(Locale.ROOT);
            for (String w : words) if (!hay.contains(w)) return false;
            return true;
        }
    }

    /** What a reload replaces, taken on the server thread; recipe entries are built later, off it. */
    record Snapshot(Object key, Map<String, List<Entry>> kinds, RecipeManager recipes, RegistryAccess access) {}

    private final MinecraftServer server;
    private final Map<String, List<Entry>> frozen = new ConcurrentHashMap<>();
    private volatile Snapshot reloadable;

    Catalog(MinecraftServer server) {
        this.server = server;
    }

    MinecraftServer server() {
        return server;
    }

    /** Server thread: refresh what a {@code /reload} replaced; cheap when nothing changed. */
    void refresh() {
        Object key = server.getResourceManager();
        Snapshot s = reloadable;
        if (s != null && s.key() == key) return;
        Map<String, List<Entry>> kinds = new ConcurrentHashMap<>();
        kinds.put("tags", tags(server.registryAccess()));
        kinds.put("commands", commands(server.getCommands().getDispatcher(), server.createCommandSourceStack()));
        kinds.put("config", config());
        // Loot tables and advancements are data a reload replaces: their ids (and an advancement's title), taken here.
        kinds.put("loot_tables", lootTables(server));
        kinds.put("advancements", advancements(server));
        // The level map is the server thread's: a mod may add a dimension at runtime.
        List<Entry> dimensions = new ArrayList<>();
        for (ResourceKey<?> level : server.levelKeys()) dimensions.add(new Entry("dimensions", Mc.id(level).toString(), "", JsonObject::new));
        kinds.put("dimensions", List.copyOf(dimensions));
        reloadable = new Snapshot(key, kinds, server.getRecipeManager(), server.registryAccess());
    }

    /** Off the server thread after {@link #refresh}: entries of one kind. */
    List<Entry> entries(String kind) {
        Snapshot s = reloadable;
        if (s == null) throw new IllegalStateException("Catalog.refresh has not run");
        if (kind.equals("recipes")) {
            return s.kinds().computeIfAbsent(kind, k -> recipes(s.recipes().getRecipes(), s.access()));
        }
        List<Entry> taken = s.kinds().get(kind);
        return taken != null ? taken : frozen.computeIfAbsent(kind, this::build);
    }

    /**
     * Entries matching every word of {@code query} (case-insensitive, on id and a short text; {@code create:} is a
     * namespace), optionally of one kind, from {@code offset}; {@code total} counts all matches.
     */
    JsonObject search(String kind, String query, int offset, int limit) throws RpcException {
        if (kind != null && !KINDS.contains(kind)) {
            throw RpcException.badRequest("kind: one of " + String.join(", ", KINDS));
        }
        String[] words = query == null || query.isBlank() ? new String[0] : query.toLowerCase(Locale.ROOT).strip().split("\\s+");
        JsonArray shown = new JsonArray();
        int total = 0;
        for (String k : kind == null ? KINDS : List.of(kind)) {
            for (Entry e : entries(k)) {
                if (!e.matches(words)) continue;
                if (total >= offset && shown.size() < limit) shown.add(e.show());
                total++;
            }
        }
        JsonObject out = new JsonObject();
        out.add("entries", shown);
        out.addProperty("total", total);
        if (offset + shown.size() < total) out.addProperty("next_offset", offset + shown.size());
        return out;
    }

    private List<Entry> build(String kind) {
        RegistryAccess access = server.registryAccess();
        return switch (kind) {
            case "mods" -> mods();
            case "blocks" -> registry(kind, BuiltInRegistries.BLOCK, Catalog::block);
            case "items" -> registry(kind, BuiltInRegistries.ITEM, item -> {
                JsonObject d = new JsonObject();
                d.addProperty("max_stack", item.getDefaultMaxStackSize());
                return d;
            });
            case "entities" -> registry(kind, BuiltInRegistries.ENTITY_TYPE, type -> {
                JsonObject d = new JsonObject();
                d.addProperty("category", type.getCategory().getName());
                return d;
            });
            case "effects" -> registry(kind, BuiltInRegistries.MOB_EFFECT, effect -> {
                JsonObject d = new JsonObject();
                d.addProperty("category", effect.getCategory().name().toLowerCase(Locale.ROOT));
                return d;
            });
            case "enchantments" -> registry(kind, Mc.registry(access, Registries.ENCHANTMENT), e -> {
                JsonObject d = new JsonObject();
                d.addProperty("max_level", e.getMaxLevel());
                return d;
            });
            case "biomes" -> registry(kind, Mc.registry(access, Registries.BIOME), b -> new JsonObject());
            case "recipe_types" -> registry(kind, BuiltInRegistries.RECIPE_TYPE, t -> new JsonObject());
            case "events" -> {
                List<Entry> out = new ArrayList<>();
                Platform.get().events().all().forEach((className, mod) -> {
                    String name = GameEvents.shortName(className);
                    out.add(new Entry(kind, name, className + " " + mod, () -> {
                        JsonObject d = new JsonObject();
                        d.addProperty("class", className);
                        d.addProperty("mod", mod);
                        return d;
                    }));
                });
                yield List.copyOf(out);
            }
            default -> List.of();
        };
    }

    private static List<Entry> mods() {
        List<Entry> out = new ArrayList<>();
        for (Platform.ModInfo mod : Platform.get().mods()) {
            String description = Notes.shorten(mod.description(), MAX_TEXT);
            out.add(new Entry("mods", mod.id(), mod.name() + " " + description, () -> {
                JsonObject d = new JsonObject();
                d.addProperty("name", mod.name());
                d.addProperty("version", mod.version());
                if (!description.isEmpty()) d.addProperty("description", description);
                if (mod.url() != null) d.addProperty("url", mod.url());
                return d;
            }));
        }
        return List.copyOf(out);
    }

    private static <T> List<Entry> registry(String kind, Registry<T> registry, java.util.function.Function<T, JsonObject> detail) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<ResourceKey<T>, T> e : registry.entrySet()) {
            T value = e.getValue();
            out.add(new Entry(kind, Mc.id(e.getKey()).toString(), "", () -> detail.apply(value)));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    private static JsonObject block(Block block) {
        JsonObject d = new JsonObject();
        StringBuilder states = new StringBuilder();
        for (Property<?> p : block.getStateDefinition().getProperties()) {
            if (!states.isEmpty()) states.append(", ");
            states.append(p.getName()).append('=').append(String.join("|", p.getPossibleValues().stream().map(v -> name(p, v)).toList()));
        }
        if (!states.isEmpty()) d.addProperty("states", Notes.shorten(states.toString(), MAX_TEXT));
        return d;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String name(Property p, Object value) {
        return p.getName((Comparable) value);
    }

    /** Tags of the main registries with their sizes: taken on the server thread (a reload rebinds them). */
    /** Loot table ids (chests, entities, blocks, archaeology …); server thread, at a reload. */
    private static List<Entry> lootTables(MinecraftServer server) {
        List<Entry> out = new ArrayList<>();
        for (ResourceLocation id : Mc.lootTableIds(server)) {
            String path = id.getPath();
            String group = path.contains("/") ? path.substring(0, path.indexOf('/')) : "";
            out.add(new Entry("loot_tables", id.toString(), group, () -> {
                JsonObject d = new JsonObject();
                if (!group.isEmpty()) d.addProperty("group", group);
                return d;
            }));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    /** Advancement ids with their title and parent, if any; server thread, at a reload. */
    private static List<Entry> advancements(MinecraftServer server) {
        List<Entry> out = new ArrayList<>();
        for (net.minecraft.advancements.AdvancementHolder a : server.getAdvancements().getAllAdvancements()) {
            String title = a.value().display().map(Mc::advancementTitle).orElse("");
            String parent = a.value().parent().map(Object::toString).orElse(null);
            out.add(new Entry("advancements", a.id().toString(), title, () -> {
                JsonObject d = new JsonObject();
                if (!title.isEmpty()) d.addProperty("title", title);
                if (parent != null) d.addProperty("parent", parent);
                return d;
            }));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    private static List<Entry> tags(RegistryAccess access) {
        List<Entry> out = new ArrayList<>();
        for (ResourceKey<? extends Registry<?>> key : List.of(Registries.BLOCK, Registries.ITEM, Registries.ENTITY_TYPE,
                Registries.FLUID, Registries.BIOME, Registries.ENCHANTMENT)) {
            for (Map.Entry<ResourceLocation, Integer> tag : Mc.tagSizes(access, key)) {
                int size = tag.getValue();
                String registry = Mc.id(key).getPath();
                String id = "#" + tag.getKey();
                out.add(new Entry("tags", id, registry, () -> {
                    JsonObject d = new JsonObject();
                    d.addProperty("registry", registry);
                    d.addProperty("size", size);
                    return d;
                }));
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    /** Root commands with the usage of their direct children; taken on the server thread. */
    private static List<Entry> commands(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source) {
        List<Entry> out = new ArrayList<>();
        for (CommandNode<CommandSourceStack> root : dispatcher.getRoot().getChildren()) {
            List<String> usage = new ArrayList<>();
            try {
                usage.addAll(dispatcher.getSmartUsage(root, source).values());
            } catch (RuntimeException e) {
                usage.add("(usage unavailable: " + e.getClass().getSimpleName() + ")");
            }
            String text = Notes.shorten(String.join(" | ", usage), MAX_TEXT);
            out.add(new Entry("commands", root.getName(), text, () -> {
                JsonObject d = new JsonObject();
                if (!text.isEmpty()) d.addProperty("usage", text);
                return d;
            }));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    /** Recipes from the reload's recipe manager; details are computed only for the entries shown. */
    private static List<Entry> recipes(Collection<RecipeHolder<?>> holders, RegistryAccess access) {
        List<Entry> out = new ArrayList<>(holders.size());
        for (RecipeHolder<?> holder : holders) {
            Recipe<?> recipe = holder.value();
            String type = String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
            String result;
            try {
                ItemStack stack = Mc.recipeResult(recipe, access);
                result = stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + (stack.getCount() > 1 ? " x" + stack.getCount() : "");
            } catch (RuntimeException e) {
                result = "";
            }
            String shownResult = result;
            out.add(new Entry("recipes", Mc.recipeId(holder).toString(), type + " " + result, () -> {
                JsonObject d = new JsonObject();
                d.addProperty("type", type);
                if (!shownResult.isEmpty()) d.addProperty("result", shownResult);
                JsonArray ingredients = new JsonArray();
                for (List<net.minecraft.world.item.Item> ingredient : Mc.ingredients(recipe)) {
                    Set<String> items = new LinkedHashSet<>();
                    for (net.minecraft.world.item.Item item : ingredient) items.add(BuiltInRegistries.ITEM.getKey(item).toString());
                    List<String> first = items.stream().limit(4).toList();
                    ingredients.add(String.join("|", first) + (items.size() > 4 ? "|…(" + items.size() + ")" : ""));
                }
                if (!ingredients.isEmpty()) d.add("ingredients", ingredients);
                return d;
            }));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return List.copyOf(out);
    }

    /** Mods' loaded common and server config values, without keys that look like secrets. */
    private static List<Entry> config() {
        return configEntries(Platform.get().modConfigValues());
    }

    static List<Entry> configEntries(List<Platform.ConfigValue> values) {
        List<Entry> out = new ArrayList<>();
        for (Platform.ConfigValue v : values) {
            String key = v.key(), file = v.file();
            boolean secret = SECRET.matcher(key).matches();
            String shown = secret ? "(hidden: looks like a secret)" : Notes.shorten(String.valueOf(v.value()), MAX_TEXT);
            out.add(new Entry("config", v.mod() + ":" + key, file, () -> {
                JsonObject d = new JsonObject();
                d.addProperty("file", file);
                d.addProperty("value", shown);
                return d;
            }));
        }
        return List.copyOf(out);
    }
}
