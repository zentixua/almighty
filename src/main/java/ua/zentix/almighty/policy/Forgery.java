package ua.zentix.almighty.policy;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a command below owner may not carry (plan 4.8): a click that acts as whoever clicks it, or text that passes
 * for the server's own {@code [Almighty]} prompts. A forged "[Almighty] … [Allow]" in tellraw, a sign, a book or a
 * dialog would run as the admin who clicks it, or talk them into typing an approval.
 *
 * <p>Text components can spell the same thing many ways (JSON and SNBT escapes, nested quoting, parts split by
 * selectors, scores, translation keys or colours), so the check does not try to render them. It reads the command's
 * letters after undoing every escape ({@code \}u, {@code \}U, {@code \}x, {@code \}N{…}, at any depth), folding case,
 * accents and look-alike letters, and refuses below owner:
 * <ul>
 * <li>click actions other than {@code open_url}, {@code copy_to_clipboard} and {@code change_page}: the letters
 * {@code runcommand}, {@code suggestcommand}, {@code showdialog}, a {@code custom} action, dialog action types;</li>
 * <li>the {@code dialog} command;</li>
 * <li>translation keys with format codes ({@code "translate":"%2$s%1$s"}): they reorder parts unseen;</li>
 * <li>the name {@code almighty}, whole or as {@code almi} … {@code ghty} anywhere in the command.</li>
 * </ul>
 * An accident and forgery guard on text the agent sends, deliberately strict: ordinary building, give and chat
 * commands never spell these. Pure.
 */
public final class Forgery {
    public static final String PREFIX = "[almighty]";
    private static final Pattern ESCAPE = Pattern.compile("\\\\+(?:u([0-9a-fA-F]{4})|U([0-9a-fA-F]{8})|x([0-9a-fA-F]{2})|N\\{([^}]{1,64})})");
    private static final Pattern TRANSLATE_FORMAT = Pattern.compile("translate\\W{0,4}:\\W{0,4}[^,}\\]]*%");
    private static final Pattern CUSTOM = Pattern.compile("(action|type)\\W{0,4}:\\W{0,4}(minecraft:)?custom\\b");
    private static final Pattern ROOT = Pattern.compile("^(minecraft:)?dialog\\b");
    /** Letters that look like Latin ones in chat fonts (Cyrillic, Greek): folded before the name check. */
    private static final Map<Character, Character> LOOKALIKE = Map.ofEntries(
            Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'), Map.entry('с', 'c'),
            Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'), Map.entry('ј', 'j'), Map.entry('һ', 'h'),
            Map.entry('ԁ', 'd'), Map.entry('ӏ', 'l'), Map.entry('м', 'm'), Map.entry('т', 't'), Map.entry('г', 'r'),
            Map.entry('α', 'a'), Map.entry('ο', 'o'), Map.entry('ι', 'i'), Map.entry('τ', 't'), Map.entry('ɡ', 'g'),
            Map.entry('ı', 'i'), Map.entry('ℓ', 'l'), Map.entry('ӎ', 'm'), Map.entry('ɩ', 'i'), Map.entry('ʜ', 'h'));

    private Forgery() {}

    /** Why {@code command} may not run below owner, or null. */
    public static String refusal(String command) {
        String text = decode(command).toLowerCase(Locale.ROOT);
        for (String seg : DenyList.segments(text)) {
            if (ROOT.matcher(trim(seg)).find()) return "Below owner, commands may not show dialogs (dialog)";
        }
        String flat = text.replace("\\", "");
        String letters = letters(flat);
        if (letters.contains("runcommand") || letters.contains("suggestcommand") || letters.contains("showdialog")
                || flat.contains("dynamic/") || CUSTOM.matcher(flat).find()) {
            return "Below owner, commands may not carry clicks that act as whoever clicks (run_command, suggest_command, show_dialog,"
                    + " custom); open_url, copy_to_clipboard and change_page are fine";
        }
        if (TRANSLATE_FORMAT.matcher(flat).find()) return "Below owner, translation keys may not carry format codes (%)";
        if (names(letters)) return "Below owner, commands may not carry [Almighty] (reserved for the server's own messages)";
        return null;
    }

    /** Whether shown text (a say line, a rule's say) passes for the server's prefix, folded as {@link #refusal} does. */
    public static boolean prefix(String shown) {
        return names(letters(decode(shown).toLowerCase(Locale.ROOT)));
    }

    private static boolean names(String letters) {
        return letters.contains("almighty") || (letters.contains("almi") && letters.contains("ghty"));
    }

    /** Every escape undone, however many backslashes quote it (text inside SNBT inside JSON). */
    static String decode(String s) {
        String out = s;
        for (int pass = 0; pass < 6; pass++) {
            Matcher m = ESCAPE.matcher(out);
            if (!m.find()) break;
            StringBuilder b = new StringBuilder();
            m.reset();
            while (m.find()) {
                String cp;
                try {
                    String hex = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                    cp = new String(Character.toChars(hex != null ? Integer.parseInt(hex, 16) : Character.codePointOf(m.group(4))));
                } catch (IllegalArgumentException e) {
                    cp = "";
                }
                m.appendReplacement(b, Matcher.quoteReplacement(cp));
            }
            m.appendTail(b);
            out = b.toString();
        }
        return out;
    }

    /** Only letters, folded: accents dropped, look-alikes made Latin, everything between them gone. */
    private static String letters(String s) {
        String plain = Normalizer.normalize(s, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char c = Character.toLowerCase(plain.charAt(i));
            Character latin = LOOKALIKE.get(c);
            if (latin != null) out.append(latin);
            else if (c < 128 && Character.isLetter(c)) out.append(c);
        }
        return out.toString();
    }

    /** As the game trims a command line ({@code String.trim}: every character up to a space), and Unicode spaces too. */
    public static String trim(String s) {
        return s.strip().trim().strip();
    }
}
