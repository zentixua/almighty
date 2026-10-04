package ua.zentix.almighty.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import com.mojang.util.UndashedUuid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.slf4j.Logger;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.world.Areas;
import ua.zentix.almighty.world.Dims;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Боты одного запуска сервера: вход, список, тик, выход; пометка в табе и чате; защита настоящих игроков. Бот не
 * может взять UUID настоящего игрока (свой UUID из имени в своём пространстве) и уступает имя игроку, который
 * заходит с ним. Без {@code bots.allow_disguise} бот всегда помечен и не берёт имя или скин игрока, которого сервер
 * знает (в игре, в белом списке, оператор, в кэше профилей). Поток сервера, кроме разбора параметров.
 */
public final class Bots {
    private static final Logger LOG = LogUtils.getLogger();
    /** Имя, которое команды понимают как имя игрока ({@code /tp Pilot_1}). */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final GmServer gm;
    private final Map<String, Bot> byName = new LinkedHashMap<>();
    private final Map<UUID, Bot> byUuid = new HashMap<>();
    /** Входы, которые ждут загрузки места. */
    private final List<Waiting> waiting = new ArrayList<>();

    /** Срок загрузки места входа, тиков. */
    static final int PLACE_WAIT = 20 * 30;

    private record Waiting(Areas.Lease lease, long deadline, CompletableFuture<Areas.Lease> ready) {}

    /** Аренда места входа после входа — до {@code until} или выхода бота. */
    private final List<Held> held = new ArrayList<>();
    static final int HOLD_AFTER = 100;

    private record Held(Bot bot, Areas.Lease lease, long until) {}

    public Bots(GmServer gm) {
        this.gm = gm;
    }

    /** Параметры входа бота: разобраны и проверены в потоке моста. */
    public record Spec(String name, double[] pos, String dimension, float yaw, float pitch, GameType gamemode,
                       String skinOf, Property textures, boolean marker, boolean autoRespawn) {
        public static Spec parse(Args a) throws RpcException {
            String name = a.string("name");
            if (!NAME.matcher(name).matches()) throw RpcException.badRequest("name: 3–16 латинских букв, цифр или _ (так имя понимают команды)");
            double[] pos = a.has("pos") ? Steps.numbers(a, "pos", 3) : null;
            GameType mode = null;
            if (a.has("gamemode")) {
                mode = GameType.byName(a.string("gamemode"), null);
                if (mode == null) throw RpcException.badRequest("gamemode: survival, creative, adventure или spectator");
            }
            String skinOf = null;
            Property textures = null;
            if (a.has("skin")) {
                if (a.raw().get("skin").isJsonObject()) {
                    Args s = new Args(a.object("skin"));
                    textures = new Property("textures", s.string("value"), s.string("signature", null));
                    texturesOwner(textures);
                } else {
                    skinOf = a.string("skin");
                    if (!NAME.matcher(skinOf).matches()) throw RpcException.badRequest("skin: имя аккаунта Minecraft или {value, signature}");
                }
            }
            return new Spec(name, pos, a.string("dimension", null), (float) a.number("yaw", 0, -360, 360), (float) a.number("pitch", 0, -90, 90),
                    mode, skinOf, textures, a.bool("marker", true), a.bool("auto_respawn", true));
        }
    }

    /** Вход бота: проверки здесь, скин (если по имени аккаунта) — в фоне через службы сервера, вход — в потоке сервера. */
    public CompletableFuture<JsonObject> spawn(Spec spec) {
        return gm.onMain(() -> {
            check(spec);
            return spec;
        }).thenCompose(s -> s.skinOf() == null
                ? CompletableFuture.completedFuture(Optional.ofNullable(s.textures()))
                : SkullBlockEntity.fetchGameProfile(s.skinOf())
                        .handle((profile, e) -> profile == null ? Optional.<Property>empty()
                                : profile.flatMap(p -> p.getProperties().get("textures").stream().findFirst())))
                .thenCompose(textures -> gm.onMain(() -> {
                    check(spec);
                    return prepare(spec);
                }).thenCompose(ready -> ready).thenCompose(lease -> gm.onMain(() -> {
                    Bot bot;
                    try {
                        check(spec);
                        bot = place(spec, textures.orElse(null));
                    } catch (RpcException | RuntimeException e) {
                        if (lease != null) lease.release();
                        throw e;
                    }
                    // дальше чанки держит тикет самого игрока: он встаёт не сразу (очередь тикетов игроков), аренда — ещё немного
                    if (lease != null) held.add(new Held(bot, lease, tickNow() + HOLD_AFTER));
                    JsonObject out = bot.describe(0);
                    if (spec.skinOf() != null && textures.isEmpty()) out.addProperty("skin", "скин «" + spec.skinOf() + "» не найден: обычный");
                    return out;
                })));
    }

