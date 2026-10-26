package ua.zentix.almighty.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.script.Json;
import ua.zentix.almighty.world.Observe;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A bot is a player whose client lives in server memory: a networkless connection ({@link BotSink} instead of a
 * socket), login through {@code PlayerList.placeNewPlayer} like a real player. To everyone else, an ordinary
 * player (tab, chat, entity, inventory, death and respawn, saving under its own UUID). The GM controls it as a
 * client: keys and mouse ({@link Controls}), step programs ({@link Program}), any client packet ({@link #send}).
 * The bot reads what the server sends to the client: acknowledges teleports, puts chat and titles in its inbox,
 * and sounds in hearing.
 * <p>
 * {@code ServerConnectionListener} ticks vanilla connections; ours is absent from its list, so the bot handles
 * player ticking ({@code doTick}) and disconnection itself at server tick end. The packet listener does not tick:
 * keepalive, idle and "flying" checks concern networking, and resetting to the movement packet position would
 * undo bot physics. The connection is the key, not the entity: respawn puts a new {@code ServerPlayer} into it.
 */
public final class Bot {
    private static final Logger LOG = LogUtils.getLogger();
    static final int INBOX = 200;
    static final int SOUNDS = 64;
    /** The client acknowledges signed chat messages above this count (otherwise the server disconnects at 4096). */
    static final int CHAT_ACK_AFTER = 64;

    final String name;
    final UUID uuid;
    final boolean marked;
    private final Bots bots;
    private final Connection connection;
    private final EmbeddedChannel channel;
    private final BotSink sink = new BotSink();
    final Controls controls = new Controls(this);
    final Vehicles vehicles = new Vehicles(this);
    boolean autoRespawn;

    private final ArrayDeque<Program> programs = new ArrayDeque<>();
    private Program current, last;
    private long programIds;
    private final ArrayDeque<JsonObject> inbox = new ArrayDeque<>();
    private long inboxSeq;
    private final ArrayDeque<Heard> heard = new ArrayDeque<>();
    private int chatAcks, deadTicks;
    /** A teleport not yet acknowledged by the bot: waits for the destination chunk ({@link #acceptTeleport}). */
    private int teleportId;
    private boolean teleportPending;
    private Instant lastChat = Instant.EPOCH;
    private String leftReason;
    /** A stop requested outside the bot tick (its controlling rule turned off): apply at the next tick start. */
    private String stopReason;

    /**
     * Bots controlled by the running rule script ({@link #act}, {@link #send}): when the rule turns off, they stop
     * ({@link #stop}) instead of holding their last input. {@code Rules} sets and clears this around execution.
     */
    private static final ThreadLocal<Set<Bot>> DRIVEN = new ThreadLocal<>();

    /** A sound delivered to the bot in a packet. */
    record Heard(long tick, String sound, String source, Vec3 pos, String entity, float volume) {}

    Bot(Bots bots, GameProfile profile, boolean marked, boolean autoRespawn) {
        this.bots = bots;
        this.name = profile.getName();
        this.uuid = profile.getId();
        this.marked = marked;
        this.autoRespawn = autoRespawn;
        this.connection = new Connection(PacketFlow.SERVERBOUND);
        // Register the channel immediately: Connection receives channelActive with the "embedded" address.
        this.channel = new EmbeddedChannel(sink, connection);
        // Like NeoForge GameTest: both ends are NeoForge with all mod channels; otherwise the bot cannot receive mod packets.
        NetworkRegistry.configureMockConnection(connection);
    }

    Connection connection() {
        return connection;
    }

    public String name() {
        return name;
    }

    /** The bot's current entity; a new one after respawn. */
    public ServerPlayer player() {
        return ((ServerGamePacketListenerImpl) connection.getPacketListener()).player;
    }

    public boolean online() {
        return channel.isOpen();
    }

    /**
     * Packet from the bot's "client" to the server, through the same path as a network packet
     * ({@code Connection.channelRead0} to handler), on this thread. Client-to-server packets only;
     * a handler error disconnects the bot like a client.
     */
    public void send(Packet<?> packet) {
        if (packet.type().flow() != PacketFlow.SERVERBOUND) throw new IllegalArgumentException("a bot only takes client-to-server packets (Serverbound…), not " + packet.type());
        if (!channel.isOpen()) return;
        driven();
        channel.writeInbound(packet);
    }

    /** A step program from a script value (list of maps, like {@code actions} in {@code bot.act}); returns the program id. */
    public long act(Object steps) throws RpcException {
        return act(steps, false);
    }

    /** The same; {@code replace} removes running and queued programs (like {@code replace} in {@code bot.act}). */
    public long act(Object steps, boolean replace) throws RpcException {
        if (!(Json.from(steps) instanceof com.google.gson.JsonArray array)) throw RpcException.badRequest("act: expected a list of steps");
        List<Steps.Step> parsed = Steps.parse(array);
        driven();
        return start(parsed, replace).id;
    }

    private void driven() {
        Set<Bot> set = DRIVEN.get();
        if (set != null) set.add(this);
    }

    /** Who controls bots on this thread (a rule during execution); returns the previous value for restoration. */
    public static Set<Bot> driving(Set<Bot> set) {
        Set<Bot> previous = DRIVEN.get();
        if (set == null) DRIVEN.remove();
        else DRIVEN.set(set);
        return previous;
    }

    /**
     * Stop: remove programs and release keys at the next bot tick start (requested from a rule mid-tick,
     * possibly in this same bot's step); emit {@code bot_stopped} to the feed.
     */
    public void stop(String reason) {
        if (stopReason == null) stopReason = reason;
    }

    /**
     * Submit a program (parsed steps). Not named {@code act}: Groovy ignores access restrictions and would select
     * this method from a script's {@code act(list, true)}, passing a list of maps instead of steps.
     */
    Program start(List<Steps.Step> steps, boolean replace) {
        if (replace) cancelAll("replaced by a new program");
        Program program = new Program(++programIds, steps);
        programs.add(program);
        return program;
    }

    void cancelAll(String why) {
        if (current != null) {
            current.cancel(this, why);
            finished(current);
            current = null;
        }
        for (Program p; (p = programs.poll()) != null; ) {
            p.cancel(this, why);
            finished(p);
        }
        if (online()) controls.releaseAll(player());
    }

    /** Bot tick at server tick end; false means the bot left (kicked, error, shutdown). */
    boolean tick() {
        if (!channel.isOpen()) return false;
        try {
            drain();
            acceptTeleport();
            ServerPlayer p = player();
            if (p.isDeadOrDying()) {
                if (++deadTicks >= 20 && autoRespawn) {
                    deadTicks = 0;
                    send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                }
            } else {
                deadTicks = 0;
            }
            Spam.decay((ServerGamePacketListenerImpl) connection.getPacketListener());
            if (stopReason != null) {
                String why = stopReason;
                stopReason = null;
                cancelAll(why);
                JsonObject d = new JsonObject();
                d.addProperty("bot", name);
                d.addProperty("reason", why);
                bots.feed("bot_stopped", d);
            }
            controls.begin(player());
            runPrograms();
            if (channel.isOpen()) controls.end(player());
            drain();
        } catch (RuntimeException e) {
            // Like a server handling a client packet failure: disconnect the player and keep the server alive.
            LOG.warn("Bot {}: error in tick, disconnecting", name, e);
            disconnect(Component.literal("Internal server error"));
        }
        return channel.isOpen();
    }

    /**
     * Handler spam counters (chat, commands, creative dropping): vanilla decrements them once per handler tick,
     * which bots lack; otherwise their 11th message over their lifetime would disconnect them for spam.
     * No public API, so use reflection; on failure, log a warning and leave the bot running as is.
     */
    private static final class Spam {
        private static Field chat, drop;
        private static boolean broken;

        static void decay(ServerGamePacketListenerImpl listener) {
            if (broken) return;
            try {
                if (chat == null) {
                    chat = ObfuscationReflectionHelper.findField(ServerGamePacketListenerImpl.class, "chatSpamTickCount");
                    drop = ObfuscationReflectionHelper.findField(ServerGamePacketListenerImpl.class, "dropSpamTickCount");
                }
                int c = chat.getInt(listener), d = drop.getInt(listener);
                if (c > 0) chat.setInt(listener, c - 1);
                if (d > 0) drop.setInt(listener, d - 1);
            } catch (RuntimeException | IllegalAccessException e) {
                broken = true;
                LOG.warn("Bots: spam counters of the packet listener are unavailable ({}): frequent chat from a bot will get it kicked for spamming", e.toString());
            }
        }
    }

    private void runPrograms() {
        for (int i = 0; i < 8 && channel.isOpen(); i++) {
            if (current == null) {
                current = programs.poll();
                if (current == null) return;
            }
            Program program = current;
            if (!program.tick(this)) return;
            // A step may trigger a rule that calls act(..., true) on this same bot: the removed program has already ended.
            if (current != program) continue;
            finished(program);
            current = null;
        }
    }

    private void finished(Program program) {
        last = program;
        program.complete();
        if (program.detached) {
            JsonObject d = program.describe();
            d.addProperty("bot", name);
            bots.feed("bot_done", d);
        }
    }

    /** Disconnect the bot as the server disconnects a player: chat and feed leave events, saving, removal from the list. */
    void disconnect(Component reason) {
        if (channel.isOpen()) {
            leftReason = reason.getString();
            connection.disconnect(reason);
        }
        closed();
    }

    /** Channel closed: handle disconnection if the server has not done so already (it does when kicking). */
    void closed() {
        if (leftReason == null && connection.getDisconnectionDetails() != null) leftReason = connection.getDisconnectionDetails().reason().getString();
        cancelAll("the bot left the server");
        if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener && !listener.player.hasDisconnected()) {
            connection.handleDisconnection();
        }
    }

    long tickNow() {
        return bots.tickNow();
    }

    String leftReason() {
        return leftReason;
    }

    /** Immediately after login or teleport: acknowledge the teleport without waiting for a tick. */
    void settle() {
        drain();
    }

    /** Everything the server has written to the "client" so far. */
    private void drain() {
        for (Packet<?> p; (p = sink.poll()) != null; ) receive(p);
    }

    private void receive(Packet<?> packet) {
        switch (packet) {
            case BundlePacket<?> bundle -> bundle.subPackets().forEach(this::receive);
            case ClientboundPlayerPositionPacket pos -> {
                // The server waits only for the last id.
                teleportId = pos.getId();
                teleportPending = true;
                acceptTeleport();
            }
            case ClientboundPlayerChatPacket chat -> {
                if (chat.signature() != null && ++chatAcks > CHAT_ACK_AFTER) {
                    send(new ServerboundChatAckPacket(chatAcks));
                    chatAcks = 0;
                }
                String text = chat.unsignedContent() != null ? chat.unsignedContent().getString() : chat.body().content();
                chat(chat.chatType(), text);
            }
            case ClientboundDisguisedChatPacket chat -> chat(chat.chatType(), chat.message().getString());
            case ClientboundSystemChatPacket system -> mail(system.overlay() ? "actionbar" : "system", system.content().getString(), null);
            case ClientboundSetActionBarTextPacket bar -> mail("actionbar", bar.text().getString(), null);
            case ClientboundSetTitleTextPacket title -> mail("title", title.text().getString(), null);
            case ClientboundSetSubtitleTextPacket title -> mail("subtitle", title.text().getString(), null);
            case ClientboundOpenScreenPacket screen -> {
                JsonObject m = mail("screen", screen.getTitle().getString(), null);
                m.addProperty("menu", String.valueOf(BuiltInRegistries.MENU.getKey(screen.getType())));
            }
            case ClientboundOpenSignEditorPacket sign -> {
                JsonObject m = mail("sign_editor", "", null);
                m.add("pos", Observe.pos(sign.getPos()));
                m.addProperty("front", sign.isFrontText());
            }
            case ClientboundPlayerCombatKillPacket kill -> mail("death", kill.message().getString(), null);
            case ClientboundDisconnectPacket bye -> mail("disconnect", bye.reason().getString(), null);
            case ClientboundSoundPacket sound -> hear(sound.getSound().getRegisteredName(), sound.getSource().getName(),
                    new Vec3(sound.getX(), sound.getY(), sound.getZ()), null, sound.getVolume());
            case ClientboundSoundEntityPacket sound -> {
                Entity source = player().level().getEntity(sound.getId());
                if (source != null) hear(sound.getSound().getRegisteredName(), sound.getSource().getName(), source.position(),
                        EntityType.getKey(source.getType()).toString(), sound.getVolume());
            }
            // The bot cannot reconfigure its connection: silently ignoring it would hang the bot while the server awaited a reply.
            case ClientboundStartConfigurationPacket ignored -> connection.disconnect(Component.literal("bots do not support connection reconfiguration"));
            default -> {}
        }
    }

    /**
     * Acknowledge a teleport once the destination chunk is ready: acknowledgment moves the player
     * ({@code absMoveTo}), and NeoForge teleports load the destination chunk immediately. This occurs when a bot
     * logs in at a distant saved position (login teleport is acknowledged after adding it to the world);
     * a client loading the world also replies later. The player ticket loads the chunk.
     */
    private void acceptTeleport() {
        if (!teleportPending) return;
        ServerPlayer p = player();
        if (p.serverLevel().getChunkSource().getChunkNow(p.getBlockX() >> 4, p.getBlockZ() >> 4) == null) return;
        teleportPending = false;
        send(new ServerboundAcceptTeleportationPacket(teleportId));
    }

    private void chat(ChatType.Bound type, String text) {
        String kind = type.chatType().unwrapKey().map(k -> k.location().getPath()).orElse("chat");
        JsonObject m = mail(kind.startsWith("msg_command") || kind.startsWith("team_msg") ? "whisper" : "chat", text, type.name().getString());
        m.addProperty("chat_type", kind);
        // A whisper to the bot goes to the GM: otherwise the feed hides private messages.
        if (kind.equals("msg_command_incoming") || kind.equals("team_msg_command_incoming")) {
            JsonObject d = new JsonObject();
            d.addProperty("bot", name);
            d.addProperty("from", type.name().getString());
            d.addProperty("text", text);
            bots.feed("bot_chat", d);
        }
    }

    private JsonObject mail(String kind, String text, String from) {
        JsonObject m = new JsonObject();
        m.addProperty("n", ++inboxSeq);
        m.addProperty("tick", bots.tickNow());
        m.addProperty("kind", kind);
        if (from != null) m.addProperty("from", from);
        m.addProperty("text", text);
        inbox.addLast(m);
        while (inbox.size() > INBOX) inbox.removeFirst();
        return m;
    }

    private void hear(String sound, String source, Vec3 pos, String entity, float volume) {
        heard.addLast(new Heard(bots.tickNow(), sound, source, pos, entity, volume));
        while (heard.size() > SOUNDS) heard.removeFirst();
    }

    /** Sounds from the last {@code ticks} ticks, newest first. */
    List<Heard> heard(int ticks) {
        long since = bots.tickNow() - ticks;
        List<Heard> out = new ArrayList<>();
        heard.descendingIterator().forEachRemaining(h -> {
            if (h.tick() >= since) out.add(h);
        });
        return out;
    }

    /** Chat message time: never earlier than the previous one (the clock may have moved backward). */
    Instant chatTime() {
        Instant now = Instant.now();
        if (now.isBefore(lastChat)) now = lastChat;
        lastChat = now;
        return now;
    }

    int takeChatAcks() {
        int n = chatAcks;
        chatAcks = 0;
        return n;
    }

    /** Summary for the bot list. */
    JsonObject brief() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("online", channel.isOpen());
        if (channel.isOpen()) {
            ServerPlayer p = player();
            o.addProperty("dimension", p.level().dimension().location().toString());
            o.add("pos", Observe.vec(p.position()));
            o.addProperty("health", Math.round(p.getHealth() * 10) / 10.0);
            if (p.isDeadOrDying()) o.addProperty("dead", true);
        }
        o.addProperty("marked", marked);
        JsonArray keys = new JsonArray();
        controls.held().forEach(k -> keys.add(k.id()));
        o.add("keys", keys);
        o.addProperty("programs_queued", programs.size() + (current != null ? 1 : 0));
        return o;
    }

    /** Details: body (like {@code player}), open menu, keys, programs and inbox after {@code after}. */
    public JsonObject describe(long after) {
        JsonObject o = Observe.details(player().server, player());
        o.addProperty("bot", true);
        o.addProperty("marked", marked);
        o.addProperty("auto_respawn", autoRespawn);
        JsonArray keys = new JsonArray();
        controls.held().forEach(k -> keys.add(k.id()));
        o.add("keys", keys);
        JsonObject leases = new JsonObject();
        controls.leases().forEach((k, left) -> leases.addProperty(k.id(), left));
        if (!leases.isEmpty()) o.add("keys_ticks_left", leases);
        if (teleportPending) o.addProperty("loading", "waiting for the chunks at its position to load; stands still until they are ready");
        JsonObject vehicle = vehicles.describe(player());
        if (vehicle != null) o.add("vehicle", vehicle);
        o.add("menu", menu(player().containerMenu));
        if (current != null) o.add("program", current.describe());
        if (last != null) o.add("last_program", last.describe());
        o.addProperty("programs_queued", programs.size());
        JsonArray mail = new JsonArray();
        for (JsonObject m : inbox) if (m.get("n").getAsLong() > after) mail.add(m.deepCopy());
        o.add("inbox", mail);
        o.addProperty("inbox_next", inboxSeq);
        return o;
    }

    /** Open menu: type, id, nonempty slots by index for the {@code menu} step, and item held by the mouse. */
    static JsonObject menu(AbstractContainerMenu menu) {
        JsonObject o = new JsonObject();
        o.addProperty("container_id", menu.containerId);
        MenuType<?> type = menu.containerId == 0 ? null : menuType(menu);
        o.addProperty("type", type == null ? "inventory" : String.valueOf(BuiltInRegistries.MENU.getKey(type)));
        o.addProperty("slots", menu.slots.size());
        JsonArray items = new JsonArray();
        for (Slot slot : menu.slots) {
            if (!slot.hasItem()) continue;
            JsonObject s = Steps.item(slot.getItem()).getAsJsonObject();
            s.addProperty("slot", slot.index);
            s.addProperty("of", slot.container == null ? "?" : slot.container.getClass().getSimpleName().toLowerCase(Locale.ROOT));
            items.add(s);
        }
        o.add("items", items);
        o.add("carried", Steps.item(menu.getCarried()));
        if (menu.containerId == 0) o.addProperty("layout", "0 crafting result, 1–4 crafting grid, 5–8 armor (head…feet), 9–35 main inventory, 36–44 hotbar, 45 offhand");
        return o;
    }

    private static MenuType<?> menuType(AbstractContainerMenu menu) {
        try {
            return menu.getType();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }
}
