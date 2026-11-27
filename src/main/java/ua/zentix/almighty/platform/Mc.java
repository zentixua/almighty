package ua.zentix.almighty.platform;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;

/**
 * Vanilla across game versions: small helpers for what the game renamed or reshaped between the versions this
 * mod builds for (Stonecutter conditions live here, so call sites stay the same on every version). Each helper
 * keeps the meaning of the oldest version's call it replaces.
 */
public final class Mc {
    private Mc() {}

    // Profiles (authlib's GameProfile is a record on 26.x).

    public static String name(GameProfile profile) {
        //? if >=26.1 {
        /*return profile.name();
        *///?} else {
        return profile.getName();
        //?}
    }

    public static UUID id(GameProfile profile) {
        //? if >=26.1 {
        /*return profile.id();
        *///?} else {
        return profile.getId();
        //?}
    }

    public static PropertyMap properties(GameProfile profile) {
        //? if >=26.1 {
        /*return profile.properties();
        *///?} else {
        return profile.getProperties();
        //?}
    }

    /** A profile with an optional {@code textures} property (26.x property maps are immutable: set at creation). */
    public static GameProfile profile(UUID id, String name, Property textures) {
        //? if >=26.1 {
        /*if (textures == null) return new GameProfile(id, name);
        return new GameProfile(id, name, new PropertyMap(com.google.common.collect.ImmutableMultimap.of("textures", textures)));
        *///?} else {
        GameProfile profile = new GameProfile(id, name);
        if (textures != null) profile.getProperties().put("textures", textures);
        return profile;
        //?}
    }

    /**
     * The server's profile cache ({@code usercache.json}, name to UUID); null if it has none (26.x: the name
     * resolver, when it is the file-backed cache).
     */
    public static net.minecraft.server.players.GameProfileCache profileCache(MinecraftServer server) {
        //? if >=26.1 {
        /*return server.services().nameToIdCache() instanceof net.minecraft.server.players.GameProfileCache cache ? cache : null;
        *///?} else {
        return server.getProfileCache();
        //?}
    }

    /** A player's profile (name and UUID, no properties) from the profile cache by name; null if not there. */
    public static GameProfile cachedProfile(MinecraftServer server, String name) {
        //? if >=26.1 {
        /*return server.services().nameToIdCache().get(name).map(n -> new GameProfile(n.id(), n.name())).orElse(null);
        *///?} else {
        var cache = server.getProfileCache();
        return cache == null ? null : cache.get(name).orElse(null);
        //?}
    }

    /** A player's full profile (with signed textures) by account name, looked up in the background. */
    public static java.util.concurrent.CompletableFuture<java.util.Optional<GameProfile>> fetchProfile(MinecraftServer server, String name) {
        //? if >=26.1 {
        /*var resolver = server.services().profileResolver();
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> resolver.fetchByName(name), net.minecraft.util.Util.nonCriticalIoPool());
        *///?} else {
        return net.minecraft.world.level.block.entity.SkullBlockEntity.fetchGameProfile(name);
        //?}
    }

    // Players.

    public static MinecraftServer server(ServerPlayer player) {
        //? if >=26.1 {
        /*return player.level().getServer();
        *///?} else {
        return player.server;
        //?}
    }

    public static ServerLevel level(ServerPlayer player) {
        //? if >=26.1 {
        /*return player.level();
        *///?} else {
        return player.serverLevel();
        //?}
    }

    /** Operator level of a profile on this server (0 to 4; the integrated server's owner gets the owner level). */
    public static int opLevel(MinecraftServer server, GameProfile profile) {
        //? if >=26.1 {
        /*return server.getProfilePermissions(new net.minecraft.server.players.NameAndId(profile)).level().id();
        *///?} else {
        return server.getProfilePermissions(profile);
        //?}
    }

    /**
     * The player whose own typing a command source is: not {@code execute as}, a function or the GM speaking as
     * them; null otherwise (1.21.1: the player is the source's output; 26.x: the player's own command source).
     */
    public static ServerPlayer typedBy(CommandSourceStack source) {
        //? if >=26.1 {
        /*return source.getEntity() instanceof ServerPlayer p && source.source == p.commandSource() ? p : null;
        *///?} else {
        return source.source instanceof ServerPlayer p ? p : null;
        //?}
    }

