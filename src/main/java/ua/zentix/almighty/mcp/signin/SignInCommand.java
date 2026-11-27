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
 * owner's own rights when {@code owner} is given ({@link OwnerApp}); {@code approve}
 * alone lists waiting logins (without codes); {@code deny <code>}; {@code connect} shows the address and the ways to
 * connect each product. Brigadier merges these into the same {@code almighty} node.
 */
public final class SignInCommand {
    private SignInCommand() {}

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("almighty").requires(s -> Mc.has(s, 4));
        root.then(Commands.literal("approve").executes(c -> waiting(c.getSource()))
                .then(Commands.argument("code", StringArgumentType.word()).executes(c -> approve(c, null, false))
                        .then(Commands.argument("name", StringArgumentType.word()).executes(c -> approve(c, StringArgumentType.getString(c, "name"), false))
                                .then(Commands.literal("owner").executes(c -> approve(c, StringArgumentType.getString(c, "name"), true))))));
        root.then(Commands.literal("deny").then(Commands.argument("code", StringArgumentType.word()).executes(SignInCommand::deny)));
        root.then(Commands.literal("connect").executes(c -> connect(c.getSource())));
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

    private static int approve(CommandContext<CommandSourceStack> c, String name, boolean owner) {
        CommandSourceStack source = c.getSource();
        Mcp mcp = mcp(source);
        if (mcp == null) return 0;
        GmServer gm = GmServer.of(source.getServer());
        try {
            if (owner) {
                // Checked before the approval, so a refused grant never leaves an app connected with fewer rights than asked.
                String listed = OwnerApp.listed(gm.policies().file(), name);
                if (listed != null) {
                    source.sendFailure(Component.literal(listed));
                    return 0;
                }
            }
            String agent = mcp.signIn().approve(StringArgumentType.getString(c, "code"), name);
            String rights;
            if (owner) {
                String refused = OwnerApp.grant(gm.policies().file(), agent);
                String error = refused == null ? gm.policies().reload() : null;
                rights = refused != null ? " " + refused + "." : error != null ? " The policy did not reload: " + error + "."
                        : " It has your rights (ceiling owner in policy.toml).";
            } else {
                rights = " It has " + gm.policies().current().ceiling(agent).id() + " rights. If it is your own app, sign it in again "
                        + "and approve with /almighty approve <code> " + agent + " owner.";
            }
            String text = "Approved: it connects as agent " + agent + "." + rights + " End it any time with /almighty agent remove " + agent;
            source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (SignIn.OAuthError | IOException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int deny(CommandContext<CommandSourceStack> c) {
        CommandSourceStack source = c.getSource();
        Mcp mcp = mcp(source);
        if (mcp == null) return 0;
        if (!mcp.signIn().deny(StringArgumentType.getString(c, "code"))) {
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
