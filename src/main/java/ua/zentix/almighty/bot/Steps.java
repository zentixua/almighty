package ua.zentix.almighty.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.network.connection.ConnectionType;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.world.Observe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Шаги программы бота: то, что делает игрок клавиатурой и мышью (держать и отпускать клавиши, щёлкать, смотреть,
 * хотбар, меню, чат, табличка, возрождение, полёт), ожидание в тиках и дойти до точки. Разбор — в потоке моста
 * (только проверка), выполнение — в потоке сервера, по тикам бота.
 */
final class Steps {
    static final int MAX_STEPS = 256;
    static final int MAX_WAIT = 20 * 60 * 10;

    private Steps() {}

    /** Шаг: тик за тиком, пока не кончится. Ошибка — {@link Failed}: программа на ней останавливается. */
    interface Step {
        /** Один тик; true — шаг кончился, итог — в {@code out}. */
        boolean tick(Bot bot, ServerPlayer p, JsonObject out);

        /** Программу сняли посреди шага: отпустить то, что шаг держит сам. */
        default void cancel(Bot bot, ServerPlayer p) {}
    }

    static final class Failed extends RuntimeException {
        Failed(String message) {
            super(message);
        }
    }

    static List<Step> parse(JsonArray array) throws RpcException {
        if (array.isEmpty()) throw RpcException.badRequest("actions: пустой список");
        if (array.size() > MAX_STEPS) throw RpcException.badRequest("actions: не больше " + MAX_STEPS + " шагов");
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement e = array.get(i);
            if (!e.isJsonObject()) throw RpcException.badRequest("actions[" + i + "]: ожидается объект, например {\"hold\": \"forward\"}");
            try {
                out.add(step(new Args(e.getAsJsonObject())));
            } catch (RpcException x) {
                throw RpcException.badRequest("actions[" + i + "]: " + x.getMessage());
            }
        }
        return out;
    }

    private static Step step(Args a) throws RpcException {
        if (a.has("wait")) return new Wait(a.integer("wait", 0, MAX_WAIT));
        if (a.has("hold")) return new Hold(keys(a, "hold"), a.integer("ticks", 0, 1, MAX_WAIT));
        if (a.has("release")) {
            boolean all = a.raw().get("release").isJsonPrimitive() && a.string("release").equals("all");
            return new Release(all ? EnumSet.allOf(Controls.Key.class) : keys(a, "release"));
        }
        if (a.has("look")) {
            double[] v = numbers(a, "look", 2);
            return new Look(v[0], v[1], false);
        }
        if (a.has("turn")) {
            double[] v = numbers(a, "turn", 2);
            return new Look(v[0], v[1], true);
        }
        if (a.has("look_at")) return new LookAt(Target.parse(a, "look_at"));
        if (a.has("click")) {
            String button = a.string("click");
            if (!button.equals("attack") && !button.equals("use")) throw RpcException.badRequest("click: attack или use");
            return new Click(button.equals("attack"), a.has("at") ? Target.parse(a, "at") : null);
        }
        if (a.has("slot")) return packet("slot", new ServerboundSetCarriedItemPacket(a.integer("slot", 0, 8)));
        if (a.has("drop")) {
            String what = a.string("drop");
            if (!what.equals("one") && !what.equals("stack")) throw RpcException.badRequest("drop: one или stack");
            return packet("drop", new ServerboundPlayerActionPacket(what.equals("one")
                    ? ServerboundPlayerActionPacket.Action.DROP_ITEM : ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS, BlockPos.ZERO, Direction.DOWN));
        }
        if (a.has("swap_hands")) return packet("swap_hands", new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        if (a.has("menu")) {
            ClickType mode;
            try {
                mode = ClickType.valueOf(a.string("mode", "pickup").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw RpcException.badRequest("mode: pickup, quick_move, swap, clone, throw, quick_craft или pickup_all");
            }
            return new MenuClick(a.integer("menu", -999, 1023), a.integer("button", 0, 0, 40), mode);
        }
        if (a.has("menu_button")) return new MenuButton(a.integer("menu_button", 0, 255));
        if (a.has("close")) return new Close();
        if (a.has("chat")) return new Chat(text(a.string("chat"), 256, "chat"));
        if (a.has("sign")) {
            int[] pos = a.ints("sign", 3);
            List<String> lines = a.has("lines") ? a.strings("lines") : List.of();
            if (lines.size() > 4) throw RpcException.badRequest("lines: не больше 4 строк");
            String[] four = {"", "", "", ""};
            for (int i = 0; i < lines.size(); i++) four[i] = text(lines.get(i), 384, "lines");
            return packet("sign", new ServerboundSignUpdatePacket(new BlockPos(pos[0], pos[1], pos[2]), a.bool("front", true), four[0], four[1], four[2], four[3]));
        }
        if (a.has("respawn")) return new Respawn();
        if (a.has("press")) return new Press(a.string("press"));
        if (a.has("payload")) return Payload.parse(a);
        if (a.has("fly")) {
            Abilities abilities = new Abilities();
            abilities.flying = a.bool("fly", true);
            return packet("fly", new ServerboundPlayerAbilitiesPacket(abilities));
        }
        if (a.has("player_command")) {
            ServerboundPlayerCommandPacket.Action action;
            try {
                action = ServerboundPlayerCommandPacket.Action.valueOf(a.string("player_command").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw RpcException.badRequest("player_command: start_fall_flying, stop_sleeping, start_riding_jump, stop_riding_jump, open_inventory, start_sprinting, stop_sprinting, press_shift_key или release_shift_key");
            }
            return new PlayerCommand(action, a.integer("data", 0, 0, 100));
        }
        if (a.has("walk_to")) {
            JsonArray to = a.array("walk_to");
            if (to.size() != 2 && to.size() != 3) throw RpcException.badRequest("walk_to: [x, z] или [x, y, z]");
            double[] v = numbers(a, "walk_to", to.size());
            return new WalkTo(v[0], v.length == 3 ? v[2] : v[1], a.bool("sprint", false), a.number("within", 0.6, 0.1, 8), a.integer("max_ticks", 1200, 1, MAX_WAIT));
        }
        throw RpcException.badRequest("неизвестный шаг " + a.raw().keySet() + ": wait, hold, release, look, turn, look_at, click, slot, drop, swap_hands, menu, menu_button, close, chat, sign, respawn, press, payload, fly, player_command, walk_to");
    }

    private static Set<Controls.Key> keys(Args a, String name) throws RpcException {
        Set<Controls.Key> out = EnumSet.noneOf(Controls.Key.class);
        for (String s : a.strings(name)) {
            Controls.Key key = Controls.Key.parse(s);
            if (key == null) throw RpcException.badRequest(name + ": клавиши forward, back, left, right, jump, sneak, sprint, attack, use");
            out.add(key);
        }
        return out;
    }

    static double[] numbers(Args a, String name, int count) throws RpcException {
        JsonArray arr = a.array(name);
        if (arr.size() != count) throw RpcException.badRequest(name + ": ожидается " + count + " числа");
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            JsonElement v = arr.get(i);
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw RpcException.badRequest(name + ": ожидаются числа");
            out[i] = v.getAsDouble();
            if (!Double.isFinite(out[i]) || Math.abs(out[i]) > 3e7) throw RpcException.badRequest(name + ": число вне мира");
        }
        return out;
    }

    /** Текст, который сервер примет от клиента: без § и управляющих символов (иначе он отключает игрока). */
    private static String text(String s, int max, String name) throws RpcException {
        if (s.length() > max) throw RpcException.badRequest(name + ": не длиннее " + max + " символов");
        for (int i = 0; i < s.length(); i++) {
            if (!StringUtil.isAllowedChatCharacter(s.charAt(i))) throw RpcException.badRequest(name + ": символ «§» и управляющие символы сервер не принимает");
        }
        return s;
    }

    /** Повернуть голову к точке (yaw — по часовой, если смотреть сверху; pitch — вниз положительный). */
    static void lookAt(ServerPlayer p, Vec3 at) {
        Vec3 d = at.subtract(p.getEyePosition());
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG);
        rotate(p, yaw, pitch);
    }

    static void rotate(ServerPlayer p, float yaw, float pitch) {
        p.absRotateTo(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0F, 90.0F));
        p.setYHeadRot(p.getYRot());
    }

    /** Куда смотреть: точка ([x, y, z]; целые — центр блока) или сущность (UUID или имя игрока). */
    record Target(Vec3 point, String entity) {
        static Target parse(Args a, String name) throws RpcException {
            JsonElement e = a.raw().get(name);
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return new Target(null, e.getAsString());
            double[] v = numbers(a, name, 3);
            boolean block = v[0] == Math.rint(v[0]) && v[1] == Math.rint(v[1]) && v[2] == Math.rint(v[2]);
            return new Target(block ? new Vec3(v[0] + 0.5, v[1] + 0.5, v[2] + 0.5) : new Vec3(v[0], v[1], v[2]), null);
        }

        Vec3 resolve(ServerPlayer p) {
            if (point != null) return point;
            Entity target = null;
            try {
                target = p.serverLevel().getEntity(UUID.fromString(entity));
            } catch (IllegalArgumentException notUuid) {
                ServerPlayer named = p.server.getPlayerList().getPlayerByName(entity);
                if (named != null && named.level() == p.level()) target = named;
            }
            if (target == null) throw new Failed("нет сущности «" + entity + "» в измерении бота");
            return target.getBoundingBox().getCenter();
        }
    }

    private static Step packet(String kind, net.minecraft.network.protocol.Packet<?> packet) {
        return (bot, p, out) -> {
            out.addProperty("do", kind);
            bot.send(packet);
            return true;
        };
    }

    private static final class Wait implements Step {
        private final int ticks;
        private int left = -1;

        Wait(int ticks) {
            this.ticks = ticks;
        }

        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            if (left < 0) {
                left = ticks;
                if (left > 0) return false;
            } else if (--left > 0) {
                return false;
            }
            out.addProperty("do", "wait");
            out.addProperty("ticks", ticks);
            return true;
        }
    }

    /** Держать клавиши: без срока — пока не отпустят, с {@code ticks} — столько тиков (каждый новый шаг продлевает). */
    private record Hold(Set<Controls.Key> keys, int ticks) implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "hold");
            if (ticks > 0) out.addProperty("ticks", ticks);
            for (Controls.Key key : keys) bot.controls.hold(p, key, ticks, out);
            return true;
        }
    }

    /** Клавиша транспорта мода по имени в настройках управления (высадка и ускоритель самолёта IA). */
    private record Press(String key) implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "press");
            out.addProperty("key", key);
            out.addProperty("result", bot.vehicles.press(p, key));
            return true;
        }
    }

    /**
     * Пакет мода от клиента — то, что клиент мода шлёт со своим вводом (рули, кнопки, меню мода): id пакета и его байты
     * после id (base64), как они идут по сети. Разбор — кодеком пакета (как у сетевого слоя: реестры сервера,
     * соединение NeoForge), обработка — обычным обработчиком мода, игрок — бот. Бот шлёт только то, что мог бы послать
     * клиент: пакет, который мод принимает от клиента, не больше предела ванили. Байты — по исходникам мода или его
     * памятке.
     */
    private record Payload(ResourceLocation id, byte[] data) implements Step {
        /** Предел пакета от клиента у ванили ({@code ServerboundCustomPayloadPacket}). */
        private static final int MAX_BYTES = 32767;

        static Payload parse(Args a) throws RpcException {
            ResourceLocation id = ResourceLocation.tryParse(a.string("payload"));
            if (id == null) throw RpcException.badRequest("payload: id пакета вида namespace:path");
            byte[] data;
            try {
                data = Base64.getDecoder().decode(a.string("data", ""));
            } catch (IllegalArgumentException e) {
                throw RpcException.badRequest("data: байты пакета в base64");
            }
            if (data.length > MAX_BYTES) throw RpcException.badRequest("data: не больше " + MAX_BYTES + " байт");
            return new Payload(id, data);
        }

        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "payload");
            out.addProperty("id", id.toString());
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(data.length + 64), p.server.registryAccess(), ConnectionType.NEOFORGE);
            ServerboundCustomPayloadPacket packet;
            int left;
            try {
                buf.writeResourceLocation(id);
                buf.writeBytes(data);
                packet = ServerboundCustomPayloadPacket.STREAM_CODEC.decode(buf);
                left = buf.readableBytes();
            } catch (Exception | StackOverflowError | LinkageError e) {
                // у клиента это ловит netty и отключает его; в тике сервера ошибка кодека уронила бы сервер
                throw new Failed("пакет " + id + " не разобран: " + e);
            } finally {
                buf.release();
            }
            if (packet.payload() instanceof DiscardedPayload) throw new Failed("у модов сервера нет пакета " + id + " от клиента");
            // сетевой слой отключил бы клиента за пакет длиннее, чем прочёл кодек
            if (left > 0) throw new Failed("пакет " + id + ": лишние байты после разбора — " + left);
            bot.send(packet);
            out.addProperty("bytes", data.length);
            return true;
        }
    }

    private record Release(Set<Controls.Key> keys) implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "release");
            for (Controls.Key key : keys) bot.controls.release(p, key);
            int broken = bot.controls.takeBroken();
            if (broken > 0) out.addProperty("blocks_broken", broken);
            return true;
        }
    }

    private record Look(double yaw, double pitch, boolean relative) implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", relative ? "turn" : "look");
            rotate(p, (float) (relative ? p.getYRot() + yaw : yaw), (float) (relative ? p.getXRot() + pitch : pitch));
            return true;
        }
    }

    private record LookAt(Target target) implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "look_at");
            lookAt(p, target.resolve(p));
            out.addProperty("yaw", Math.round(p.getYRot() * 10) / 10.0);
            out.addProperty("pitch", Math.round(p.getXRot() * 10) / 10.0);
            return true;
        }
    }

    private record Click(boolean attack, Target at) implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", attack ? "click_attack" : "click_use");
            if (p.isDeadOrDying()) throw new Failed("бот мёртв: сперва respawn");
            if (at != null) lookAt(p, at.resolve(p));
            JsonObject r = attack ? bot.controls.attack(p) : bot.controls.use(p);
            for (String k : r.keySet()) out.add(k, r.get(k));
            return true;
        }
    }

    private record MenuClick(int slot, int button, ClickType mode) implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "menu");
            AbstractContainerMenu menu = p.containerMenu;
            if (slot != -999 && (slot < 0 || slot >= menu.slots.size())) {
                throw new Failed("в меню " + menu.slots.size() + " ячеек (0.." + (menu.slots.size() - 1) + ", -999 — мимо окна)");
            }
            bot.send(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), slot, button, mode, ItemStack.EMPTY, new Int2ObjectOpenHashMap<>()));
            if (slot >= 0) out.add("slot", item(p.containerMenu.getSlot(slot).getItem()));
            out.add("carried", item(p.containerMenu.getCarried()));
            return true;
        }
    }

    private record MenuButton(int id) implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "menu_button");
            bot.send(new ServerboundContainerButtonClickPacket(p.containerMenu.containerId, id));
            return true;
        }
    }

    private static final class Close implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "close");
            bot.send(new ServerboundContainerClosePacket(p.containerMenu.containerId));
            return true;
        }
    }

    /**
     * Чат и команды — пакетами, как у клиента. Подписать сообщение бот не может (ключа Mojang у него нет): сервер с
     * {@code enforce-secure-profile} отказал бы ему в чате и в командах с подписываемым текстом ({@code /msg}, {@code /me}),
     * а клиенты такого сервера неподписанное сообщение игрока прячут. Тогда — как текст командного блока: событие чата
     * NeoForge и рассылка «маскированным» сообщением, команда — от источника бота без подписи.
     */
    private record Chat(String text) implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "chat");
            MinecraftServer server = p.server;
            boolean unsigned = server.enforceSecureProfile() && p.getChatSession() == null;
            if (text.startsWith("/")) {
                String command = text.substring(1);
                CommandSourceStack source = p.createCommandSourceStack();
                if (unsigned && SignableCommand.hasSignableArguments(server.getCommands().getDispatcher().parse(command, source))) {
                    out.addProperty("unsigned", true);
                    server.getCommands().performPrefixedCommand(source, command);
                } else {
                    bot.send(new ServerboundChatCommandPacket(command));
                }
            } else if (unsigned) {
                out.addProperty("unsigned", true);
                Component decorated = CommonHooks.getServerChatSubmittedDecorator().decorate(p, Component.literal(text));
                if (decorated != null) server.getPlayerList().broadcastChatMessage(PlayerChatMessage.system(text).withUnsignedContent(decorated), p, ChatType.bind(ChatType.CHAT, p));
            } else {
                bot.send(new ServerboundChatPacket(text, bot.chatTime(), 0L, null, new LastSeenMessages.Update(bot.takeChatAcks(), new BitSet())));
            }
            return true;
        }
    }

    private static final class Respawn implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "respawn");
            if (!p.isDeadOrDying()) {
                out.addProperty("ignored", "бот жив");
                return true;
            }
            bot.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            out.addProperty("respawned", bot.player() != p);
            return true;
        }
    }

    private record PlayerCommand(ServerboundPlayerCommandPacket.Action action, int data) implements Step {
        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            out.addProperty("do", "player_command");
            bot.send(new ServerboundPlayerCommandPacket(p, action, data));
            return true;
        }
    }

    /**
     * Дойти до точки по прямой: голова к цели, вперёд (и бег), прыжок, когда упёрся в блок на земле, в воде — вверх.
     * Обходить препятствия бот не умеет: путь выбирает ведущий. Не ближе на 0,1 блока за 60 тиков — застрял.
     */
    private static final class WalkTo implements Step {
        private final double x, z, within;
        private final boolean sprint;
        private final int maxTicks;
        private final Set<Controls.Key> pressed = EnumSet.noneOf(Controls.Key.class);
        private int ticks, sinceBetter;
        private double best = Double.MAX_VALUE;

        WalkTo(double x, double z, boolean sprint, double within, int maxTicks) {
            this.x = x;
            this.z = z;
            this.sprint = sprint;
            this.within = within;
            this.maxTicks = maxTicks;
        }

        @Override
        public boolean tick(Bot bot, ServerPlayer p, JsonObject out) {
            double dx = x - p.getX(), dz = z - p.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            String reason = null;
            if (dist <= within) reason = "";
            else if (p.isDeadOrDying()) reason = "бот умер";
            else if (ticks >= maxTicks) reason = "не дошёл за " + maxTicks + " тиков";
            else if (sinceBetter >= 60) reason = "застрял";
            if (reason != null) {
                cancel(bot, p);
                out.addProperty("do", "walk_to");
                out.addProperty("reached", reason.isEmpty());
                if (!reason.isEmpty()) out.addProperty("reason", reason);
                out.addProperty("distance_left", Math.round(dist * 10) / 10.0);
                out.add("pos", Observe.vec(p.position()));
                out.addProperty("ticks", ticks);
                return true;
            }
            ticks++;
            if (dist < best - 0.1) {
                best = dist;
                sinceBetter = 0;
            } else sinceBetter++;
            Steps.rotate(p, (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F, p.getXRot());
            press(bot, p, Controls.Key.FORWARD);
            if (sprint) press(bot, p, Controls.Key.SPRINT);
            boolean jump = p.isInWater() || p.isInLava() || (p.horizontalCollision && p.onGround());
            if (jump) press(bot, p, Controls.Key.JUMP);
            else if (pressed.remove(Controls.Key.JUMP)) bot.controls.release(p, Controls.Key.JUMP);
            return false;
        }

        private void press(Bot bot, ServerPlayer p, Controls.Key key) {
            if (bot.controls.held().contains(key)) return;
            pressed.add(key);
            bot.controls.press(p, key, new JsonObject());
        }

        @Override
        public void cancel(Bot bot, ServerPlayer p) {
            for (Controls.Key key : pressed) bot.controls.release(p, key);
            pressed.clear();
        }
    }

    /** Предмет для итога шага: id и число; пусто — null. */
    static JsonElement item(ItemStack stack) {
        if (stack.isEmpty()) return com.google.gson.JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        o.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        o.addProperty("count", stack.getCount());
        return o;
    }
}