    /**
     * Место входа — загрузить до входа: перенос игрока в NeoForge грузит чанк назначения сразу ({@code Entity.setPosRaw}),
     * в тике это пауза сервера. Чанк с кольцом соседей — тикетом ведущего в фоне ({@link Areas}); null — переноса не будет
     * (бот остаётся там, где вошёл, у точки появления). Поток сервера.
     */
    private CompletableFuture<Areas.Lease> prepare(Spec spec) throws RpcException {
        MinecraftServer server = gm.server();
        ServerLevel level = spec.dimension() != null ? Dims.level(server, spec.dimension()) : server.overworld();
        if (spec.pos() == null && level == server.overworld()) return CompletableFuture.completedFuture(null);
        int x = spec.pos() != null ? Mth.floor(spec.pos()[0]) : level.getSharedSpawnPos().getX();
        int z = spec.pos() != null ? Mth.floor(spec.pos()[2]) : level.getSharedSpawnPos().getZ();
        Areas.Lease lease = gm.areas().acquire(level, "бот " + spec.name(), x, z, x, z, PLACE_WAIT + 200);
        if (lease.isReady()) return CompletableFuture.completedFuture(lease);
        CompletableFuture<Areas.Lease> ready = new CompletableFuture<>();
        waiting.add(new Waiting(lease, tickNow() + PLACE_WAIT, ready));
        return ready;
    }

    private void check(Spec spec) throws RpcException {
        MinecraftServer server = gm.server();
        if (byName.containsKey(key(spec.name()))) throw RpcException.conflict("Бот " + spec.name() + " уже в игре");
        if (byName.size() >= GmConfig.BOTS_MAX.get()) throw RpcException.conflict("Ботов уже " + byName.size() + " — это предел bots.max в config/" + Almighty.ID + "-common.toml");
        if (server.getPlayerList().getPlayerByName(spec.name()) != null) throw RpcException.conflict("Игрок " + spec.name() + " в игре: бот не займёт его имя");
        boolean disguise = GmConfig.BOTS_ALLOW_DISGUISE.get();
        if (!disguise && !spec.marker()) throw RpcException.badRequest("Бот без пометки — только с bots.allow_disguise = true (решает владелец сервера)");
        if (!disguise && known(spec.name())) throw RpcException.badRequest("Имя " + spec.name() + " — игрока этого сервера: только с bots.allow_disguise = true (решает владелец сервера)");
        if (!disguise && spec.skinOf() != null && known(spec.skinOf())) throw RpcException.badRequest("Скин игрока этого сервера — только с bots.allow_disguise = true (решает владелец сервера)");
        if (!disguise && spec.textures() != null) {
            GameProfile owner = texturesOwner(spec.textures());
            if (known(owner.getName()) || knownId(owner.getId())) throw RpcException.badRequest("Скин игрока этого сервера — только с bots.allow_disguise = true (решает владелец сервера)");
        }
        if (spec.dimension() != null) Dims.level(server, spec.dimension());
    }

