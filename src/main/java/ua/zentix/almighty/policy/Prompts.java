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

    private static Component button(Component label, String command, ChatFormatting color) {
        return Component.literal("[").append(label).append("]").withStyle(s -> s.withColor(color).withBold(true)
                .withClickEvent(Mc.runCommand(command))
                .withHoverEvent(Mc.showText(Component.literal(command))));
    }

    private static Component button(String label, String command, ChatFormatting color) {
        return button(Component.literal(label), command, color);
    }

    private static Component t(String key, String lang, Object... args) {
        return ua.zentix.almighty.feed.GmMessages.get(key, lang, args);
    }

    /** The question to the player who must decide a requester confirmation, in their language (English or Russian). */
    public static void ask(MinecraftServer server, Confirmations.Pending p) {
        ServerPlayer player = server.getPlayerList().getPlayer(p.confirmer);
        if (player == null) return;
        String lang = ua.zentix.almighty.feed.GmMessages.language(player);
        player.sendSystemMessage(question(p, lang));
        // 26.x: the same question as a dialog in front of the player (the chat line stays: Bedrock, a dialog closed with
        // Later or Escape leaves the request waiting). Not asserted in GameTest: a mock connection keeps no packets.
        if (!Identity.bedrock(player.getUUID())) {
            String name = GmConfig.NAME.get();
            Mc.confirmDialog(player, prefix().append(t("almighty.prompt.dialog_title", lang, name).copy().withStyle(ChatFormatting.WHITE)),
                    t("almighty.prompt.dialog_body", lang, name).copy().withStyle(ChatFormatting.WHITE).append("\n")
                            .append(Component.literal(p.text).withStyle(ChatFormatting.YELLOW)).append("\n")
                            .append(t("almighty.prompt.dialog_irreversible", lang).copy().withStyle(ChatFormatting.GRAY)),
                    "/almighty confirm " + p.nonce(), "/almighty deny " + p.nonce(),
                    t("almighty.prompt.allow", lang), t("almighty.prompt.deny", lang), t("almighty.prompt.later", lang));
        }
    }

    static Component question(Confirmations.Pending p, String lang) {
        return prefix().append(t("almighty.prompt.asks", lang, GmConfig.NAME.get()).copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(p.text).withStyle(ChatFormatting.YELLOW))
                .append(t("almighty.prompt.irreversible", lang).copy().withStyle(ChatFormatting.GRAY))
                .append(button(t("almighty.prompt.allow", lang), "/almighty confirm " + p.nonce(), ChatFormatting.GREEN)).append(" ")
                .append(button(t("almighty.prompt.deny", lang), "/almighty deny " + p.nonce(), ChatFormatting.RED))
                .append(t("almighty.prompt.typed", lang, "/almighty confirm " + p.nonce()).copy().withStyle(ChatFormatting.DARK_GRAY));
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

    /** A translated server line to a player ({@code key} from the lang files, {@code arg} shown as is). */
    public static Component line(String key, String lang, String arg) {
        return prefix().append(t(key, lang, arg).copy().withStyle(ChatFormatting.GRAY));
    }

    /** A receipt with a suggested undo: "Done for you: … [Undo]", in the player's language. */
    public static Component receipt(String text, Long job, String lang) {
        MutableComponent c = prefix().append(t("almighty.receipt.done", lang, text).copy().withStyle(ChatFormatting.GRAY));
        if (job != null) c.append(t("almighty.receipt.undo", lang).copy().withStyle(s -> s.withColor(ChatFormatting.GOLD)
                .withClickEvent(Mc.suggestCommand("/almighty undo " + job))));
        return c;
    }
}
