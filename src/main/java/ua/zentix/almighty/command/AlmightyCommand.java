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
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Agent;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Bridge;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.mcp.signin.SignInCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Command {@code /almighty}: each subcommand has its own requirement. Owner subcommands need permission level 4 (the
 * console and level-4 operators; agents, tokens, scripts and owners only a true owner); confirm, deny, whoami and inbox are for every
 * player, role for admins. Agents and their tokens.
 * Tokens are never printed: a command names the file that holds one. Also the red button ({@code pause},
 * {@code resume}) and the audit ({@code log}). Later steps add {@code role}, {@code confirm}, {@code undo}.
 */
public final class AlmightyCommand {
    private AlmightyCommand() {}

    private static final SuggestionProvider<CommandSourceStack> AGENTS = (c, b) -> {
        Bridge bridge = bridge(c.getSource());
        return SharedSuggestionProvider.suggest(bridge == null ? List.of() : bridge.agents().names(), b);
    };

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        // Owner subcommands need permission level 4 (the console, level-4 operators); whoami is for everyone.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("almighty");
        root.then(Commands.literal("whoami").executes(c -> whoami(c.getSource())));
        // Answers to confirmations: anyone may type them; the server checks who may decide (plan 4.8).
        root.then(Commands.literal("confirm").then(Commands.argument("nonce", StringArgumentType.word())
                .executes(c -> decide(c.getSource(), StringArgumentType.getString(c, "nonce"), true))));
        root.then(Commands.literal("deny").then(Commands.argument("nonce", StringArgumentType.word())
                .executes(c -> decide(c.getSource(), StringArgumentType.getString(c, "nonce"), false))));
        root.then(Commands.literal("inbox").executes(c -> inbox(c.getSource())));
        // Roles on the ladder (no permission plugin): admins and the console, only below their own role.
        root.then(Commands.literal("role").then(Commands.argument("player", StringArgumentType.word())
                .then(Commands.argument("role", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("guest", "member", "builder", "admin", "clear"), b))
                        .executes(c -> role(c.getSource(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "role"), null))
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(c -> role(c.getSource(), StringArgumentType.getString(c, "player"),
                                        StringArgumentType.getString(c, "role"), StringArgumentType.getString(c, "duration")))))));
        root.then(trueOwner("owner")
                .then(Commands.literal("add").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> owners(c.getSource(), StringArgumentType.getString(c, "player"), true))))
                .then(Commands.literal("remove").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> owners(c.getSource(), StringArgumentType.getString(c, "player"), false)))));
        root.then(Commands.literal("undo").then(Commands.argument("change", IntegerArgumentType.integer(1))
                .executes(c -> undo(c.getSource(), IntegerArgumentType.getInteger(c, "change")))));
        root.then(owner("agents").executes(c -> agents(c.getSource())));
        root.then(trueOwner("agent")
                .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> change(c, "add"))))
                .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).suggests(AGENTS)
                        .executes(c -> change(c, "remove")))));
        root.then(trueOwner("script")
                .then(Commands.literal("approve").then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> script(c.getSource(), StringArgumentType.getString(c, "name"), true))))
                .then(Commands.literal("revoke").then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> script(c.getSource(), StringArgumentType.getString(c, "name"), false))))
                .then(Commands.literal("list").executes(c -> scripts(c.getSource()))));
        root.then(trueOwner("policy").then(Commands.literal("undo").executes(c -> policyUndo(c.getSource()))));
        root.then(trueOwner("revoke").then(Commands.argument("name", StringArgumentType.word()).suggests(AGENTS)
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
        dispatcher.register(root);
    }

    /** An owner subcommand: permission level 4. */
    private static LiteralArgumentBuilder<CommandSourceStack> owner(String name) {
        return Commands.literal(name).requires(s -> Mc.has(s, 4));
    }

    /**
     * Owners and agent tokens (who gets owner rights and the engine): the console, or a verified owner from
     * policy.toml. An operator who is not an owner is an admin on the ladder and cannot make themselves owner.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> trueOwner(String name) {
        return Commands.literal(name).requires(AlmightyCommand::isTrueOwner);
    }

    /** The console, or a verified owner from policy.toml (see {@link #trueOwner}). */
    public static boolean isTrueOwner(CommandSourceStack s) {
        if (!Mc.has(s, 4)) return false;
        // Who typed it, not who it runs as: "/execute as <owner> run almighty …" by another operator is that operator's.
        net.minecraft.server.level.ServerPlayer typist = Mc.typedBy(s);
        if (typist != null) {
            return ua.zentix.almighty.policy.Identity.verified(typist.getUUID())
                    && ua.zentix.almighty.policy.Nodes.role(typist.getUUID()) == ua.zentix.almighty.policy.Role.OWNER;
        }
        return s.getEntity() == null && (s.source == s.getServer() || s.source instanceof net.minecraft.server.rcon.RconConsoleSource);
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
        // An agent's name, or a player's (the calls made for their requests).
        java.util.UUID player = null;
        if (who != null) {
            var online = source.getServer().getPlayerList().getPlayerByName(who);
            if (online != null) {
                player = online.getUUID();
            } else if (gm.audit().recent(1, e -> e.get("agent").getAsString().equals(who)).isEmpty()) {
                // Not an agent with entries: an offline player (the profile cache may ask Mojang on a miss).
                var profile = Mc.cachedProfile(source.getServer(), who);
                if (profile != null) player = Mc.id(profile);
            }
        }
        String requester = player == null ? null : player.toString();
        var entries = gm.audit().recent(count, e -> who == null || e.get("agent").getAsString().equals(who)
                || (requester != null && e.has("requester") && e.get("requester").getAsString().equals(requester)));
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

    private static int decide(CommandSourceStack source, String nonce, boolean allow) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) return 0;
        // Only the player's own typing (or click) answers, never a command run as them (execute as …): Mc.typedBy.
        net.minecraft.server.level.ServerPlayer by = Mc.typedBy(source);
        if (by == null && (source.getEntity() != null || !Mc.has(source, 4))) {
            source.sendFailure(Component.literal("Only a player or the console can answer"));
            return 0;
        }
        // One deny node for both: a sign-in code is 4 digits, a confirmation nonce 24 hex characters.
        if (!allow && SignInCommand.isCode(nonce) && Mc.has(source, 4)) return SignInCommand.deny(source, nonce);
        String answer = gm.decisions().decide(nonce, by, allow);
        gm.audit().owner(source.getTextName(), allow ? "confirm" : "deny", answer);
        source.sendSuccess(() -> ua.zentix.almighty.policy.Prompts.line(answer), false);
        return answer.startsWith("Allowed") || answer.startsWith("Denied") ? 1 : 0;
    }

    /** Admin requests the viewer may decide (the console sees all). */
    private static int inbox(CommandSourceStack source) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) return 0;
        java.util.UUID id = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p ? p.getUUID() : null;
        int n = 0;
        for (var p : gm.decisions().confirmations().waitingForAdmins()) {
            if (id == null ? !Mc.has(source, 4) : !(ua.zentix.almighty.policy.Identity.verified(id) && ua.zentix.almighty.policy.Nodes.holds(id, p.node))) continue;
            Component line = ua.zentix.almighty.policy.Prompts.inboxLine(p);
            source.sendSuccess(() -> line, false);
            n++;
        }
        if (n == 0) source.sendSuccess(() -> ua.zentix.almighty.policy.Prompts.line("No requests wait for you"), false);
        return n;
    }

    /** Undo a journal change: the player it was made for (typed by them), or the console / a level-4 operator. */
    private static int undo(CommandSourceStack source, int id) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) return 0;
        var meta = gm.journal().describe(id);
        if (meta == null) {
            source.sendFailure(Component.literal("No change " + id));
            return 0;
        }
        boolean mine = Mc.typedBy(source) instanceof net.minecraft.server.level.ServerPlayer p && meta.has("requester")
                && p.getUUID().toString().equals(meta.get("requester").getAsString()) && !meta.get("method").getAsString().equals("undo");
        if (!mine && !Mc.has(source, 4)) {
            source.sendFailure(Component.literal("Change " + id + " was not made for you"));
            return 0;
        }
        try {
            gm.revert(id, null, new com.google.gson.JsonObject(), 0);
            gm.audit().owner(source.getTextName(), "undo", "change " + id);
            source.sendSuccess(() -> ua.zentix.almighty.policy.Prompts.line("Undoing change " + id
                    + " (positions changed since are left as they are)"), false);
            return 1;
        } catch (ua.zentix.almighty.bridge.RpcException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    /** A player by name: online, else the server's profile cache (players who joined before). */
    private static com.mojang.authlib.GameProfile profile(CommandSourceStack source, String name) {
        var online = source.getServer().getPlayerList().getPlayerByName(name);
        if (online != null) return online.getGameProfile();
        return Mc.cachedProfile(source.getServer(), name);
    }

    private static int role(CommandSourceStack source, String name, String roleName, String durationText) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) return 0;
        ua.zentix.almighty.policy.Role granter;
        String by;
        if (Mc.typedBy(source) instanceof net.minecraft.server.level.ServerPlayer p) {
            granter = ua.zentix.almighty.policy.Identity.verified(p.getUUID()) ? ua.zentix.almighty.policy.Nodes.role(p.getUUID())
                    : ua.zentix.almighty.policy.Role.GUEST;
            by = Mc.name(p.getGameProfile());
        } else if (Mc.has(source, 4) && source.getEntity() == null) {
            granter = ua.zentix.almighty.policy.Role.OWNER;
            by = source.getTextName();
        } else {
            source.sendFailure(Component.literal("Roles are given by admins and the console"));
            return 0;
        }
        if (!granter.atLeast(ua.zentix.almighty.policy.Role.ADMIN)) {
            source.sendFailure(Component.literal("Only admins and owners give roles"));
            return 0;
        }
        var profile = profile(source, name);
        if (profile == null) {
            source.sendFailure(Component.literal("No player " + name + " (they must have joined this server)"));
            return 0;
        }
        ua.zentix.almighty.policy.Role role = roleName.equals("clear") ? null : ua.zentix.almighty.policy.Role.parse(roleName);
        if (role == null && !roleName.equals("clear")) {
            source.sendFailure(Component.literal("Role: guest, member, builder, admin, or clear"));
            return 0;
        }
        java.time.Duration d = null;
        if (durationText != null) {
            d = ua.zentix.almighty.policy.Roles.parse(durationText);
            if (d == null) {
                source.sendFailure(Component.literal("Duration such as 30m, 3h, 2d"));
                return 0;
            }
        }
        String why = gm.roles().set(Mc.id(profile), Mc.name(profile), role, d, granter, by);
        if (why != null) {
            source.sendFailure(Component.literal(why));
            return 0;
        }
        String text = role == null ? Mc.name(profile) + " no longer has a granted role"
                : Mc.name(profile) + " is " + role.id() + (d == null ? "" : " for " + durationText + ", until "
                        + until(java.time.Instant.now().plus(d), gm.policies().current().timezone()));
        gm.audit().owner(by, "role", text);
        source.sendSuccess(() -> ua.zentix.almighty.policy.Prompts.line(text), true);
        return 1;
    }

    /** A time in the policy's zone, as players read it: {@code 2026-11-28 18:30 Europe/Warsaw}. */
    static String until(java.time.Instant at, java.time.ZoneId zone) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(at.atZone(zone)) + " " + zone.getId();
    }

    /** Owners are UUIDs in policy.toml: added and removed by the owner (level 4), the file validated and reloaded. */
    private static int owners(CommandSourceStack source, String name, boolean add) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null || gm.policies().file() == null) return 0;
        var profile = profile(source, name);
        if (profile == null) {
            source.sendFailure(Component.literal("No player " + name + " (they must have joined this server)"));
            return 0;
        }
        java.util.Set<java.util.UUID> owners = new java.util.LinkedHashSet<>(gm.policies().current().owners());
        if (add) owners.add(Mc.id(profile));
        else owners.remove(Mc.id(profile));
        // PolicyFile.owners refuses to remove the last owner, as the file route does.
        try {
            String why = ua.zentix.almighty.policy.PolicyFile.owners(gm.policies().file(), owners);
            if (why == null) why = gm.policies().reload();
            if (why != null) {
                source.sendFailure(Component.literal("policy.toml not changed: " + why));
                return 0;
            }
        } catch (IOException e) {
            source.sendFailure(Component.literal("Cannot write policy.toml: " + e.getMessage()));
            return 0;
        }
        boolean verified = ua.zentix.almighty.policy.Identity.verified(Mc.id(profile));
        gm.audit().owner(source.getTextName(), add ? "owner add" : "owner remove", Mc.name(profile) + " " + Mc.id(profile));
        source.sendSuccess(() -> ua.zentix.almighty.policy.Prompts.line((add ? "Owner added: " : "Owner removed: ") + Mc.name(profile)
                + (add && !verified ? " (their UUID is not verified on this server, so they act as a guest until it is)" : "")), true);
        return 1;
    }

    /** {@code /almighty script approve|revoke <name>}: pin the file's hash (it runs by name until it changes) or drop it. */
    private static int script(CommandSourceStack source, String name, boolean approve) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) return 0;
        String why = gm.files().approve(name, approve);
        gm.audit().owner(source.getTextName(), approve ? "script approve" : "script revoke", name + (why == null ? "" : ": " + why));
        if (why != null) {
            source.sendFailure(Component.literal(why));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(approve ? "Approved script " + name + ": agents with almighty.scripts.run may run it by name until the file changes"
                : "Script " + name + " is no longer approved"), true);
        return 1;
    }

    private static int scripts(CommandSourceStack source) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) return 0;
        try {
            var list = gm.files().approvedList();
            source.sendSuccess(() -> Component.literal(list.isEmpty() ? "No approved scripts" : "Approved scripts: " + list), false);
            return list.size();
        } catch (java.io.IOException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    /** {@code /almighty policy undo}: put back the policy.toml saved before the last change (by the agent or by you). */
    private static int policyUndo(CommandSourceStack source) {
        GmServer gm = GmServer.of(source.getServer());
        if (gm == null) return 0;
        String why = gm.files().undoByOwner();
        gm.audit().owner(source.getTextName(), "policy undo", why == null ? "restored" : "refused: " + why);
        if (why != null) {
            source.sendFailure(Component.literal("Policy not changed: " + why));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Policy restored: preset " + gm.policies().current().preset().id()), true);
        return 1;
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
                        .withClickEvent(Mc.copyToClipboard(id.toString()))
                        .withUnderlined(true)))
                .append(Component.literal((verified ? ", verified" : ", not verified (offline UUID)") + ", role " + role));
        source.sendSuccess(() -> line, false);
        return 1;
    }
}