    /** Selected hotbar slot. */
    public static int selectedSlot(net.minecraft.world.entity.player.Inventory inventory) {
        //? if >=26.1 {
        /*return inventory.getSelectedSlot();
        *///?} else {
        return inventory.selected;
        //?}
    }

    /** The player's own respawn point (bed, anchor, {@code /spawnpoint}); null if none. */
    public static BlockPos respawnPos(ServerPlayer player) {
        //? if >=26.1 {
        /*ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        return config == null ? null : config.respawnData().pos();
        *///?} else {
        return player.getRespawnPosition();
        //?}
    }

    /** Dimension of the player's own respawn point (meaningful with {@link #respawnPos}). */
    public static ResourceKey<net.minecraft.world.level.Level> respawnDimension(ServerPlayer player) {
        //? if >=26.1 {
        /*ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        return config == null ? net.minecraft.world.level.Level.OVERWORLD : config.respawnData().dimension();
        *///?} else {
        return player.getRespawnDimension();
        //?}
    }

    /** Teleport a player to a level and position with a rotation (dismounting, as {@code /tp}). */
    public static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        //? if >=26.1 {
        /*player.teleportTo(level, x, y, z, java.util.Set.of(), yaw, pitch, true);
        *///?} else {
        player.teleportTo(level, x, y, z, yaw, pitch);
        //?}
    }

    // Command permission levels (26.x: permission sets; a level is one of them).

    /** Whether a source has command permission level {@code level} (as 1.21.1's {@code hasPermission(level)}). */
    public static boolean has(CommandSourceStack source, int level) {
        //? if >=26.1 {
        /*return source.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(
                net.minecraft.server.permissions.PermissionLevel.byId(level)));
        *///?} else {
        return source.hasPermission(level);
        //?}
    }

    /** The source at exactly command permission level {@code level}. */
    public static CommandSourceStack withLevel(CommandSourceStack source, int level) {
        //? if >=26.1 {
        /*return source.withPermission(permissions(level));
        *///?} else {
        return source.withPermission(level);
        //?}
    }

    /** A new command source at command permission level {@code level}. */
    public static CommandSourceStack source(CommandSource output, Vec3 pos, Vec2 rotation, ServerLevel level, int permission,
                                            String name, Component displayName, MinecraftServer server, Entity entity) {
        //? if >=26.3 {
        /*// 26.3: a source names itself after its entity, or after one constant component (text name = its string).
        return entity != null ? new CommandSourceStack(output, pos, rotation, level, permissions(permission), server, entity)
                : new CommandSourceStack(output, pos, rotation, level, permissions(permission), displayName, server);
        *///?} else if >=26.1 {
        /*return new CommandSourceStack(output, pos, rotation, level, permissions(permission), name, displayName, server, entity);
        *///?} else {
        return new CommandSourceStack(output, pos, rotation, level, permission, name, displayName, server, entity);
        //?}
    }

    //? if >=26.1 {
    /*private static net.minecraft.server.permissions.LevelBasedPermissionSet permissions(int level) {
        return net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(net.minecraft.server.permissions.PermissionLevel.byId(level));
    }
    *///?}

    // Levels.

    /** Lowest buildable y (inclusive). */
    public static int minBuildHeight(LevelHeightAccessor level) {
        //? if >=26.1 {
        /*return level.getMinY();
        *///?} else {
        return level.getMinBuildHeight();
        //?}
    }

    /** One above the highest buildable y (exclusive), as 1.21.1's {@code getMaxBuildHeight}. */
    public static int maxBuildHeight(LevelHeightAccessor level) {
        //? if >=26.1 {
        /*return level.getMaxY() + 1;
        *///?} else {
        return level.getMaxBuildHeight();
        //?}
    }

    /** World spawn position (26.x keeps one for the server, the same in every level, as 1.21.1's derived levels did). */
    public static BlockPos spawnPos(ServerLevel level) {
        //? if >=26.1 {
        /*return level.getRespawnData().pos();
        *///?} else {
        return level.getSharedSpawnPos();
        //?}
    }

    /**
     * Time of day in ticks, as 1.21.1's {@code getDayTime} (one world time shared by every level; 26.x: the
     * Overworld clock, which is that same time).
     */
    public static long dayTime(net.minecraft.world.level.Level level) {
        //? if >=26.1 {
        /*return level.getOverworldClockTime();
        *///?} else {
        return level.getDayTime();
        //?}
    }

    /** Whether the dimension is "ultra warm" like the Nether (26.x: its water evaporates). */
    public static boolean ultraWarm(net.minecraft.world.level.Level level) {
        //? if >=26.1 {
        /*return level.environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_EVAPORATES);
        *///?} else {
        return level.dimensionType().ultraWarm();
        //?}
    }

    /** Whether an entity's type is in a tag. */
    public static boolean isType(Entity entity, net.minecraft.tags.TagKey<net.minecraft.world.entity.EntityType<?>> tag) {
        //? if >=26.1 {
        /*return entity.is(tag);
        *///?} else {
        return entity.getType().is(tag);
        //?}
    }

    /** Scoreboard tags of an entity ({@code /tag}). */
    public static java.util.Set<String> tags(Entity entity) {
        //? if >=26.1 {
        /*return entity.entityTags();
        *///?} else {
        return entity.getTags();
        //?}
    }

    // NBT.

    /** A string field, empty when absent (as 1.21.1's {@code getString}). */
    public static String string(net.minecraft.nbt.CompoundTag tag, String key) {
        //? if >=26.1 {
        /*return tag.getStringOr(key, "");
        *///?} else {
        return tag.getString(key);
        //?}
    }

    /** Load saved data (with components) into a block entity, as {@code /data merge block}. */
    public static void loadBlockEntity(net.minecraft.world.level.block.entity.BlockEntity be, net.minecraft.nbt.CompoundTag tag,
                                       net.minecraft.core.HolderLookup.Provider registries) {
        //? if >=26.1 {
        /*try (var reporter = new net.minecraft.util.ProblemReporter.ScopedCollector(be.problemPath(), com.mojang.logging.LogUtils.getLogger())) {
            be.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(reporter, registries, tag));
        }
        *///?} else {
        be.loadWithComponents(tag, registries);
        //?}
    }

    /** An SNBT compound, the whole text (trailing data is an error). */
    public static net.minecraft.nbt.CompoundTag parseCompound(String snbt) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        //? if >=26.1 {
        /*return net.minecraft.nbt.TagParser.parseCompoundFully(snbt);
        *///?} else {
        return net.minecraft.nbt.TagParser.parseTag(snbt);
        //?}
    }

    /** A registry key's id ({@code location()} on 1.21.1, {@code identifier()} on 26.x). */
    public static ResourceLocation id(ResourceKey<?> key) {
        //? if >=26.1 {
        /*return key.identifier();
        *///?} else {
        return key.location();
        //?}
    }

    // Registries and recipes.

    /** A registry of the server's registry access (data-driven ones included); throws if absent. */
    public static <T> net.minecraft.core.Registry<T> registry(net.minecraft.core.RegistryAccess access,
                                                              ResourceKey<? extends net.minecraft.core.Registry<? extends T>> key) {
        //? if >=26.1 {
        /*return access.lookupOrThrow(key);
        *///?} else {
        return access.registryOrThrow(key);
        //?}
    }

    /** A registry's tags as (tag id, size), in the registry's order; empty if the registry is absent. */
    public static java.util.List<java.util.Map.Entry<ResourceLocation, Integer>> tagSizes(net.minecraft.core.RegistryAccess access,
                                                                                    ResourceKey<? extends net.minecraft.core.Registry<?>> key) {
        java.util.List<java.util.Map.Entry<ResourceLocation, Integer>> out = new java.util.ArrayList<>();
        //? if >=26.1 {
        /*access.lookup(key).ifPresent(r -> r.getTags().forEach(named -> out.add(java.util.Map.entry(named.key().location(), named.size()))));
        *///?} else {
        access.registry(key).ifPresent(r -> r.getTags().forEach(pair -> out.add(java.util.Map.entry(pair.getFirst().location(), pair.getSecond().size()))));
        //?}
        return out;
    }

    /** A recipe's id. */
    public static ResourceLocation recipeId(net.minecraft.world.item.crafting.RecipeHolder<?> holder) {
        //? if >=26.1 {
        /*return holder.id().identifier();
        *///?} else {
        return holder.id();
        //?}
    }

    /** A recipe's result item (26.x: the first stack its recipe-book display shows); empty if it has none. */
    public static net.minecraft.world.item.ItemStack recipeResult(net.minecraft.world.item.crafting.Recipe<?> recipe,
                                                                  net.minecraft.core.RegistryAccess access) {
        //? if >=26.3 {
        /*var displays = recipe.display();
        if (displays.isEmpty()) return net.minecraft.world.item.ItemStack.EMPTY;
        var context = net.minecraft.util.context.ContextMap.builder()
                .set(net.minecraft.world.item.crafting.display.SlotDisplayContext.REGISTRIES, access)
                .buildAndValidate(net.minecraft.world.item.crafting.display.SlotDisplayContext.CONTEXT);
        return displays.getFirst().result().resolveForFirstStack(context);
        *///?} else if >=26.1 {
        /*var displays = recipe.display();
        if (displays.isEmpty()) return net.minecraft.world.item.ItemStack.EMPTY;
        var context = new net.minecraft.util.context.ContextMap.Builder()
                .withParameter(net.minecraft.world.item.crafting.display.SlotDisplayContext.REGISTRIES, access)
                .create(net.minecraft.world.item.crafting.display.SlotDisplayContext.CONTEXT);
        return displays.getFirst().result().resolveForFirstStack(context);
        *///?} else {
        return recipe.getResultItem(access);
        //?}
    }

    /** A recipe's non-empty ingredients, each as the items it accepts. */
    //? if >=26.1
    /*@SuppressWarnings("deprecation") // Ingredient.items: the one view that covers NeoForge custom ingredients too*/
    public static java.util.List<java.util.List<net.minecraft.world.item.Item>> ingredients(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        java.util.List<java.util.List<net.minecraft.world.item.Item>> out = new java.util.ArrayList<>();
        //? if >=26.1 {
        /*for (net.minecraft.world.item.crafting.Ingredient ingredient : recipe.placementInfo().ingredients()) {
            if (ingredient.isEmpty()) continue;
            out.add(ingredient.items().map(net.minecraft.core.Holder::value).toList());
        }
        *///?} else {
        for (net.minecraft.world.item.crafting.Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            out.add(java.util.Arrays.stream(ingredient.getItems()).map(net.minecraft.world.item.ItemStack::getItem).toList());
        }
        //?}
        return out;
    }

    // Chunk positions (26.x: a record with packed longs).

    public static long chunkKey(int x, int z) {
        //? if >=26.1 {
        /*return ChunkPos.pack(x, z);
        *///?} else {
        return ChunkPos.asLong(x, z);
        //?}
    }

    public static ChunkPos chunkPos(long key) {
        //? if >=26.1 {
        /*return ChunkPos.unpack(key);
        *///?} else {
        return new ChunkPos(key);
        //?}
    }

    public static int chunkX(ChunkPos pos) {
        //? if >=26.1 {
        /*return pos.x();
        *///?} else {
        return pos.x;
        //?}
    }

    public static int chunkZ(ChunkPos pos) {
        //? if >=26.1 {
        /*return pos.z();
        *///?} else {
        return pos.z;
        //?}
    }

    /** The direction nearest to a vector. */
    public static Direction nearest(double x, double y, double z) {
        //? if >=26.1 {
        /*return Direction.getApproximateNearest(x, y, z);
        *///?} else {
        return Direction.getNearest(x, y, z);
        //?}
    }

    // Text events (26.x: one record per action).

    public static ClickEvent runCommand(String command) {
        //? if >=26.1 {
        /*return new ClickEvent.RunCommand(command);
        *///?} else {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
        //?}
    }

    public static ClickEvent suggestCommand(String command) {
        //? if >=26.1 {
        /*return new ClickEvent.SuggestCommand(command);
        *///?} else {
        return new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command);
        //?}
    }

    public static ClickEvent copyToClipboard(String text) {
        //? if >=26.1 {
        /*return new ClickEvent.CopyToClipboard(text);
        *///?} else {
        return new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text);
        //?}
    }

    /** Open a link (an absolute URL this mod built itself). */
    public static ClickEvent openUrl(String url) {
        //? if >=26.1 {
        /*return new ClickEvent.OpenUrl(java.net.URI.create(url));
        *///?} else {
        return new ClickEvent(ClickEvent.Action.OPEN_URL, url);
        //?}
    }

    public static HoverEvent showText(Component text) {
        //? if >=26.1 {
        /*return new HoverEvent.ShowText(text);
        *///?} else {
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
        //?}
    }

    // Interaction results (26.x: a sealed interface; the swing and item use live in its Success).

    /** Whether the hand swings after this result (1.21.1: {@code shouldSwing}; 26.x: a success that swings). */
    public static boolean swings(InteractionResult r) {
        //? if >=26.1 {
        /*return r instanceof InteractionResult.Success s && s.swingSource() != InteractionResult.SwingSource.NONE;
        *///?} else {
        return r.shouldSwing();
        //?}
    }

    /** Whether the result used the item in hand (the advancement criterion gets the item). */
    public static boolean usedItem(InteractionResult r) {
        //? if >=26.1 {
        /*return r instanceof InteractionResult.Success s && s.wasItemInteraction();
        *///?} else {
        return r.indicateItemUse();
        //?}
    }

    /** The result's name in lower case ({@code success}, {@code consume}, {@code pass}, {@code fail}, …). */
    public static String resultName(InteractionResult r) {
        //? if >=26.1 {
        /*return switch (r) {
            case InteractionResult.Success s -> s.swingSource() == InteractionResult.SwingSource.NONE ? "consume" : "success";
            case InteractionResult.Fail f -> "fail";
            case InteractionResult.Pass p -> "pass";
            case InteractionResult.TryEmptyHandInteraction t -> "try_with_empty_hand";
        };
        *///?} else {
        return r.name().toLowerCase(Locale.ROOT);
        //?}
    }

    /** Swing the hand as the server does after an interaction a client asked for (seen by others and the player). */
    public static void swing(net.minecraft.server.level.ServerPlayer p, net.minecraft.world.InteractionHand hand) {
        //? if >=26.3 {
        /*p.swing(hand, p.getItemInHand(hand).getInteractAnimation(), true);
        *///?} else {
        p.swing(hand, true);
        //?}
    }

    /** The packet a client sends when it swings the main hand at nothing or at a block it digs. */
    public static net.minecraft.network.protocol.Packet<?> swingPacket() {
        //? if >=26.3 {
        /*return net.minecraft.network.protocol.game.ServerboundPunchPacket.INSTANCE;
        *///?} else {
        return new net.minecraft.network.protocol.game.ServerboundSwingPacket(net.minecraft.world.InteractionHand.MAIN_HAND);
        //?}
    }

    /** Drop an item the player could not carry, as a client's "drop" would (thrown from the hand: no). */
    public static void drop(net.minecraft.server.level.ServerPlayer p, net.minecraft.world.item.ItemStack stack) {
        //? if >=26.3 {
        /*p.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
        *///?} else {
        p.drop(stack, false);
        //?}
    }

    /** Whether an advancement's display announces it in chat, and its title. */
    public static boolean announces(net.minecraft.advancements.DisplayInfo d) {
        //? if >=26.3 {
        /*return d.announceToChat();
        *///?} else {
        return d.shouldAnnounceChat();
        //?}
    }

    public static net.minecraft.network.chat.Component title(net.minecraft.advancements.DisplayInfo d) {
        //? if >=26.3 {
        /*return d.title();
        *///?} else {
        return d.getTitle();
        //?}
    }
}
