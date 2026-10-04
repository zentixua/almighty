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
import java.util.UUID;

/**
 * Бот — игрок, чей клиент живёт в памяти сервера: соединение без сети ({@link BotSink} вместо сокета), вход через
 * {@code PlayerList.placeNewPlayer}, как у настоящего. Для всех остальных — обычный игрок (таб, чат, сущность,
 * инвентарь, смерть и возрождение, сохранение под своим UUID). Ведущий управляет им как клиент: клавиши и мышь
 * ({@link Controls}), программы шагов ({@link Program}), любой пакет клиента ({@link #send}). Что сервер шлёт клиенту,
 * бот читает: телепорт подтверждает, чат и заголовки — в «почту», звуки — в слух.
 * <p>
 * Ванильное соединение тикает {@code ServerConnectionListener}; наше в его списке нет, поэтому тик игрока
 * ({@code doTick}) и разбор отключения делает бот сам, в конце тика сервера. Слушатель пакетов не тикает: проверки
 * поддержания связи, простоя и «полёта» — для сети, а возврат на позицию из пакета движения отменил бы физику бота.
 * Ключ всего — соединение, не сущность: при возрождении сервер ставит в него нового {@code ServerPlayer}.
 */
public final class Bot {
    private static final Logger LOG = LogUtils.getLogger();
    static final int INBOX = 200;
    static final int SOUNDS = 64;
    /** Клиент подтверждает подписанные сообщения чата, когда их больше стольких (иначе сервер отключит на 4096). */
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
    /** Телепорт, который бот ещё не подтвердил: ждёт готового чанка места ({@link #acceptTeleport}). */
    private int teleportId;
    private boolean teleportPending;
    private Instant lastChat = Instant.EPOCH;
    private String leftReason;

    /** Звук, который пришёл боту пакетом. */
    record Heard(long tick, String sound, String source, Vec3 pos, String entity, float volume) {}

    Bot(Bots bots, GameProfile profile, boolean marked, boolean autoRespawn) {
        this.bots = bots;
        this.name = profile.getName();
        this.uuid = profile.getId();
        this.marked = marked;
        this.autoRespawn = autoRespawn;
        this.connection = new Connection(PacketFlow.SERVERBOUND);
        // канал регистрируется сразу: Connection получает channelActive, адрес «embedded»
        this.channel = new EmbeddedChannel(sink, connection);
        // как в GameTest NeoForge: оба конца — NeoForge со всеми каналами модов, иначе пакеты модов бот не примет
        NetworkRegistry.configureMockConnection(connection);
    }

    Connection connection() {
        return connection;
    }

    public String name() {
        return name;
    }

    /** Сущность бота сейчас: после возрождения — новая. */
    public ServerPlayer player() {
        return ((ServerGamePacketListenerImpl) connection.getPacketListener()).player;
    }

    public boolean online() {
        return channel.isOpen();
    }

    /**
     * Пакет от «клиента» бота — серверу, тем же путём, что пакет из сети ({@code Connection.channelRead0} →
     * обработчик), в этом же потоке. Пакет только клиента к серверу; ошибка обработчика отключает бота, как клиента.
     */
    public void send(Packet<?> packet) {
        if (packet.type().flow() != PacketFlow.SERVERBOUND) throw new IllegalArgumentException("боту — только пакеты клиента к серверу (Serverbound…), не " + packet.type());
        if (!channel.isOpen()) return;
        channel.writeInbound(packet);
    }

    /** Программа шагов из значения скрипта (список карт, как {@code actions} у {@code bot.act}); номер программы. */
    public long act(Object steps) throws RpcException {
        if (!(Json.from(steps) instanceof com.google.gson.JsonArray array)) throw RpcException.badRequest("act: список шагов");
        return act(Steps.parse(array), false).id;
    }

