package ua.zentix.almighty.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Agent;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Bridge;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Owner command {@code /almighty} (permission level 4: the console and level-4 operators): agents and their tokens.
 * Tokens are never printed: a command names the file that holds one. Also the red button ({@code pause},
 * {@code resume}) and the audit ({@code log}). Later steps add {@code role}, {@code confirm}, {@code undo}.
 */
public final class AlmightyCommand {
    private AlmightyCommand() {}

    public static void register(IEventBus bus) {
        bus.addListener(AlmightyCommand::onRegister);
    }

    private static final SuggestionProvider<CommandSourceStack> AGENTS = (c, b) -> {
        Bridge bridge = bridge(c.getSource());
        return SharedSuggestionProvider.suggest(bridge == null ? List.of() : bridge.agents().names(), b);
    };

    private static void onRegister(RegisterCommandsEvent e) {
        // Owner subcommands need permission level 4 (the console, level-4 operators); whoami is for everyone.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("almighty");
        root.then(Commands.literal("whoami").executes(c -> whoami(c.getSource())));
        root.then(owner("agents").executes(c -> agents(c.getSource())));
        root.then(owner("agent")
                .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> change(c, "add"))))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).suggests(AGENTS)
                        .executes(c -> change(c, "remove")))));
        root.then(owner("revoke").then(Commands.argument("name", StringArgumentType.word()).suggests(AGENTS)
                .executes(c -> change(c, "revoke"))));
        root.then(owner("reload").executes(c -> reload(c.getSource())));
        root.then(owner("pause").executes(c -> pause(c.getSource(), null, true))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(AGENTS)
                        .executes(c -> pause(c.getSource(), StringArgumentType.getString(c, "name"), true))));
        root.then(owner("resume").executes(c -> pause(c.getSource(), null, false))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(AGENTS)
                        .executes(c -> pause(c.getSource(), StringArgumentType.getString(c, "name"), false))));
        root.then(owner("log").executes(c -> log(c.getSource(), null, 10))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                        .executes(c -> log(c.getSource(), null, IntegerArgumentType.getInteger(c, "count"))))
                .then(Commands.argument("who", StringArgumentType.word()).suggests(AGENTS)
                        .executes(c -> log(c.getSource(), StringArgumentType.getString(c, "who"), 10))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(c -> log(c.getSource(), StringArgumentType.getString(c, "who"),
                                        IntegerArgumentType.getInteger(c, "count"))))));
        e.getDispatcher().register(root);
    }

    /** An owner subcommand: permission level 4. */
    private static LiteralArgumentBuilder<CommandSourceStack> owner(String name) {
        return Commands.literal(name).requires(s -> s.hasPermission(4));
    }

    private static Bridge bridge(CommandSourceStack source) {
        GmServer gm = GmServer.of(source.getServer());
        return gm == null ? null : gm.bridge();
    }

    private static Bridge need(CommandSourceStack source) {
        Bridge bridge = bridge(source);
        if (bridge == null) source.sendFailure(Component.literal("The Almighty bridge is not running on this server"));
        return bridge;
    }

    private static int agents(CommandSourceStack source) {
        Bridge bridge = need(source);
        if (bridge == null) return 0;
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        for (Agent a : bridge.agents().list()) {
            String seen = a.lastSeenMillis() == 0 ? "not seen since start"
                    : "last call " + ago(Duration.ofMillis(now - a.lastSeenMillis())) + " ago from " + a.lastAddress();
            lines.add(a.name() + ": " + seen);
        }
        source.sendSuccess(() -> Component.literal("Agents (" + lines.size() + "):\n" + String.join("\n", lines)), false);
        return lines.size();
    }

    private static String ago(Duration d) {
        if (d.toMinutes() < 1) return d.toSeconds() + " s";
        if (d.toHours() < 1) return d.toMinutes() + " min";
        return d.toHours() + " h";
    }

    private static int change(CommandContext<CommandSourceStack> c, String what) {
        CommandSourceStack source = c.getSource();
        Bridge bridge = need(source);
        if (bridge == null) return 0;
        String name = StringArgumentType.getString(c, "name");
        Agents agents = bridge.agents();
        try {
            String done = switch (what) {
                case "add" -> {
                    Path file = agents.add(name);
                    yield "Agent " + name + " added; its token is in " + file.toAbsolutePath();
                }
                case "remove" -> {
                    agents.remove(name);
                    yield "Agent " + name + " removed; its token no longer works";
                }
                default -> {
                    Path file = agents.revoke(name);
                    yield "Token of agent " + name + " rotated: the old one no longer works; the new one is in " + file.toAbsolutePath();
                }
            };
            source.sendSuccess(() -> Component.literal(done), true);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    /** The red button: pause (or resume) one agent or every agent. */
    private static int pause(CommandSourceStack source, String agent, boolean on) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) return 0;
        String who = agent == null ? "every agent" : "agent " + agent;
        if (on) {
            int bots = gm.pause(agent);
            gm.audit().owner(source.getTextName(), "pause", agent == null ? "all" : agent);
            source.sendSuccess(() -> Component.literal("Paused " + who + ": calls with effects are refused, jobs wait, "
                    + bots + " bot(s) stopped. /almighty resume" + (agent == null ? "" : " " + agent) + " to continue"), true);
        } else {
            gm.pause().resume(agent);
            gm.audit().owner(source.getTextName(), "resume", agent == null ? "all" : agent);
            source.sendSuccess(() -> Component.literal("Resumed " + who), true);
        }
        return 1;
    }

    /** The last entries of the audit log, optionally of one agent. */
    private static int log(CommandSourceStack source, String who, int count) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) return 0;
        var entries = gm.audit().recent(count, e -> who == null || e.get("agent").getAsString().equals(who));
        StringBuilder out = new StringBuilder("Almighty log (" + entries.size() + "):");
        for (var el : entries) {
            var e = el.getAsJsonObject();
            String time = e.get("time").getAsString();
            out.append("\n").append(time, Math.min(11, time.length()), Math.min(19, time.length())).append(' ')
                    .append(e.get("agent").getAsString()).append(' ').append(e.get("method").getAsString()).append(' ')
                    .append(e.get("outcome").getAsString());
            if (e.has("job")) out.append(" job #").append(e.get("job").getAsLong());
            String params = e.has("params") ? e.get("params").getAsString() : "";
            if (!params.isEmpty() && !params.equals("{}")) out.append(' ').append(params.length() > 80 ? params.substring(0, 80) + "…" : params);
        }
        String text = out.toString();
        source.sendSuccess(() -> Component.literal(text), false);
        return entries.size();
    }

    private static int reload(CommandSourceStack source) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) return 0;
        String why = gm.policies().reload();
        gm.audit().owner(source.getTextName(), "reload", why == null ? "policy ok" : "policy refused: " + why);
        if (why != null) {
            source.sendFailure(Component.literal("policy.toml refused, the previous policy stays: " + why));
        } else {
            source.sendSuccess(() -> Component.literal("Policy reloaded: preset " + gm.policies().current().preset().id()
                    + ", " + gm.policies().current().owners().size() + " owner(s)"), true);
        }
        Bridge bridge = gm.bridge();
        if (bridge == null) return why == null ? 1 : 0;
        try {
            bridge.agents().reload();
            source.sendSuccess(() -> Component.literal("Agents reloaded: " + String.join(", ", bridge.agents().names())), true);
            return why == null ? 1 : 0;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not read agent tokens: " + e.getMessage()));
            return 0;
        }
    }

    /** Who the server thinks you are: UUID (for owners in policy.toml), whether it is verified, your role. */
    private static int whoami(CommandSourceStack source) {
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p)) {
            source.sendSuccess(() -> Component.literal("The console is the owner by definition"), false);
            return 1;
        }
        java.util.UUID id = p.getUUID();
        boolean verified = ua.zentix.almighty.policy.Identity.verified(id);
        String role = ua.zentix.almighty.policy.Nodes.role(id).id();
        Component line = Component.literal("UUID ").append(Component.literal(id.toString()).withStyle(s -> s
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.COPY_TO_CLIPBOARD, id.toString()))
                        .withUnderlined(true)))
                .append(Component.literal((verified ? ", verified" : ", not verified (offline UUID)") + ", role " + role));
        source.sendSuccess(() -> line, false);
        return 1;
    }
}
