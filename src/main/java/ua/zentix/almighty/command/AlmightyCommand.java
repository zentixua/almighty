package ua.zentix.almighty.command;

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
 * Tokens are never printed: a command names the file that holds one. Later steps add {@code log}, {@code pause},
 * {@code role}, {@code confirm}, {@code undo}.
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
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("almighty").requires(s -> s.hasPermission(4));
        root.then(Commands.literal("agents").executes(c -> agents(c.getSource())));
        root.then(Commands.literal("agent")
                .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> change(c, "add"))))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).suggests(AGENTS)
                        .executes(c -> change(c, "remove")))));
        root.then(Commands.literal("revoke").then(Commands.argument("name", StringArgumentType.word()).suggests(AGENTS)
                .executes(c -> change(c, "revoke"))));
        root.then(Commands.literal("reload").executes(c -> reload(c.getSource())));
        e.getDispatcher().register(root);
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

    private static int reload(CommandSourceStack source) {
        Bridge bridge = need(source);
        if (bridge == null) return 0;
        try {
            bridge.agents().reload();
            source.sendSuccess(() -> Component.literal("Agents reloaded: " + String.join(", ", bridge.agents().names())), true);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not read agent tokens: " + e.getMessage()));
            return 0;
        }
    }
}
