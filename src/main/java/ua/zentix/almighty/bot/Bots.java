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
import org.slf4j.Logger;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.platform.Mc;
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
 * Bots for one server run: login, list, tick, logout; tab and chat markers; protection of real players.
 * A bot cannot take a real player's UUID (its UUID comes from its name in its own namespace), and yields its name
 * to a player logging in with it. Without {@code bots.allow_disguise}, a bot is always marked and cannot take
 * the name or skin of a player known to the server (online, whitelisted, operator, profile cache).
 * Server thread except parameter parsing.
 */
public final class Bots {
    private static final Logger LOG = LogUtils.getLogger();
    /** A name commands recognize as a player name ({@code /tp Pilot_1}). */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final GmServer gm;
    private final Map<String, Bot> byName = new LinkedHashMap<>();
    /** Names of the bots, readable from any thread (the gate tells a bot's eyes from a player's). */
    private final java.util.Set<String> names = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Bot> byUuid = new HashMap<>();
    /** Logins awaiting destination loading. */
    private final List<Waiting> waiting = new ArrayList<>();

    /** Login destination loading deadline, in ticks. */
    static final int PLACE_WAIT = 20 * 30;

    private record Waiting(Areas.Lease lease, long deadline, CompletableFuture<Areas.Lease> ready) {}

    /** Login destination lease after login, until {@code until} or bot logout. */
    private final List<Held> held = new ArrayList<>();
    static final int HOLD_AFTER = 100;

    private record Held(Bot bot, Areas.Lease lease, long until) {}

    public Bots(GmServer gm) {
        this.gm = gm;
    }

