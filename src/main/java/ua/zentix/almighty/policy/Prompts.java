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
 * What players see of confirmations (plan 4.8): rendered by the server from the parsed call ({@link Facts}, beside the
 * agent's optional, cleaned {@link Summary}; the exact call on hover, in the dialog and behind {@code /almighty show}), prefixed with the
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

    private static Component t(String key, String lang, Object... args) {
        return ua.zentix.almighty.feed.GmMessages.get(key, lang, args);
    }

    /** The question to the player who must decide a requester confirmation, in their language (English or Russian). */
    public static void ask(MinecraftServer server, Confirmations.Pending p) {
        ServerPlayer player = server.getPlayerList().getPlayer(p.confirmer);
        if (player == null) return;
        String lang = ua.zentix.almighty.feed.GmMessages.language(player);
        boolean bedrock = Identity.bedrock(player.getUUID());
        player.sendSystemMessage(question(p, lang, bedrock));
        // 26.x: the same question as a dialog in front of the player (the chat line stays: Bedrock, a dialog closed with
        // Later or Escape leaves the request waiting). Not asserted in GameTest: a mock connection keeps no packets.
        if (!bedrock) {
            Mc.confirmDialog(player, prefix().append(t("almighty.prompt.dialog_title", lang, name()).copy().withStyle(ChatFormatting.WHITE)),
                    dialogBody(p, lang), "/almighty confirm " + p.nonce(), "/almighty deny " + p.nonce(),
                    t("almighty.prompt.allow", lang), t("almighty.prompt.deny", lang), t("almighty.prompt.later", lang));
        }
    }

    private static String name() {
        return GmConfig.NAME.get();
    }

    /** The dialog's text: who asks, the summary and facts, the exact call in full, and whether it can be undone where that is known. */
    public static Component dialogBody(Confirmations.Pending p, String lang) {
        MutableComponent body = t("almighty.prompt.dialog_body", lang, name()).copy().withStyle(ChatFormatting.WHITE);
        if (p.confirmer != null && !p.confirmer.equals(p.requester)) {
            body.append(" ").append(t("almighty.prompt.asked_by", lang, p.requesterName).copy().withStyle(ChatFormatting.WHITE));
        }
        body.append("\n").append(what(p.summary, p.facts, p.text, lang, true)).append("\n\n")
                .append(t("almighty.prompt.exact", lang, p.text).copy().withStyle(ChatFormatting.GRAY));
        String undo = switch (p.undo) {
            case REVERTIBLE -> "almighty.prompt.dialog_undoable";
            case PERMANENT -> "almighty.prompt.dialog_irreversible";
            case UNSTATED -> null;
        };
        if (undo != null) body.append("\n\n").append(t(undo, lang).copy().withStyle(ChatFormatting.GRAY));
        return body;
    }

    /** The question's end after the facts: whether it can be undone, only where that is true ({@link Confirmations#undo}). */
    static String ending(Confirmations.Undo undo) {
        return switch (undo) {
            case REVERTIBLE -> "almighty.prompt.undoable";
            case PERMANENT -> "almighty.prompt.irreversible";
            case UNSTATED -> "almighty.prompt.end";
        };
    }

    /** The agent's words in the language's quotes (a summary holds no quote characters, so it cannot close them). */
    private static MutableComponent quoted(String summary, String lang) {
        return t("almighty.prompt.quote", lang, summary).copy();
    }

    /**
     * What a call does, as a player reads it: the agent's summary in quotes, marked as its words, then the server's
     * own facts (always; alone when there is no summary), with the exact call on hover. {@code prompt}: before it
     * runs ("what runs:"), else after (a receipt or an outcome).
     */
    public static MutableComponent what(String summary, List<Facts.Fact> facts, String exact, String lang, boolean prompt) {
        MutableComponent c = Component.empty();
        if (summary != null) {
            Component hover = t("almighty.prompt.words_hover", lang, name());
            c.append(quoted(summary, lang).withStyle(s -> s.withColor(ChatFormatting.AQUA).withItalic(true).withHoverEvent(Mc.showText(hover))));
            c.append(t(prompt ? "almighty.prompt.its_words" : "almighty.prompt.its_words_done", lang).copy().withStyle(ChatFormatting.GRAY));
            if (prompt) c.append(t("almighty.prompt.what_runs", lang).copy().withStyle(ChatFormatting.GRAY));
        }
        Component hover = t("almighty.prompt.exact", lang, exact);
        c.append(Facts.component(facts, lang).withStyle(s -> s.withColor(ChatFormatting.YELLOW).withHoverEvent(Mc.showText(hover))));
        return c;
    }

    public static Component question(Confirmations.Pending p, String lang) {
        return question(p, lang, false);
    }

    /** The chat question; {@code bedrock}: the client shows no hover, so the exact call is written out. */
    public static Component question(Confirmations.Pending p, String lang, boolean bedrock) {
        MutableComponent c = prefix().append(t("almighty.prompt.wants", lang, name()).copy().withStyle(ChatFormatting.WHITE));
        if (p.confirmer != null && !p.confirmer.equals(p.requester)) {
            c.append(t("almighty.prompt.asked_by", lang, p.requesterName).copy().withStyle(ChatFormatting.WHITE)).append(" ");
        }
        c.append(what(p.summary, p.facts, p.text, lang, true))
                .append(t(ending(p.undo), lang).copy().withStyle(ChatFormatting.GRAY));
        buttons(c, p, lang, bedrock);
        return c;
    }

    /**
     * [Allow] [Deny] [Details]; the line ends there for clients that click (the exact call is on hover and behind
     * [Details]). {@code noClicks} (Bedrock through Geyser, the console): the typed commands and the exact call written out.
     */
    private static void buttons(MutableComponent c, Confirmations.Pending p, String lang, boolean noClicks) {
        String confirm = "/almighty confirm " + p.nonce(), deny = "/almighty deny " + p.nonce(), show = "/almighty show " + p.nonce();
        c.append(button(t("almighty.prompt.allow", lang), confirm, ChatFormatting.GREEN)).append(" ")
                .append(button(t("almighty.prompt.deny", lang), deny, ChatFormatting.RED)).append(" ")
                .append(Component.literal("[").append(t("almighty.prompt.details", lang)).append("]").withStyle(s -> s
                        .withColor(ChatFormatting.AQUA).withClickEvent(Mc.runCommand(show))
                        .withHoverEvent(Mc.showText(t("almighty.prompt.exact", lang, p.text)))));
        if (!noClicks) return;
        c.append(t("almighty.prompt.typed", lang, confirm, deny, show).copy().withStyle(ChatFormatting.DARK_GRAY))
                .append(" ").append(t("almighty.prompt.exact", lang, p.text).copy().withStyle(ChatFormatting.GRAY));
    }

    /** {@code /almighty show <nonce>}: the exact call written out, for clients without hover (Bedrock) and the console. */
    public static Component show(Confirmations.Pending p, String lang) {
        return prefix().append(what(p.summary, p.facts, p.text, lang, true)).append("\n")
                .append(t("almighty.prompt.exact", lang, p.text).copy().withStyle(ChatFormatting.GRAY));
    }

    /**
     * An admin request put straight to an admin online: "[Almighty] Cloudien wants to “summary” (its own words) — what
     * runs: … (for Alex, with your rights) [Allow] [Deny] [Details]"; {@code noClicks}: the typed commands and the
     * exact call written out after it.
     */
    public static Component adminQuestion(Confirmations.Pending p, String lang, boolean noClicks) {
        MutableComponent c = prefix().append(t("almighty.prompt.wants", lang, name()).copy().withStyle(ChatFormatting.WHITE))
                .append(what(p.summary, p.facts, p.text, lang, true)).append(" ")
                .append((p.requester != null ? t("almighty.prompt.for_admin", lang, p.requesterName)
                        : t("almighty.prompt.for_admin_agent", lang, p.caller.name())).copy().withStyle(ChatFormatting.WHITE)).append(" ");
        buttons(c, p, lang, noClicks);
        return c;
    }

    /** One admin request in {@code /almighty inbox}, in the admin's language ({@code noClicks}: as {@link #adminQuestion}). */
    public static Component inboxLine(Confirmations.Pending p, String lang, boolean noClicks) {
        MutableComponent c = prefix().append((p.requester != null
                ? t("almighty.inbox.player", lang, p.requesterName, name()) : t("almighty.inbox.agent", lang, p.caller.name()))
                .copy().withStyle(ChatFormatting.WHITE))
                .append(what(p.summary, p.facts, p.text, lang, true))
                .append(Component.literal(p.count > 1 ? " (×" + p.count + ") " : " "));
        // The facts stay in the line; the exact call is on hover, behind [Details], or written out without clicks.
        buttons(c, p, lang, noClicks);
        return c;
    }

    /** The quiet digest: one line, never one per request. */
    public static Component digest(List<Confirmations.Pending> waiting, String lang) {
        return prefix().append(t("almighty.inbox.digest", lang, String.valueOf(waiting.size())).copy().withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/almighty inbox").withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(Mc.runCommand("/almighty inbox"))));
    }

    /** A plain server line to a player (receipts, outcomes). */
    public static Component line(String text) {
        return prefix().append(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    /** A translated server line to a player ({@code key} from the lang files, {@code arg} shown as is). */
    public static Component line(String key, String lang, String... args) {
        return prefix().append(t(key, lang, (Object[]) args).copy().withStyle(ChatFormatting.GRAY));
    }

    /** An outcome of a confirmation ("Not allowed: …"), naming the call as the question did. */
    public static Component outcome(String key, Confirmations.Pending p, String lang) {
        return prefix().append(t(key, lang, what(p.summary, p.facts, p.text, lang, false)).copy().withStyle(ChatFormatting.GRAY));
    }

    /**
     * A receipt with an undo that runs on click (lead's F10): "Done for you: “summary” [Undo]" (or the facts when
     * there is no summary), in the player's language; facts and the exact call on hover.
     */
    public static Component receipt(String summary, List<Facts.Fact> facts, String exact, Long job, String lang) {
        // After the fact the player reads their own words back: the summary alone, the server's facts and the exact
        // call on hover; without a summary the facts stand in the line.
        Component detail = Facts.component(facts, lang).append("\n").append(t("almighty.prompt.exact", lang, exact));
        MutableComponent what = summary != null
                ? quoted(summary, lang).withStyle(s -> s.withColor(ChatFormatting.AQUA).withHoverEvent(Mc.showText(detail)))
                : Facts.component(facts, lang).withStyle(s -> s.withColor(ChatFormatting.YELLOW)
                        .withHoverEvent(Mc.showText(t("almighty.prompt.exact", lang, exact))));
        MutableComponent c = prefix().append(t("almighty.receipt.done", lang, what).copy().withStyle(ChatFormatting.GRAY));
        if (job != null) c.append(t("almighty.receipt.undo", lang).copy().withStyle(s -> s.withColor(ChatFormatting.GOLD)
                .withClickEvent(Mc.runCommand("/almighty undo " + job))
                .withHoverEvent(Mc.showText(Component.literal("/almighty undo " + job)))));
        return c;
    }
}
