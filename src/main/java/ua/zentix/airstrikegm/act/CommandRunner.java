package ua.zentix.airstrikegm.act;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.FunctionInstantiationException;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.InstantiatedFunction;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import ua.zentix.airstrikegm.AirstrikeGm;
import ua.zentix.airstrikegm.GmConfig;
import ua.zentix.airstrikegm.bridge.Args;
import ua.zentix.airstrikegm.bridge.RpcException;
import ua.zentix.airstrikegm.world.Dims;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Команды и функции от имени ведущего: источник с правами 4, без сущности, на точке появления мира (или
 * {@code dimension}/{@code pos}); вывод команд собирается и возвращается, в чат операторам не уходит. Каждая команда —
 * строкой INFO в лог сервера. Функция — строки {@code .mcfunction} без файла и без {@code /reload}: ванильный разбор
 * ({@code CommandFunction.fromLines}) и тот же порядок выполнения, что у {@code /function}, макросы {@code $(...)} —
 * параметром {@code args}.
 */
public final class CommandRunner {
    private static final Logger LOG = LogUtils.getLogger();
    static final int MAX_COMMANDS = 100;
    static final int MAX_LINES = 10_000;
    private static final TextColor RED = TextColor.fromLegacyFormat(ChatFormatting.RED);

    private CommandRunner() {}

    /** Вывод одной команды или функции. */
    private static final class Capture implements CommandSource {
        final List<String> output = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        Boolean success;
        int result;

        @Override
        public void sendSystemMessage(Component message) {
            // CommandSourceStack.sendFailure красит ошибку в красный корнем сообщения
            (RED.equals(message.getStyle().getColor()) ? errors : output).add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        CommandResultCallback callback() {
            return (ok, value) -> {
                success = ok;
                result = value;
            };
        }

        JsonObject describe(String key, String what) {
            JsonObject out = new JsonObject();
            out.addProperty(key, what);
            // функция без return итог не сообщает: успех — выполнилась без ошибок
            out.addProperty("ok", !Boolean.FALSE.equals(success) && errors.isEmpty());
            out.addProperty("result", result);
            if (!output.isEmpty()) out.add("output", strings(output));
            if (!errors.isEmpty()) out.add("errors", strings(errors));
            return out;
        }
    }

    /** {@code commands}: строка или список (с {@code /} или без); {@code dimension}, {@code pos} — откуда. */
    public static JsonElement commands(MinecraftServer server, Args args) throws RpcException {
        List<String> commands = args.strings("commands");
        if (commands.isEmpty() || commands.size() > MAX_COMMANDS) {
            throw RpcException.badRequest("commands: от 1 до " + MAX_COMMANDS + " команд");
        }
        ServerLevel level = Dims.level(server, args);
        Vec3 pos = origin(level, args);
        JsonArray results = new JsonArray();
        for (String command : commands) {
            Capture capture = new Capture();
            LOG.info("Ведущий: /{}", command.startsWith("/") ? command.substring(1) : command);
            server.getCommands().performPrefixedCommand(source(server, level, pos, capture).withCallback(capture.callback()), command);
            results.add(capture.describe("command", command));
        }
        return results;
    }

    /**
     * {@code lines}: строки функции; {@code args}: объект (числа без дробной части — целые) или строка SNBT для
     * макросов {@code $(...)}.
     */
    public static JsonElement function(MinecraftServer server, Args args) throws RpcException {
        List<String> lines = args.strings("lines");
        if (lines.size() > MAX_LINES) throw RpcException.badRequest("lines: не больше " + MAX_LINES + " строк");
        CompoundTag macro = args.has("args") ? macroArgs(args.raw().get("args")) : null;
        ServerLevel level = Dims.level(server, args);
        Vec3 pos = origin(level, args);
        Capture capture = new Capture();
        CommandSourceStack source = source(server, level, pos, capture);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(AirstrikeGm.ID, "inline");
        CommandFunction<CommandSourceStack> function;
        InstantiatedFunction<CommandSourceStack> instance;
        try {
            function = CommandFunction.fromLines(id, server.getCommands().getDispatcher(), source, lines);
            instance = function.instantiate(macro, server.getCommands().getDispatcher());
        } catch (IllegalArgumentException e) {
            throw RpcException.badRequest("Функция не разобрана: " + e.getMessage());
        } catch (FunctionInstantiationException e) {
            throw RpcException.badRequest("Макрос не подставлен: " + e.messageComponent().getString());
        }
        LOG.info("Ведущий: функция из {} строк", lines.size());
        Commands.executeCommandInContext(source,
                context -> ExecutionContext.queueInitialFunctionCall(context, instance, source, capture.callback()));
        return capture.describe("function", lines.size() + " строк");
    }

    private static CommandSourceStack source(MinecraftServer server, ServerLevel level, Vec3 pos, CommandSource capture) {
        String name = GmConfig.NAME.get();
        return new CommandSourceStack(capture, pos, Vec2.ZERO, level, 4, name, Component.literal(name), server, null);
    }

    private static Vec3 origin(ServerLevel level, Args args) throws RpcException {
        if (!args.has("pos")) return Vec3.atBottomCenterOf(level.getSharedSpawnPos());
        int[] p = args.ints("pos", 3);
        return new Vec3(p[0] + 0.5, p[1], p[2] + 0.5);
    }

    static CompoundTag macroArgs(JsonElement json) throws RpcException {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            try {
                return TagParser.parseTag(json.getAsString());
            } catch (CommandSyntaxException e) {
                throw RpcException.badRequest("args: " + e.getMessage());
            }
        }
        if (!json.isJsonObject()) throw RpcException.badRequest("args: ожидается объект или строка SNBT");
        return (CompoundTag) tag(json);
    }

    /** JSON → NBT для макросов: целые остаются целыми ({@code 5}, а не {@code 5.0} в подстановке). */
    private static Tag tag(JsonElement json) throws RpcException {
        if (json.isJsonObject()) {
            CompoundTag out = new CompoundTag();
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) out.put(e.getKey(), tag(e.getValue()));
            return out;
        }
        if (json.isJsonArray()) {
            ListTag out = new ListTag();
            for (JsonElement e : json.getAsJsonArray()) {
                if (!out.addTag(out.size(), tag(e))) throw RpcException.badRequest("args: в списке NBT все элементы одного типа");
            }
            return out;
        }
        if (json.isJsonPrimitive()) {
            JsonPrimitive p = json.getAsJsonPrimitive();
            if (p.isBoolean()) return ByteTag.valueOf(p.getAsBoolean());
            if (p.isString()) return StringTag.valueOf(p.getAsString());
            double d = p.getAsDouble();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                long l = (long) d;
                return l == (int) l ? IntTag.valueOf((int) l) : LongTag.valueOf(l);
            }
            return DoubleTag.valueOf(d);
        }
        throw RpcException.badRequest("args: null в NBT не бывает");
    }

    private static JsonArray strings(List<String> list) {
        JsonArray out = new JsonArray();
        list.forEach(out::add);
        return out;
    }
}