    /** Имя знакомо серверу: игрок в игре (не бот), белый список, операторы, кэш профилей (кроме ботов). */
    private boolean known(String name) {
        MinecraftServer server = gm.server();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!byUuid.containsKey(p.getUUID()) && p.getGameProfile().getName().equalsIgnoreCase(name)) return true;
        }
        for (String n : server.getPlayerList().getWhiteListNames()) if (n.equalsIgnoreCase(name)) return true;
        for (String n : server.getPlayerList().getOpNames()) if (n.equalsIgnoreCase(name)) return true;
        GameProfile cached = ProfileCache.byName(server.getProfileCache(), name);
        return cached != null && !cached.getId().equals(uuid(name));
    }

    /** UUID знаком серверу: игрок в игре (не бот), белый список, операторы, кэш профилей. */
    private boolean knownId(UUID id) {
        if (id == null || byUuid.containsKey(id)) return false;
        MinecraftServer server = gm.server();
        if (server.getPlayerList().getPlayer(id) != null) return true;
        GameProfile probe = new GameProfile(id, "");
        if (server.getPlayerList().getWhiteList().get(probe) != null || server.getPlayerList().getOps().get(probe) != null) return true;
        return server.getProfileCache() != null && server.getProfileCache().get(id).isPresent();
    }

    /**
     * Чей скин в свойстве {@code textures}: его {@code value} — base64 JSON с {@code profileId} и {@code profileName}
     * владельца (так его подписывают службы Mojang). Не читается — ошибка запроса.
     */
    static GameProfile texturesOwner(Property textures) throws RpcException {
        try {
            JsonObject o = JsonParser.parseString(new String(Base64.getDecoder().decode(textures.value()), StandardCharsets.UTF_8)).getAsJsonObject();
            String name = o.has("profileName") ? o.get("profileName").getAsString() : "";
            UUID id = o.has("profileId") ? UndashedUuid.fromStringLenient(o.get("profileId").getAsString()) : null;
            return new GameProfile(id, name);
        } catch (RuntimeException e) {
            throw RpcException.badRequest("skin.value: не base64 JSON текстур Mojang (" + e.getMessage() + ")");
        }
    }

    private Bot place(Spec spec, Property textures) throws RpcException {
        MinecraftServer server = gm.server();
        GameProfile profile = new GameProfile(uuid(spec.name()), spec.name());
        if (textures != null) profile.getProperties().put("textures", textures);
        // вид как у обычного клиента: все слои скина, правая рука, есть в списке игроков сервера
        ClientInformation info = new ClientInformation("en_us", 8, ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true);
        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, info, false, ConnectionType.NEOFORGE);
        ServerLevel level = spec.dimension() != null ? Dims.level(server, spec.dimension()) : server.overworld();
        // как вход игрока (getPlayerForLogin): в верхнем мире — конструктор ищет место у точки появления, а в чужом
        // измерении с небом искал бы его, загружая чанки в тике сразу
        ServerPlayer player = new BotPlayer(server, server.overworld(), profile, info);
        Bot bot = new Bot(this, profile, spec.marker(), spec.autoRespawn());
        // до входа: имя в табе считается при входе, событие должно уже знать бота
        byName.put(key(spec.name()), bot);
        byUuid.put(profile.getId(), bot);
        ProfileCache.Snapshot cache = ProfileCache.snapshot(server.getProfileCache(), spec.name(), profile.getId());
        try {
            server.getPlayerList().placeNewPlayer(bot.connection(), player, cookie);
        } catch (RuntimeException e) {
            forget(bot);
            bot.disconnect(Component.literal("не вошёл"));
            throw e;
        } finally {
            // вход записал бота в кэш профилей: имя → его UUID; команды по имени (/op, /whitelist) нашли бы бота
            cache.restore();
        }
        ServerPlayer p = bot.player();
        if (spec.gamemode() != null) p.setGameMode(spec.gamemode());
        if (spec.pos() != null) {
            p.teleportTo(level, spec.pos()[0], spec.pos()[1], spec.pos()[2], spec.yaw(), spec.pitch());
        } else if (spec.dimension() != null && p.level() != level) {
            p.teleportTo(level, level.getSharedSpawnPos().getX() + 0.5, level.getSharedSpawnPos().getY(), level.getSharedSpawnPos().getZ() + 0.5, spec.yaw(), spec.pitch());
        }
        // голова — туда же, куда тело: взгляд (getViewYRot) берёт поворот головы, а его тик выставит только потом
        p.setYHeadRot(p.getYRot());
        // тикет игрока — сразу на новое место: чанки там начнут грузиться в фоне, пока бот ждёт их (Controls.move)
        p.serverLevel().getChunkSource().move(p);
        bot.settle();
        LOG.info("Ведущий: бот {} вошёл{}", spec.name(), spec.marker() ? "" : " без пометки");
        return bot;
    }

    /**
     * UUID бота: из имени, в своём пространстве — не совпадёт ни с настоящим (v4), ни с пиратским UUID игрока
     * ({@code OfflinePlayer:<имя>}). От id мода не зависит: бот с тем же именем находит свои данные и после переименования мода.
     */
    public static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("AgentBot:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Bot find(String name) {
        return byName.get(key(name));
    }

    public Bot get(String name) throws RpcException {
        Bot bot = find(name);
        if (bot == null) throw RpcException.notFound("Нет бота " + name + " (боты: " + String.join(", ", names()) + ")");
        return bot;
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Bot b : byName.values()) out.add(b.name);
        return out;
    }

    public List<Bot> all() {
        return new ArrayList<>(byName.values());
    }

    /** Бот этого игрока; не бот — null. */
    public Bot of(ServerPlayer player) {
        return byUuid.get(player.getUUID());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * Программа шагов боту ({@code bot.act}): разбор — здесь (поток моста), очередь — в потоке сервера; ждать конца не
     * дольше {@code waitSeconds}; не дождались — описание идущей, конец придёт событием {@code bot_done}.
     */
    public CompletableFuture<JsonElement> act(String name, JsonArray actions, boolean replace, int waitSeconds) throws RpcException {
        List<Steps.Step> steps = Steps.parse(actions);
        return gm.onMain(() -> get(name).start(steps, replace)).thenCompose(program -> {
            CompletableFuture<JsonElement> out = new CompletableFuture<>();
            program.done().thenAccept(out::complete);
            if (!program.done().isDone()) {
                CompletableFuture.delayedExecutor(waitSeconds, TimeUnit.SECONDS).execute(() -> {
                    if (out.isDone()) return;
                    gm.onMain(() -> {
                        if (program.done().isDone()) return program.done().join();
                        program.detached = true;
                        return program.describe();
                    }).whenComplete((d, e) -> {
                        if (e != null) out.completeExceptionally(e);
                        else out.complete(d);
                    });
                });
            }
            return out;
        });
    }

    public void remove(Bot bot, String reason) {
        bot.disconnect(Component.literal(reason));
        gone(bot);
    }

    public JsonArray describe() {
        JsonArray out = new JsonArray();
        for (Bot b : byName.values()) out.add(b.brief());
        return out;
    }

    /** Конец тика сервера: тики ботов; ушедшие (выгнали, ошибка) — из списка, событием в ленту. */
    public void tick() {
        if (!waiting.isEmpty()) places();
        if (!held.isEmpty()) held.removeIf(h -> {
            if (tickNow() < h.until()) return false;
            h.lease().release();
            return true;
        });
        if (byName.isEmpty()) return;
        for (Bot bot : all()) {
            if (!bot.tick()) {
                bot.closed();
                gone(bot);
            }
        }
    }

    /** Места входа, которые загрузились или не дождались; продолжения (вход) — после обхода. */
    private void places() {
        List<Runnable> after = new ArrayList<>();
        for (Iterator<Waiting> it = waiting.iterator(); it.hasNext(); ) {
            Waiting w = it.next();
            if (w.lease().isReady()) {
                it.remove();
                after.add(() -> w.ready().complete(w.lease()));
            } else if (tickNow() >= w.deadline() || w.lease().released() || w.lease().refusal() != null) {
                it.remove();
                String refusal = w.lease().refusal();
                w.lease().release();
                after.add(() -> w.ready().completeExceptionally(RpcException.unavailable(refusal != null
                        ? "Место входа бота: " + refusal : "Место входа бота не загрузилось за " + PLACE_WAIT / 20 + " с")));
            }
        }
        after.forEach(Runnable::run);
    }

    /** Остановка сервера: боты выходят как игроки (сохранение, выход в чат) до того, как сервер сохранит остальных. */
    public void stop() {
        for (Waiting w : waiting) {
            w.lease().release();
            w.ready().completeExceptionally(RpcException.unavailable("Сервер останавливается"));
        }
        waiting.clear();
        held.forEach(h -> h.lease().release());
        held.clear();
        for (Bot bot : all()) {
            bot.disconnect(Component.translatable("multiplayer.disconnect.server_shutdown"));
            forget(bot);
        }
    }

    private void gone(Bot bot) {
        // ушёл — чанки места входа ему больше не нужны
        if (!held.isEmpty()) held.removeIf(h -> {
            if (h.bot() != bot) return false;
            h.lease().release();
            return true;
        });
        if (!forget(bot)) return;
        JsonObject d = new JsonObject();
        d.addProperty("bot", bot.name);
        d.addProperty("event", "left");
        if (bot.leftReason() != null) d.addProperty("reason", bot.leftReason());
        feed("bot", d);
    }

    private boolean forget(Bot bot) {
        byUuid.remove(bot.uuid);
        return byName.remove(key(bot.name), bot);
    }

    void feed(String type, JsonObject data) {
        gm.feed().add(type, data);
    }

    long tickNow() {
        return gm.server().getTickCount();
    }

    // --- пометка и защита имени: события игровой шины ---

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOW, Bots::onTabName);
        bus.addListener(EventPriority.LOW, Bots::onName);
        bus.addListener(EventPriority.HIGHEST, Bots::onLogin);
    }

    private static Bot botOf(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof ServerPlayer sp)) return null;
        GmServer gm = GmServer.of(sp.server);
        return gm == null ? null : gm.bots().of(sp);
    }

    private static Component marked(Component name) {
        return Component.literal(GmConfig.BOTS_MARKER.get() + " ").withStyle(ChatFormatting.GRAY).append(name.copy().withStyle(ChatFormatting.WHITE));
    }

    /** Строка в табе: пометка перед именем. */
    private static void onTabName(PlayerEvent.TabListNameFormat e) {
        Bot bot = botOf(e.getEntity());
        if (bot == null || !bot.marked || GmConfig.BOTS_MARKER.get().isBlank()) return;
        Component current = e.getDisplayName();
        e.setDisplayName(marked(current != null ? current : e.getEntity().getName()));
    }

    /** Имя в чате и сообщениях сервера (вошёл, умер): с пометкой. */
    private static void onName(PlayerEvent.NameFormat e) {
        Bot bot = botOf(e.getEntity());
        if (bot == null || !bot.marked || GmConfig.BOTS_MARKER.get().isBlank()) return;
        e.setDisplayname(marked(e.getDisplayname()));
    }

    /** Настоящий игрок зашёл с именем бота: бот уходит, двух игроков с одним именем на сервере не бывает. */
    private static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        GmServer gm = GmServer.of(player.server);
        if (gm == null || gm.bots().of(player) != null) return;
        Bot bot = gm.bots().find(player.getGameProfile().getName());
        if (bot != null) gm.bots().remove(bot, "уступил имя игроку " + player.getGameProfile().getName());
    }

    /**
     * Кэш профилей сервера ({@code usercache.json}): вход игрока пишет в него «имя → UUID», а команды по имени
     * ({@code /op}, {@code /whitelist add}, {@code /ban}) берут UUID оттуда. Бот запись после себя убирает: прежняя
     * запись этого имени возвращается. Открытого API на это нет — поля кэша рефлексией; не вышло — бот входит как
     * есть, в лог — предупреждение.
     */
    static final class ProfileCache {
        private static Field byNameField, byUuidField, profileField;
        private static boolean broken;

        record Snapshot(GameProfileCache cache, String name, UUID uuid, Object byName, Object byUuid) {
            @SuppressWarnings("unchecked")
            void restore() {
                if (cache == null) return;
                try {
                    Map<String, Object> names = (Map<String, Object>) byNameField.get(cache);
                    Map<UUID, Object> uuids = (Map<UUID, Object>) byUuidField.get(cache);
                    if (byName != null) names.put(name, byName);
                    else names.remove(name);
                    if (byUuid != null) uuids.put(uuid, byUuid);
                    else uuids.remove(uuid);
                    cache.save();
                } catch (ReflectiveOperationException | RuntimeException e) {
                    LOG.warn("Кэш профилей: запись бота {} не убрана", name, e);
                }
            }
        }

        private static boolean ready() {
            if (broken) return false;
            if (byNameField != null) return true;
            try {
                byNameField = ObfuscationReflectionHelper.findField(GameProfileCache.class, "profilesByName");
                byUuidField = ObfuscationReflectionHelper.findField(GameProfileCache.class, "profilesByUUID");
                Class<?> info = Class.forName(GameProfileCache.class.getName() + "$GameProfileInfo");
                profileField = ObfuscationReflectionHelper.findField(info, "profile");
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                broken = true;
                byNameField = null;
                LOG.warn("Кэш профилей недоступен: боты останутся в usercache.json", e);
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        static Snapshot snapshot(GameProfileCache cache, String name, UUID uuid) {
            if (cache == null || !ready()) return new Snapshot(null, name, uuid, null, null);
            try {
                String key = name.toLowerCase(Locale.ROOT);
                Object n = ((Map<String, Object>) byNameField.get(cache)).get(key);
                Object u = ((Map<UUID, Object>) byUuidField.get(cache)).get(uuid);
                return new Snapshot(cache, key, uuid, n, u);
            } catch (ReflectiveOperationException e) {
                return new Snapshot(null, name, uuid, null, null);
            }
        }

        /** Профиль по имени из кэша, без запроса к Mojang (у {@code GameProfileCache.get(name)} он бывает). */
        @SuppressWarnings("unchecked")
        static GameProfile byName(GameProfileCache cache, String name) {
            if (cache == null || !ready()) return null;
            try {
                Object info = ((Map<String, Object>) byNameField.get(cache)).get(name.toLowerCase(Locale.ROOT));
                return info == null ? null : (GameProfile) profileField.get(info);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }
}