    Program act(List<Steps.Step> steps, boolean replace) {
        if (replace) cancelAll("заменена новой программой");
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

    /** Тик бота в конце тика сервера; false — бот ушёл (выгнали, ошибка, остановка). */
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
            controls.begin(player());
            runPrograms();
            if (channel.isOpen()) controls.end(player());
            drain();
        } catch (RuntimeException e) {
            // как сервер с клиентом, чей пакет упал в обработчике: игрок отключается, сервер живёт
            LOG.warn("Бот {}: ошибка в тике, отключаю", name, e);
            disconnect(Component.literal("Internal server error"));
        }
        return channel.isOpen();
    }

    /**
     * Счётчики спама обработчика (чат и команды, выброс в творческом): ваниль снимает их по единице в тике
     * обработчика, которого у бота нет, — иначе 11-е сообщение за всё время отключало бы бота за спам. Открытого API нет —
     * рефлексия; не вышло — предупреждение в лог, бот живёт как есть.
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
                LOG.warn("Боты: счётчики спама обработчика недоступны ({}): частый чат бота отключит его за спам", e.toString());
            }
        }
    }

    private void runPrograms() {
        for (int i = 0; i < 8 && channel.isOpen(); i++) {
            if (current == null) {
                current = programs.poll();
                if (current == null) return;
            }
            if (!current.tick(this)) return;
            finished(current);
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

    /** Отключить бота так, как сервер отключает игрока: выход в чат и ленту, сохранение, снятие из списка. */
    void disconnect(Component reason) {
        if (channel.isOpen()) {
            leftReason = reason.getString();
            connection.disconnect(reason);
        }
        closed();
    }

    /** Канал закрыт: если сервер ещё не разобрал отключение (выгнал сам — разобрал), разобрать. */
    void closed() {
        if (leftReason == null && connection.getDisconnectionDetails() != null) leftReason = connection.getDisconnectionDetails().reason().getString();
        cancelAll("бот ушёл с сервера");
        if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener && !listener.player.hasDisconnected()) {
            connection.handleDisconnection();
        }
    }

    String leftReason() {
        return leftReason;
    }

    /** Сразу после входа или телепорта: подтвердить телепорт, не дожидаясь тика. */
    void settle() {
        drain();
    }

    /** Всё, что сервер успел написать «клиенту». */
    private void drain() {
        for (Packet<?> p; (p = sink.poll()) != null; ) receive(p);
    }

    private void receive(Packet<?> packet) {
        switch (packet) {
            case BundlePacket<?> bundle -> bundle.subPackets().forEach(this::receive);
            case ClientboundPlayerPositionPacket pos -> {
                // сервер ждёт только последний номер
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
            // перенастройку соединения бот не проходит: молча он бы завис, а сервер ждал бы ответа
            case ClientboundStartConfigurationPacket ignored -> connection.disconnect(Component.literal("бот не проходит перенастройку соединения"));
            default -> {}
        }
    }

    /**
     * Подтвердить телепорт, когда чанк места готов: подтверждение переносит игрока ({@code absMoveTo}), а перенос в
     * NeoForge грузит чанк назначения сразу. Так бывает со входом бота на сохранённое место вдали (телепорт входа
     * подтверждается после добавления в мир) — клиент на загрузке мира тоже отвечает позже. Чанк грузит тикет игрока.
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
        // шёпот боту — ведущему: лента иначе личных сообщений не показывает
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

    /** Звуки за последние {@code ticks} тиков, новые первыми. */
    List<Heard> heard(int ticks) {
        long since = bots.tickNow() - ticks;
        List<Heard> out = new ArrayList<>();
        heard.descendingIterator().forEachRemaining(h -> {
            if (h.tick() >= since) out.add(h);
        });
        return out;
    }

    /** Время сообщения чата: не раньше прошлого (часы могли отойти назад). */
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

    /** Коротко: для списка ботов. */
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

    /** Подробно: тело (как {@code player}), открытое меню, клавиши, программы, почта после {@code after}. */
    public JsonObject describe(long after) {
        JsonObject o = Observe.details(player().server, player());
        o.addProperty("bot", true);
        o.addProperty("marked", marked);
        o.addProperty("auto_respawn", autoRespawn);
        JsonArray keys = new JsonArray();
        controls.held().forEach(k -> keys.add(k.id()));
        o.add("keys", keys);
        if (teleportPending) o.addProperty("loading", "ждёт загрузки чанков места: стоит, пока они не готовы");
        JsonObject vehicle = Vehicles.describe(player());
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

    /** Открытое меню: тип, номер, непустые ячейки по номерам для шага {@code menu}, предмет «в руке» мыши. */
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
        if (menu.containerId == 0) o.addProperty("layout", "0 — результат крафта, 1–4 — сетка, 5–8 — броня (голова…ноги), 9–35 — рюкзак, 36–44 — хотбар, 45 — вторая рука");
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