    /** Bot login parameters: parsed and validated on the bridge thread. */
    public record Spec(String name, double[] pos, String dimension, float yaw, float pitch, GameType gamemode,
                       String skinOf, Property textures, boolean marker, boolean autoRespawn, String meter) {
        /** The same bot, its online minutes charged to {@code agent}'s {@code bot_minutes} (spawned below owner). */
        public Spec metered(String agent) {
            return new Spec(name, pos, dimension, yaw, pitch, gamemode, skinOf, textures, marker, autoRespawn, agent);
        }

        public static Spec parse(Args a) throws RpcException {
            String name = a.string("name");
            if (!NAME.matcher(name).matches()) throw RpcException.badRequest("name: 3–16 Latin letters, digits or _ (the names commands accept)");
            double[] pos = a.has("pos") ? Steps.numbers(a, "pos", 3) : null;
            GameType mode = null;
            if (a.has("gamemode")) {
                mode = GameType.byName(a.string("gamemode"), null);
                if (mode == null) throw RpcException.badRequest("gamemode: survival, creative, adventure or spectator");
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
                    if (!NAME.matcher(skinOf).matches()) throw RpcException.badRequest("skin: a Minecraft account name or {value, signature}");
                }
            }
            return new Spec(name, pos, a.string("dimension", null), (float) a.number("yaw", 0, -360, 360), (float) a.number("pitch", 0, -90, 90),
                    mode, skinOf, textures, a.bool("marker", true), a.bool("auto_respawn", true), null);
        }
    }

    /** Bot login: checks here, skin lookup (if an account name) in the background through server services, login on the server thread. */
    public CompletableFuture<JsonObject> spawn(Spec spec) {
        // Who spawns it, while the call's caller is still known (the login continues in later stages).
        String by = ua.zentix.almighty.bridge.Dispatch.current().name();
        return gm.onMain(() -> {
            check(spec);
            // The first minute up front: an agent whose bot minutes are used up gets 429 before anything loads.
            if (spec.meter() != null) chargeMinute(spec.meter());
            return spec;
        }).thenCompose(s -> s.skinOf() == null
                ? CompletableFuture.completedFuture(Optional.ofNullable(s.textures()))
                : Mc.fetchProfile(gm.server(), s.skinOf())
                        .handle((profile, e) -> profile == null ? Optional.<Property>empty()
                                : profile.flatMap(p -> Mc.properties(p).get("textures").stream().findFirst())))
                .thenCompose(textures -> gm.onMain(() -> {
                    check(spec);
                    return prepare(spec);
                }).thenCompose(ready -> ready).thenCompose(lease -> gm.onMain(() -> {
                    Bot bot;
                    try {
                        check(spec);
                        bot = place(spec, textures.orElse(null));
                        bot.spawner = by;
                    } catch (RpcException | RuntimeException e) {
                        if (lease != null) lease.release();
                        throw e;
                    }
                    // The player's own ticket retains chunks afterward; it is queued, so keep the lease a little longer.
                    if (lease != null) held.add(new Held(bot, lease, tickNow() + HOLD_AFTER));
                    JsonObject out = bot.describe(0);
                    if (spec.skinOf() != null && textures.isEmpty()) out.addProperty("skin", "skin \"" + spec.skinOf() + "\" not found: default skin");
                    return out;
                })));
    }

    /**
     * Load the login destination before login: NeoForge teleportation loads the destination chunk immediately
     * ({@code Entity.setPosRaw}), stalling the server if done in a tick. A GM ticket loads the chunk and neighbor
     * ring in the background ({@link Areas}); null means no teleport (the bot stays where it logged in, near spawn).
     * Server thread.
     */
    private CompletableFuture<Areas.Lease> prepare(Spec spec) throws RpcException {
        MinecraftServer server = gm.server();
        ServerLevel level = spec.dimension() != null ? Dims.level(server, spec.dimension()) : server.overworld();
        if (spec.pos() == null && level == server.overworld()) return CompletableFuture.completedFuture(null);
        int x = spec.pos() != null ? Mth.floor(spec.pos()[0]) : Mc.spawnPos(level).getX();
        int z = spec.pos() != null ? Mth.floor(spec.pos()[2]) : Mc.spawnPos(level).getZ();
        Areas.Lease lease = gm.areas().acquire(level, "bot " + spec.name(), x, z, x, z, PLACE_WAIT + 200);
        if (lease.isReady()) return CompletableFuture.completedFuture(lease);
        CompletableFuture<Areas.Lease> ready = new CompletableFuture<>();
        waiting.add(new Waiting(lease, tickNow() + PLACE_WAIT, ready));
        return ready;
    }

    private void check(Spec spec) throws RpcException {
        MinecraftServer server = gm.server();
        if (byName.containsKey(key(spec.name()))) throw RpcException.conflict("Bot " + spec.name() + " is already online");
        if (byName.size() >= GmConfig.BOTS_MAX.get()) throw RpcException.conflict("Already " + byName.size() + " bots: that is the bots.max limit in config/" + Almighty.ID + "-common.toml");
        if (server.getPlayerList().getPlayerByName(spec.name()) != null) throw RpcException.conflict("Player " + spec.name() + " is online: a bot cannot take their name");
        boolean disguise = GmConfig.BOTS_ALLOW_DISGUISE.get();
        if (!disguise && !spec.marker()) throw RpcException.badRequest("A bot without the marker needs bots.allow_disguise = true (the server owner decides)");
        if (!disguise && known(spec.name())) throw RpcException.badRequest("The name " + spec.name() + " belongs to a player of this server: needs bots.allow_disguise = true (the server owner decides)");
        if (!disguise && spec.skinOf() != null && known(spec.skinOf())) throw RpcException.badRequest("The skin of a player of this server needs bots.allow_disguise = true (the server owner decides)");
        if (!disguise && spec.textures() != null) {
            GameProfile owner = texturesOwner(spec.textures());
            if (known(Mc.name(owner)) || knownId(Mc.id(owner))) throw RpcException.badRequest("The skin of a player of this server needs bots.allow_disguise = true (the server owner decides)");
        }
        if (spec.dimension() != null) Dims.level(server, spec.dimension());
    }

    /** Name known to the server: online player (not a bot), whitelist, operators, profile cache (excluding bots). */
    private boolean known(String name) {
        MinecraftServer server = gm.server();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!byUuid.containsKey(p.getUUID()) && Mc.name(p.getGameProfile()).equalsIgnoreCase(name)) return true;
        }
        for (String n : server.getPlayerList().getWhiteListNames()) if (n.equalsIgnoreCase(name)) return true;
        for (String n : server.getPlayerList().getOpNames()) if (n.equalsIgnoreCase(name)) return true;
        GameProfile cached = ProfileCache.byName(Mc.profileCache(server), name);
        return cached != null && !Mc.id(cached).equals(uuid(name));
    }

    /** UUID known to the server: online player (not a bot), whitelist, operators, profile cache. */
    private boolean knownId(UUID id) {
        if (id == null || byUuid.containsKey(id)) return false;
        MinecraftServer server = gm.server();
        if (server.getPlayerList().getPlayer(id) != null) return true;
        //? if >=26.1 {
        /*var probe = new net.minecraft.server.players.NameAndId(id, "");
        *///?} else {
        GameProfile probe = new GameProfile(id, "");
        //?}
        if (server.getPlayerList().getWhiteList().get(probe) != null || server.getPlayerList().getOps().get(probe) != null) return true;
        return Mc.profileCache(server) != null && Mc.profileCache(server).get(id).isPresent();
    }

    /**
     * Skin owner in the {@code textures} property: its {@code value} is base64 JSON with the owner's
     * {@code profileId} and {@code profileName} (as signed by Mojang services). Unreadable data is a request error.
     */
    static GameProfile texturesOwner(Property textures) throws RpcException {
        try {
            JsonObject o = JsonParser.parseString(new String(Base64.getDecoder().decode(textures.value()), StandardCharsets.UTF_8)).getAsJsonObject();
            String name = o.has("profileName") ? o.get("profileName").getAsString() : "";
            UUID id = o.has("profileId") ? UndashedUuid.fromStringLenient(o.get("profileId").getAsString()) : null;
            return new GameProfile(id, name);
        } catch (RuntimeException e) {
            throw RpcException.badRequest("skin.value: not base64 JSON of Mojang textures (" + e.getMessage() + ")");
        }
    }

    private Bot place(Spec spec, Property textures) throws RpcException {
        MinecraftServer server = gm.server();
        GameProfile profile = Mc.profile(uuid(spec.name()), spec.name(), textures);
        // Like an ordinary client: all skin layers, right hand, listed among server players.
        //? if >=26.1 {
        /*ClientInformation info = new ClientInformation("en_us", 8, ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true,
                net.minecraft.server.level.ParticleStatus.ALL);
        *///?} else {
        ClientInformation info = new ClientInformation("en_us", 8, ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true);
        //?}
        CommonListenerCookie cookie = ua.zentix.almighty.platform.Platform.get().cookie(profile, info);
        ServerLevel level = spec.dimension() != null ? Dims.level(server, spec.dimension()) : server.overworld();
        //? if >=26.1 {
        /*// 26.x loads a player's data before login (PrepareSpawnTask, which a bot connection skips): the saved level,
        // position and rotation, else the world spawn (no search: it would load chunks; the bot then waits for them).
        ServerPlayer player;
        try (var reporter = new net.minecraft.util.ProblemReporter.ScopedCollector(LOG)) {
            var input = server.getPlayerList().loadPlayerData(new net.minecraft.server.players.NameAndId(profile))
                    .map(tag -> net.minecraft.world.level.storage.TagValueInput.create(reporter, server.registryAccess(), tag));
            // The fields of ServerPlayer.SavedPosition.
            ServerLevel home = input.flatMap(i -> i.read("Dimension", net.minecraft.world.level.Level.RESOURCE_KEY_CODEC))
                    .map(server::getLevel).orElse(server.overworld());
            net.minecraft.world.phys.Vec3 pos = input.flatMap(i -> i.read("Pos", net.minecraft.world.phys.Vec3.CODEC))
                    .orElseGet(() -> net.minecraft.world.phys.Vec3.atBottomCenterOf(Mc.spawnPos(home)));
            net.minecraft.world.phys.Vec2 rot = input.flatMap(i -> i.read("Rotation", net.minecraft.world.phys.Vec2.CODEC))
                    .orElse(net.minecraft.world.phys.Vec2.ZERO);
            player = new BotPlayer(server, home, profile, info);
            input.ifPresent(player::load);
            ua.zentix.almighty.platform.Platform.get().playerLoaded(player);
            player.snapTo(pos, rot.x, rot.y);
        }
        *///?} else {
        // Like player login (getPlayerForLogin): use the Overworld; the constructor searches near spawn, while in another
        // dimension with a sky it would search by loading chunks synchronously in the tick.
        ServerPlayer player = new BotPlayer(server, server.overworld(), profile, info);
        //?}
        Bot bot = new Bot(this, profile, spec.marker(), spec.autoRespawn());
        bot.meter = spec.meter();
        // Before login: the tab name is computed during login, and the event must already know the bot.
        byName.put(key(spec.name()), bot);
        names.add(key(spec.name()));
        byUuid.put(Mc.id(profile), bot);
        ProfileCache.Snapshot cache = ProfileCache.snapshot(Mc.profileCache(server), spec.name(), Mc.id(profile));
        try {
            server.getPlayerList().placeNewPlayer(bot.connection(), player, cookie);
            //? if >=26.1 {
            /*// As PrepareSpawnTask after placeNewPlayer: the vehicle and ender pearls saved with the player come back
            // (on 1.21.1 placeNewPlayer does this itself).
            try (var reporter = new net.minecraft.util.ProblemReporter.ScopedCollector(LOG)) {
                server.getPlayerList().loadPlayerData(new net.minecraft.server.players.NameAndId(profile))
                        .map(tag -> net.minecraft.world.level.storage.TagValueInput.create(reporter, server.registryAccess(), tag))
                        .ifPresent(saved -> {
                            player.loadAndSpawnEnderPearls(saved);
                            player.loadAndSpawnParentVehicle(saved);
                        });
            }
            *///?}
        } catch (RuntimeException e) {
            forget(bot);
            bot.disconnect(Component.literal("failed to join"));
            throw e;
        } finally {
            // Login wrote the bot to the profile cache: name to UUID; commands by name (/op, /whitelist) would find the bot.
            cache.restore();
        }
        ServerPlayer p = bot.player();
        if (spec.gamemode() != null) p.setGameMode(spec.gamemode());
        if (spec.pos() != null) {
            Mc.teleport(p, level, spec.pos()[0], spec.pos()[1], spec.pos()[2], spec.yaw(), spec.pitch());
        } else if (spec.dimension() != null && p.level() != level) {
            Mc.teleport(p, level, Mc.spawnPos(level).getX() + 0.5, Mc.spawnPos(level).getY(), Mc.spawnPos(level).getZ() + 0.5, spec.yaw(), spec.pitch());
        }
        // Head faces with the body: vision (getViewYRot) uses head rotation, which its tick sets only later.
        p.setYHeadRot(p.getYRot());
        // Move the player ticket to the new location immediately: chunks start loading in the background while the bot waits (Controls.move).
        Mc.level(p).getChunkSource().move(p);
        bot.settle();
        LOG.info("GM: bot {} joined{}", spec.name(), spec.marker() ? "" : " without the marker");
        return bot;
    }

    /**
     * Bot UUID: from its name in its own namespace, distinct from real (v4) and offline player UUIDs
     * ({@code OfflinePlayer:<name>}). Independent of the mod id: a bot with the same name finds its data after a mod rename.
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
        if (bot == null) throw RpcException.notFound("No bot " + name + " (bots: " + String.join(", ", names()) + ")");
        return bot;
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Bot b : byName.values()) out.add(b.name);
        return out;
    }

    static final int MINUTE_TICKS = 1200;

    private void chargeMinute(String agent) throws RpcException {
        gm.quotas().charge("agent:" + agent, "bot_minutes", 1, gm.policies().current().limits().botMinutes());
    }

    /** Whether {@code agent} is paused by the owner (its bots get no new programs). */
    boolean paused(String agent) {
        return gm.pause().paused(agent);
    }

    public boolean isBotName(String name) {
        return names.contains(key(name));
    }

    public List<Bot> all() {
        return new ArrayList<>(byName.values());
    }

    /** This player's bot; null if not a bot. */
    public Bot of(ServerPlayer player) {
        return byUuid.get(player.getUUID());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * A bot step program ({@code bot.act}): parse here (bridge thread), queue on the server thread; wait up to
     * {@code waitSeconds} for completion; if still running, return its description, with completion arriving
     * as a {@code bot_done} event.
     */
    public CompletableFuture<JsonElement> act(String name, JsonArray actions, boolean replace, int waitSeconds, com.google.gson.JsonObject params) throws RpcException {
        List<Steps.Step> steps = Steps.parse(actions);
        ua.zentix.almighty.bridge.Caller caller = ua.zentix.almighty.bridge.Dispatch.current();
        return gm.onMain(() -> mine(get(name), caller, params, "drive").start(steps, replace)).thenCompose(program -> {
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

    /**
     * Another agent's bot (rights audit M7): driving or removing it needs bots.admin, as spawning a creative one does;
     * otherwise any agent could drive an owner's creative bot or take an autopilot off its aircraft.
     */
    public Bot mine(Bot bot, ua.zentix.almighty.bridge.Caller caller, com.google.gson.JsonObject params, String what) throws RpcException {
        if (caller.agent() != null && !bot.spawner.equals(caller.name())) {
            gm.gate().require(caller, params, ua.zentix.almighty.policy.Node.BOTS_ADMIN, what + " " + bot.name() + ", spawned by " + bot.spawner);
        }
        return bot;
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

    /** Server tick end: tick bots; remove departed bots (kicked, error) from the list and emit a feed event. */
    public void tick() {
        if (!waiting.isEmpty()) places();
        if (!held.isEmpty()) held.removeIf(h -> {
            if (tickNow() < h.until()) return false;
            h.lease().release();
            return true;
        });
        if (byName.isEmpty()) return;
        for (Bot bot : all()) {
            // Plan F12: a bot spawned below owner costs its agent a minute of bot_minutes per online minute; when they
            // run out, the bot leaves (its chunks with it).
            if (bot.meter != null && ++bot.meteredTicks % MINUTE_TICKS == 0) {
                try {
                    chargeMinute(bot.meter);
                } catch (RpcException e) {
                    remove(bot, "bot minutes of agent " + bot.meter + " used up ([quotas] bot_minutes)");
                    continue;
                }
            }
            if (!bot.tick()) {
                bot.closed();
                gone(bot);
            }
        }
    }

    /** Login destinations loaded or timed out; continuations (login) run after iteration. */
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
                        ? "Bot spawn position: " + refusal : "Bot spawn position did not load within " + PLACE_WAIT / 20 + " s")));
            }
        }
        after.forEach(Runnable::run);
    }

    /** Server shutdown: bots log out as players (saving, chat leave) before the server saves everyone else. */
    public void stop() {
        for (Waiting w : waiting) {
            w.lease().release();
            w.ready().completeExceptionally(RpcException.unavailable("The server is stopping"));
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
        // The bot left; it no longer needs its login destination chunks.
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
        boolean removed = byName.remove(key(bot.name), bot);
        if (removed) names.remove(key(bot.name));
        return removed;
    }

    void feed(String type, JsonObject data) {
        gm.feed().add(type, data);
    }

    long tickNow() {
        return gm.server().getTickCount();
    }

    // --- Markers and name protection: the loader calls these from its name and login events ---

    private static Bot botOf(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof ServerPlayer sp)) return null;
        GmServer gm = GmServer.of(Mc.server(sp));
        return gm == null ? null : gm.bots().of(sp);
    }

    private static Component marked(Component name) {
        return Component.literal(GmConfig.BOTS_MARKER.get() + " ").withStyle(ChatFormatting.GRAY).append(name.copy().withStyle(ChatFormatting.WHITE));
    }

    /** Tab line: marker before the name; null leaves the line as it is. */
    public static Component tabName(net.minecraft.world.entity.player.Player player, Component current) {
        Bot bot = botOf(player);
        if (bot == null || !bot.marked || GmConfig.BOTS_MARKER.get().isBlank()) return null;
        return marked(current != null ? current : player.getName());
    }

    /** Name in chat and server messages (join, death), with a marker; null leaves it as it is. */
    public static Component displayName(net.minecraft.world.entity.player.Player player, Component current) {
        Bot bot = botOf(player);
        if (bot == null || !bot.marked || GmConfig.BOTS_MARKER.get().isBlank()) return null;
        return marked(current);
    }

    /** A real player logged in with a bot's name: the bot leaves; no two players on the server share a name. */
    public static void onLogin(ServerPlayer player) {
        GmServer gm = GmServer.of(Mc.server(player));
        if (gm == null || gm.bots().of(player) != null) return;
        Bot bot = gm.bots().find(Mc.name(player.getGameProfile()));
        if (bot != null) gm.bots().remove(bot, "gave the name up to player " + Mc.name(player.getGameProfile()));
    }

    /**
     * Server profile cache ({@code usercache.json}): player login writes "name to UUID", and commands by name
     * ({@code /op}, {@code /whitelist add}, {@code /ban}) read the UUID there. A bot removes its entry afterward,
     * restoring the previous entry for that name. No public API, so reflect cache fields; on failure, log a warning
     * and let the bot log in as is.
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
                    LOG.warn("Profile cache: the entry of bot {} was not removed", name, e);
                }
            }
        }

        private static boolean ready() {
            if (broken) return false;
            if (byNameField != null) return true;
            try {
                byNameField = ua.zentix.almighty.platform.Reflect.field(GameProfileCache.class, "profilesByName");
                byUuidField = ua.zentix.almighty.platform.Reflect.field(GameProfileCache.class, "profilesByUUID");
                Class<?> info = Class.forName(GameProfileCache.class.getName() + "$GameProfileInfo");
                //? if >=26.1 {
                /*profileField = ua.zentix.almighty.platform.Reflect.field(info, "nameAndId");
                *///?} else {
                profileField = ua.zentix.almighty.platform.Reflect.field(info, "profile");
                //?}
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                broken = true;
                byNameField = null;
                LOG.warn("Profile cache unavailable: bots will stay in usercache.json", e);
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

        /** Profile by name from cache, without a Mojang request (unlike some {@code GameProfileCache.get(name)} calls). */
        @SuppressWarnings("unchecked")
        static GameProfile byName(GameProfileCache cache, String name) {
            if (cache == null || !ready()) return null;
            try {
                Object info = ((Map<String, Object>) byNameField.get(cache)).get(name.toLowerCase(Locale.ROOT));
                //? if >=26.1 {
                /*if (info == null) return null;
                var known = (net.minecraft.server.players.NameAndId) profileField.get(info);
                return new GameProfile(known.id(), known.name());
                *///?} else {
                return info == null ? null : (GameProfile) profileField.get(info);
                //?}
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }
}
