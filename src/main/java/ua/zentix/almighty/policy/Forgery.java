package ua.zentix.almighty.policy;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a command below owner may not carry (plan 4.8): a click that acts as whoever clicks it, or text that passes
 * for the server's own {@code [Almighty]} prompts. A forged "[Almighty] … [Allow]" in tellraw, a sign, a book or a
 * dialog would run as the admin who clicks it. Checked on the text after undoing the ways a command can spell the
 * same thing differently: {@code \}u escapes (JSON and SNBT), the prefix split across text parts
 * ({@code ["[Almi","ghty]"]}), any letter case. The click actions allowed below owner are {@code open_url},
 * {@code copy_to_clipboard} and {@code change_page}; {@code run_command}, {@code suggest_command},
 * {@code show_dialog} and {@code custom} (and dialog actions) are not, nor the {@code dialog} command. Pure.
 */
public final class Forgery {
    public static final String PREFIX = "[almighty]";
    private static final Pattern UNICODE = Pattern.compile("\\\\+u([0-9a-fA-F]{4})");
    private static final Pattern ACTIONS = Pattern.compile("run_command|suggest_command|show_dialog|dynamic/|action[\"']?\\s*:\\s*[\"']?(minecraft:)?custom\\b");
    private static final Pattern ROOT = Pattern.compile("^(minecraft:)?dialog\\b");

    private Forgery() {}

    /** Why {@code command} may not run below owner, or null. */
    public static String refusal(String command) {
        String text = decode(command).toLowerCase(Locale.ROOT);
        for (String seg : DenyList.segments(text)) {
            if (ROOT.matcher(seg.strip()).find()) return "Below owner, commands may not show dialogs (dialog)";
        }
        if (ACTIONS.matcher(text).find()) {
            return "Below owner, commands may not carry clicks that act as whoever clicks (run_command, suggest_command, show_dialog,"
                    + " custom); open_url, copy_to_clipboard and change_page are fine";
        }
        if (text.contains(PREFIX) || literals(text).contains(PREFIX) || squeezed(text).contains("[almighty]")) {
            return "Below owner, commands may not carry [Almighty] (reserved for the server's own messages)";
        }
        return null;
    }

    /** {@code \}u escapes undone, however many backslashes quote them (text inside SNBT inside JSON). */
    static String decode(String s) {
        String out = s;
        for (int pass = 0; pass < 4; pass++) {
            Matcher m = UNICODE.matcher(out);
            if (!m.find()) break;
            StringBuilder b = new StringBuilder();
            m.reset();
            while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
            m.appendTail(b);
            out = b.toString();
        }
        return out;
    }

    /** The quoted strings of a command that are values, not keys, joined: what text components would show. */
    static String literals(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char q = s.charAt(i);
            if (q != '"' && q != '\'') {
                i++;
                continue;
            }
            StringBuilder lit = new StringBuilder();
            int j = i + 1;
            while (j < n && s.charAt(j) != q) {
                if (s.charAt(j) == '\\' && j + 1 < n) j++;
                lit.append(s.charAt(j++));
            }
            int k = j + 1;
            while (k < n && Character.isWhitespace(s.charAt(k))) k++;
            boolean key = k < n && s.charAt(k) == ':';
            if (!key && !NOT_SHOWN.contains(keyBefore(s, i))) out.append(lit);
            i = j + 1;
        }
        return out.toString();
    }

    /** Component fields whose values are not shown as text: they would split a forged prefix's parts. */
    private static final java.util.Set<String> NOT_SHOWN = java.util.Set.of("color", "font", "shadow_color", "insertion", "action",
            "type", "id", "url", "value", "command", "dialog", "nbt", "block", "entity", "storage", "source", "separator", "sprite", "atlas");

    /** The key a value at {@code at} belongs to ({@code "color": "gold"}, SNBT {@code color:"gold"}), or "". */
    private static String keyBefore(String s, int at) {
        int k = at - 1;
        while (k >= 0 && Character.isWhitespace(s.charAt(k))) k--;
        if (k < 0 || s.charAt(k) != ':') return "";
        k--;
        while (k >= 0 && Character.isWhitespace(s.charAt(k))) k--;
        if (k < 0) return "";
        int end = k + 1;
        if (s.charAt(k) == '"' || s.charAt(k) == '\'') {
            char q = s.charAt(k);
            int start = s.lastIndexOf(q, k - 1);
            return start < 0 ? "" : s.substring(start + 1, k);
        }
        while (k >= 0 && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) k--;
        return s.substring(k + 1, end);
    }

    /** Only letters and brackets: "[ Almi" + "ghty ]" spelled with spaces, quotes or JSON between the parts. */
    private static String squeezed(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c) || c == '[' || c == ']') out.append(c);
        }
        return out.toString();
    }
}
