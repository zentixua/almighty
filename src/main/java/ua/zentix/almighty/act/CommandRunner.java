package ua.zentix.almighty.act;

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.world.Dims;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Commands and functions as the game master: a source with permission level 4, no entity, at world spawn
 * (or {@code dimension}/{@code pos}); command output is collected and returned, never sent to operators' chat.
 * Each command is logged at INFO. A function is {@code .mcfunction} lines without a file or {@code /reload}:
 * vanilla parsing ({@code CommandFunction.fromLines}) and the same execution order as {@code /function};
 * {@code $(...)} macros use the {@code args} parameter.
 */
public final class CommandRunner {
    private static final Logger LOG = LogUtils.getLogger();
    /**
     * Ongoing command execution on this thread ({@code Commands.CURRENT_EXECUTION_CONTEXT}): a command submitted
     * inside it (a rule on an event triggered by a command or function) would only enter its queue, without a result
     * and run after the rule exits. The game master runs its commands immediately in a separate execution.
     */
    private static final ThreadLocal<Object> CURRENT_EXECUTION = currentExecution();
    static final int MAX_COMMANDS = 100;
    static final int MAX_LINES = 10_000;
    private static final TextColor RED = TextColor.fromLegacyFormat(ChatFormatting.RED);

    private CommandRunner() {}

    /** Output of one command or function. */
    private static final class Capture implements CommandSource {
        final List<String> output = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        Boolean success;
        int result;

        @Override
        public void sendSystemMessage(Component message) {
            // CommandSourceStack.sendFailure colors the error red at the message root.
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
            // A function without return reports no result: success means execution without errors.
            out.addProperty("ok", !Boolean.FALSE.equals(success) && errors.isEmpty());
            out.addProperty("result", result);
            if (!output.isEmpty()) out.add("output", strings(output));
            if (!errors.isEmpty()) out.add("errors", strings(errors));
            return out;
        }
    }

    /** {@code commands}: a string or list (with or without {@code /}); {@code dimension}, {@code pos}: the source location. */
    public static JsonElement commands(MinecraftServer server, Args args) throws RpcException {
        List<String> commands = args.strings("commands");
        if (commands.isEmpty() || commands.size() > MAX_COMMANDS) {
            throw RpcException.badRequest("commands: 1 to " + MAX_COMMANDS + " commands");
        }
        ServerLevel level = Dims.level(server, args);
        return run(server, level, origin(level, args), null, commands, false);
    }

    /**
     * Commands in order, with output for each. The source is the game master at {@code level}/{@code pos}, or, if
     * {@code as} is set, the entity: {@code @s}, its position, rotation and dimension (permission level remains 4).
     * {@code quiet} logs at DEBUG instead of INFO: a rule may call a command every tick.
     */
    public static JsonArray run(MinecraftServer server, ServerLevel level, Vec3 pos, Entity as, List<String> commands, boolean quiet) {
        JsonArray results = new JsonArray();
        for (String command : commands) {
            Capture capture = new Capture();
            String text = command.startsWith("/") ? command.substring(1) : command;
            if (quiet) LOG.debug("GM: /{}", text);
            else LOG.info("GM: /{}", text);
            CommandSourceStack source = source(server, level, pos, capture);
            if (as != null && as.level() instanceof ServerLevel own) {
                source = source.withEntity(as).withLevel(own).withPosition(as.position()).withRotation(as.getRotationVector());
            }
            CommandSourceStack from = source.withCallback(capture.callback());
            outsideCurrentExecution(() -> server.getCommands().performPrefixedCommand(from, command));
            results.add(capture.describe("command", command));
        }
        return results;
    }

    /**
     * {@code lines}: function lines; {@code args}: an object (numbers without a fractional part are integers)
     * or an SNBT string for {@code $(...)} macros.
     */
    public static JsonElement function(MinecraftServer server, Args args) throws RpcException {
        List<String> lines = args.strings("lines");
        if (lines.size() > MAX_LINES) throw RpcException.badRequest("lines: at most " + MAX_LINES + " lines");
        CompoundTag macro = args.has("args") ? macroArgs(args.raw().get("args")) : null;
        ServerLevel level = Dims.level(server, args);
        Vec3 pos = origin(level, args);
        Capture capture = new Capture();
        CommandSourceStack source = source(server, level, pos, capture);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Almighty.ID, "inline");
        CommandFunction<CommandSourceStack> function;
        InstantiatedFunction<CommandSourceStack> instance;
        try {
            function = CommandFunction.fromLines(id, server.getCommands().getDispatcher(), source, lines);
            instance = function.instantiate(macro, server.getCommands().getDispatcher());
        } catch (IllegalArgumentException e) {
            throw RpcException.badRequest("Function failed to parse: " + e.getMessage());
        } catch (FunctionInstantiationException e) {
            throw RpcException.badRequest("Macro substitution failed: " + e.messageComponent().getString());
        }
        LOG.info("GM: function of {} lines", lines.size());
        outsideCurrentExecution(() -> Commands.executeCommandInContext(source,
                context -> ExecutionContext.queueInitialFunctionCall(context, instance, source, capture.callback())));
        return capture.describe("function", lines.size() + " lines");
    }

    private static void outsideCurrentExecution(Runnable run) {
        Object outer = CURRENT_EXECUTION == null ? null : CURRENT_EXECUTION.get();
        if (outer == null) {
            run.run();
            return;
        }
        CURRENT_EXECUTION.remove();
        try {
            run.run();
        } finally {
            CURRENT_EXECUTION.set(outer);
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> currentExecution() {
        try {
            return (ThreadLocal<Object>) ObfuscationReflectionHelper.findField(Commands.class, "CURRENT_EXECUTION_CONTEXT").get(null);
        } catch (RuntimeException | IllegalAccessException e) {
            LOG.error("GM: Commands.CURRENT_EXECUTION_CONTEXT not found; commands of a rule that runs inside another command will join that command's queue", e);
            return null;
        }
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
        if (!json.isJsonObject()) throw RpcException.badRequest("args: expected an object or an SNBT string");
        return (CompoundTag) tag(json);
    }

    /** JSON to NBT for macros: integers remain integers ({@code 5}, rather than {@code 5.0} in substitutions). */
    private static Tag tag(JsonElement json) throws RpcException {
        if (json.isJsonObject()) {
            CompoundTag out = new CompoundTag();
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) out.put(e.getKey(), tag(e.getValue()));
            return out;
        }
        if (json.isJsonArray()) {
            ListTag out = new ListTag();
            for (JsonElement e : json.getAsJsonArray()) {
                if (!out.addTag(out.size(), tag(e))) throw RpcException.badRequest("args: all elements of an NBT list must have the same type");
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
        throw RpcException.badRequest("args: NBT has no null");
    }

    private static JsonArray strings(List<String> list) {
        JsonArray out = new JsonArray();
        list.forEach(out::add);
        return out;
    }
}
