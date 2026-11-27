package ua.zentix.almighty.policy;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.platform.Mc;

import java.util.List;

/**
 * What players see of confirmations (plan 4.8): rendered by the server from the parsed call, prefixed with the
 * reserved {@value #PREFIX} (agent text can never carry it, {@code ua.zentix.almighty.act.Chat}), with clickable
 * buttons that type {@code /almighty confirm <nonce>}. Typing the command works too (Bedrock players, the console).
 * Server thread.
 */
public final class Prompts {
    public static final String PREFIX = ua.zentix.almighty.act.Chat.RESERVED;

    private Prompts() {}

    static MutableComponent prefix() {
        return Component.literal(PREFIX + " ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
    }

    private static Component button(String label, String command, ChatFormatting color) {
        return Component.literal("[" + label + "]").withStyle(s -> s.withColor(color).withBold(true)
                .withClickEvent(Mc.runCommand(command))
                .withHoverEvent(Mc.showText(Component.literal(command))));
    }

    /** The question to the player who must decide a requester confirmation. */
    public static void ask(MinecraftServer server, Confirmations.Pending p) {
        ServerPlayer player = server.getPlayerList().getPlayer(p.confirmer);
        if (player == null) return;
        player.sendSystemMessage(question(p));
    }

    static Component question(Confirmations.Pending p) {
        return prefix().append(Component.literal(GmConfig.NAME.get() + " wants to run as you: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(p.text).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" — cannot be undone. ").withStyle(ChatFormatting.GRAY))
                .append(button("Allow", "/almighty confirm " + p.nonce(), ChatFormatting.GREEN)).append(" ")
                .append(button("Deny", "/almighty deny " + p.nonce(), ChatFormatting.RED))
                .append(typed(p));
    }

    /** The typed form, for clients that cannot click chat (Bedrock through Geyser) and the console. */
    private static Component typed(Confirmations.Pending p) {
        return Component.literal(" or type /almighty confirm " + p.nonce()).withStyle(ChatFormatting.DARK_GRAY);
    }

    /** One admin request in {@code /almighty inbox}. */
    public static Component inboxLine(Confirmations.Pending p) {
        String how = p.method.equals("command") || p.method.equals("function")
                ? " asks " + GmConfig.NAME.get() + " to run as " + p.requesterName + ", with your rights: " : " asks " + GmConfig.NAME.get() + ": ";
        return prefix().append(Component.literal(p.requesterName + how).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(p.text).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(p.count > 1 ? " (×" + p.count + ") " : " "))
                .append(button("Allow", "/almighty confirm " + p.nonce(), ChatFormatting.GREEN)).append(" ")
                .append(button("Deny", "/almighty deny " + p.nonce(), ChatFormatting.RED))
                .append(typed(p));
    }

    /** The quiet digest: one line, never one per request. */
    public static Component digest(List<Confirmations.Pending> waiting) {
        return prefix().append(Component.literal(waiting.size() + " request(s) waiting for an admin — ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/almighty inbox").withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(Mc.runCommand("/almighty inbox"))));
    }

    /** A plain server line to a player (receipts, outcomes). */
    public static Component line(String text) {
        return prefix().append(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    /** A receipt with a suggested undo: "Done for you: … [Undo]". */
    public static Component receipt(String text, Long job) {
        MutableComponent c = prefix().append(Component.literal("Done for you: " + text + " ").withStyle(ChatFormatting.GRAY));
        if (job != null) c.append(Component.literal("[Undo]").withStyle(s -> s.withColor(ChatFormatting.GOLD)
                .withClickEvent(Mc.suggestCommand("/almighty undo " + job))));
        return c;
    }
}
