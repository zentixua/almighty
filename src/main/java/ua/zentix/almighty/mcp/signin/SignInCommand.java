package ua.zentix.almighty.mcp.signin;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.mcp.Connect;
import ua.zentix.almighty.mcp.Mcp;
import ua.zentix.almighty.platform.Mc;

import java.io.IOException;
import java.util.List;

/**
 * Owner sign-in commands, added to {@code /almighty} (permission level 4: the console and level-4 operators):
 * {@code approve <code> [name] [owner]} lets the waiting login with that code in as agent {@code name}, with the
 * owner's own rights when {@code owner} is given ({@link OwnerApp}) or while policy.toml lists no owner yet (the first
 * setup: the approver is the owner, so the app is theirs); {@code approve}
 * alone lists waiting logins (without codes); {@code deny <code>}; {@code connect} shows the address and the ways to
 * connect each product. Brigadier merges these into the same {@code almighty} node.
 */
public final class SignInCommand {
    private SignInCommand() {}

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        // No requirement on the root: Brigadier keeps the first registration's requirement when it merges the
        // almighty node, and AlmightyCommand's subcommands (confirm, whoami …) are for every player. Each
        // subcommand here carries its own. "deny <code>" is AlmightyCommand's deny node (codes are 4 digits).
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("almighty");
        root.then(Commands.literal("approve").requires(s -> Mc.has(s, 4)).executes(c -> waiting(c.getSource()))
                // Approving mints an agent (admin ceiling by default): the owner's, as `agent add` (rights audit m8).
                // A server with no owners yet: the level-4 operator who approves becomes its first owner (setup in one step).
                .then(Commands.argument("code", StringArgumentType.word()).requires(SignInCommand::ownerOrFirst)
                        .executes(c -> approve(c, null, false, null))
                        .then(Commands.argument("name", StringArgumentType.word()).executes(c -> approve(c, StringArgumentType.getString(c, "name"), false, null))
                                // An app with the owner's rights: only an owner (or the console) grants it (plan 4.4).
                                .then(Commands.literal("owner").requires(SignInCommand::ownerOrFirst)
                                        .executes(c -> approve(c, StringArgumentType.getString(c, "name"), true, null))
                                        // From the console: the player who owns this app becomes an owner too.
                                        .then(Commands.argument("player", StringArgumentType.word()).requires(SignInCommand::console)
                                                .executes(c -> approve(c, StringArgumentType.getString(c, "name"), true,
                                                        StringArgumentType.getString(c, "player"))))))));
        root.then(Commands.literal("connect").requires(s -> Mc.has(s, 4)).executes(c -> connect(c.getSource())));
        dispatcher.register(root);
    }

    private static Mcp mcp(CommandSourceStack source) {
        GmServer gm = GmServer.of(source.getServer());
        Mcp mcp = gm == null ? null : gm.mcp();
        if (mcp == null) source.sendFailure(Component.literal("The Almighty bridge is not running on this server"));
        return mcp;
    }

    private static int waiting(CommandSourceStack source) {
        Mcp mcp = mcp(source);
        if (mcp == null) return 0;
        List<SignIn.Pending> list = mcp.signIn().waiting();
        if (list.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No sign-in is waiting. Start one from your agent: /almighty connect"), false);
            return 0;
        }
        StringBuilder out = new StringBuilder("Waiting sign-ins (type the code shown on the sign-in page):");
        for (SignIn.Pending p : list) out.append("\n- ").append(p.client().name()).append(" from ").append(p.address());
        source.sendSuccess(() -> Component.literal(out.toString()), false);
        return list.size();
    }

    /** The console (or RCON): not a player typing. */
    static boolean console(CommandSourceStack s) {
        return Mc.typedBy(s) == null && ua.zentix.almighty.command.AlmightyCommand.isTrueOwner(s);
    }

    /**
     * A true owner; or, while policy.toml lists no owner, a verified player typing it who is a level-4 operator (the
     * highest authority in game: nobody grants more than they have). Who typed it, not who it runs as.
     */
    public static boolean ownerOrFirst(CommandSourceStack s) {
        if (ua.zentix.almighty.command.AlmightyCommand.isTrueOwner(s)) return true;
        return firstOwner(s) != null;
    }

    /** The player who would become the first owner by typing this, or null. */
    private static net.minecraft.server.level.ServerPlayer firstOwner(CommandSourceStack s) {
        GmServer gm = GmServer.of(s.getServer());
        net.minecraft.server.level.ServerPlayer typist = Mc.typedBy(s);
        if (gm == null || typist == null || !gm.policies().current().owners().isEmpty()) return null;
        if (!ua.zentix.almighty.policy.Identity.verified(typist.getUUID())) return null;
        return Mc.opLevel(s.getServer(), typist.getGameProfile()) >= 4 ? typist : null;
    }

    /**
     * The typist becomes this server's first owner: only while policy.toml lists none, and only a verified level-4
     * operator typing it. Null on success, else why not.
     */
    public static String claimFirstOwner(CommandSourceStack source) throws IOException {
        GmServer gm = GmServer.of(source.getServer());
        net.minecraft.server.level.ServerPlayer first = firstOwner(source);
        if (gm == null || first == null) return "only an owner approves sign-ins";
        String why = addOwner(gm, first.getUUID(), true);
        if (why == null) gm.audit().owner(Mc.name(first.getGameProfile()), "owner add", "first owner, approving a sign-in");
        return why;
    }

    /** Add {@code id} to policy.toml's owners (validated, reloaded); null on success, else why not. */
    private static String addOwner(GmServer gm, java.util.UUID id, boolean onlyIfNone) throws IOException {
        synchronized (ua.zentix.almighty.policy.PolicyFile.LOCK) {
            java.util.Set<java.util.UUID> owners = new java.util.LinkedHashSet<>(gm.policies().current().owners());
            if (onlyIfNone) {
                if (!owners.isEmpty()) return "this server has an owner now";
                // The file itself, not only the loaded policy: an owner's edit not yet reloaded, or a mistake in the
                // file (which keeps the last good policy), must not be overwritten by a first owner.
                java.nio.file.Path file = gm.policies().file();
                if (file != null && java.nio.file.Files.exists(file)) {
                    try {
                        if (!ua.zentix.almighty.policy.Policy.parse(java.nio.file.Files.readString(file)).owners().isEmpty()) {
                            return "policy.toml lists an owner (run /almighty reload)";
                        }
                    } catch (ua.zentix.almighty.policy.Policy.Invalid e) {
                        return "policy.toml has a mistake; the owner fixes it first: " + e.getMessage();
                    }
                }
            }
            if (!owners.add(id)) return null;
            String why = ua.zentix.almighty.policy.PolicyFile.owners(gm.policies().file(), owners);
            return why != null ? why : gm.policies().reload();
        }
    }

    private static int approve(CommandContext<CommandSourceStack> c, String name, boolean owner, String player) {
        CommandSourceStack source = c.getSource();
        Mcp mcp = mcp(source);
        if (mcp == null) return 0;
        GmServer gm = GmServer.of(source.getServer());
        try {
            // Owners first: the first owner (in game), or the console naming the app's player; then the app. The code
            // is checked before anyone becomes owner, so a mistyped or expired code changes nothing.
            if (!mcp.signIn().waitingFor(StringArgumentType.getString(c, "code"))) {
                source.sendFailure(Component.literal("No sign-in is waiting with that code"));
                return 0;
            }
            if (owner) {
                String listed = OwnerApp.listed(gm.policies().file(), name);
                if (listed != null) {
                    source.sendFailure(Component.literal(listed));
                    return 0;
                }
            }
            // First setup (lead's N1): while policy.toml lists no owner and no owner app, whoever may approve is the
            // owner (the console, or the level-4 operator who becomes the first owner now), and the app they sign in is
            // their own: it gets their ceiling, so players' /gm is routed to it (Agents.route) with exactly the command
            // the page shows. Only the first: later plain approvals keep the default ceiling (review of #105).
            boolean firstSetup = firstApp(gm.policies().current());
            String became = null;
            if (!ua.zentix.almighty.command.AlmightyCommand.isTrueOwner(source)) {
                String why = claimFirstOwner(source);
                if (why != null) {
                    source.sendFailure(Component.literal("Not approved: " + why));
                    return 0;
                }
                became = Mc.name(Mc.typedBy(source).getGameProfile());
            } else if (player != null) {
                net.minecraft.server.level.ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(player);
                com.mojang.authlib.GameProfile profile = online != null ? online.getGameProfile() : Mc.cachedProfile(source.getServer(), player);
                if (profile == null) {
                    source.sendFailure(Component.literal("No player " + player + " (they must have joined this server)"));
                    return 0;
                }
                String why = addOwner(gm, Mc.id(profile), false);
                if (why != null) {
                    source.sendFailure(Component.literal("Not approved: " + why));
                    return 0;
                }
                became = Mc.name(profile);
                gm.audit().owner(source.getTextName(), "owner add", became + " " + Mc.id(profile) + ", approving a sign-in");
            }
            String agent = mcp.signIn().approve(StringArgumentType.getString(c, "code"), name);
            boolean ownApp = !owner && firstSetup && OwnerApp.listed(gm.policies().file(), agent) == null;
            String rights = rights(gm, agent, owner || ownApp, ownApp);
            String text = (became == null ? "" : became + " is now an owner of this server (policy.toml). ")
                    + "Approved: it connects as agent " + agent + "." + rights
                    + (rights.contains("/almighty agent remove") ? "" : " End it any time with /almighty agent remove " + agent);
            source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (SignIn.OAuthError | IOException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    /**
     * First setup: policy.toml lists no owner and no agent with an owner ceiling besides the token-only {@code gm}
     * (the template's). The next plain approval then gives the owner's own app the owner's ceiling (lead's N1).
     */
    public static boolean firstApp(ua.zentix.almighty.policy.Policy p) {
        if (!p.owners().isEmpty()) return false;
        for (String name : p.agents().keySet()) {
            if (!name.equals(ua.zentix.almighty.bridge.Agents.DEFAULT) && p.ceiling(name) == ua.zentix.almighty.policy.Role.OWNER) return false;
        }
        return true;
    }

    /**
     * The approved agent's rights, and the words that say so: with {@code owner} the owner's ceiling in policy.toml
     * ({@link OwnerApp}; {@code firstApp}: granted by default at first setup, lead's N1), else the ceiling the policy
     * gives it (admin by default) and how to make it the owner's app.
     */
    public static String rights(GmServer gm, String agent, boolean owner, boolean firstApp) throws IOException {
        if (!owner) {
            return " It has " + gm.policies().current().ceiling(agent).id() + " rights. If it is your own app, sign it in again "
                    + "and approve with /almighty approve <code> " + agent + " owner.";
        }
        String refused = OwnerApp.grant(gm.policies().file(), agent);
        String error = refused == null ? gm.policies().reload() : null;
        if (refused != null) return " " + refused + ".";
        if (error != null) return " The policy did not reload: " + error + ".";
        return firstApp ? " As this server's first app it has your rights (ceiling owner in policy.toml) and players' /gm goes to it."
                + " Not your own app? End it with /almighty agent remove " + agent + "."
                : " It has your rights (ceiling owner in policy.toml).";
    }

    /** A sign-in code as the login page shows it (4 digits); a confirmation nonce never looks like one. */
    public static boolean isCode(String s) {
        return s.length() == 4 && s.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }

    /** {@code /almighty deny <code>} (level 4; routed here by AlmightyCommand's deny node). */
    public static int deny(CommandSourceStack source, String code) {
        Mcp mcp = mcp(source);
        if (mcp == null) return 0;
        if (!mcp.signIn().deny(code)) {
            source.sendFailure(Component.literal("No sign-in is waiting with that code"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Refused."), true);
        return 1;
    }

    private static int connect(CommandSourceStack source) {
        Mcp mcp = mcp(source);
        if (mcp == null) return 0;
        MutableComponent out = Component.literal("Connect an AI agent to this server: ").withStyle(ChatFormatting.GOLD)
                .append(copyable(mcp.mcpUrl()));
        for (Connect.Way w : mcp.ways()) {
            out.append(Component.literal("\n" + w.product() + ": ").withStyle(ChatFormatting.YELLOW));
            if (w.how() != null) out.append(copyable(w.how()));
            if (w.link() != null) {
                out.append(Component.literal(" [link]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(Mc.openUrl(w.link()))
                        .withHoverEvent(Mc.showText(Component.literal(w.link())))));
            }
        }
        out.append(Component.literal("\nThe agent's app opens a sign-in page with a code; approve it here with /almighty approve <code>.")
                .withStyle(ChatFormatting.GRAY));
        source.sendSuccess(() -> out, false);
        return 1;
    }

    /** Text that copies itself when clicked (players); the console just prints it. */
    private static MutableComponent copyable(String text) {
        return Component.literal(text).withStyle(Style.EMPTY.withClickEvent(Mc.copyToClipboard(text))
                .withHoverEvent(Mc.showText(Component.literal("Click to copy"))));
    }
}
