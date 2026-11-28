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
 * text after undoing every escape ({@code \}u, {@code \}U, {@code \}x, {@code \}N{…}, at any depth), folding case,
 * accents and look-alike letters, and refuses below owner:
 * <ul>
 * <li>click actions other than {@code open_url}, {@code copy_to_clipboard} and {@code change_page}
 * ({@code run_command}, {@code suggest_command}, {@code show_dialog}, a {@code custom} action, dialog action types);</li>
 * <li>nbt text with {@code interpret}: it renders clicks stored in the world (a book a player wrote);</li>
 * <li>the {@code dialog} command;</li>
 * <li>translation keys and fallbacks with format codes ({@code "translate":"%2$s%1$s"}): they reorder parts unseen;</li>
 * <li>text parts that show names or data the world holds rather than the command: a {@code selector} that can match
 * non-players ({@code @e}, {@code @n}, a {@code type} other than player; a named mob or item can be called
 * "[Almighty] Allow"), and {@code nbt} parts (an entity's, a block's or storage's data, a sign's text). Player
 * selectors stay: account names hold only letters, digits and underscores;</li>
 * <li>the name {@code almighty} in the command's letters, or in the letters its text parts show once keys and the
 * values of fields never shown (colour, selector, score, nbt …) are taken out.</li>
 * </ul>
 * An accident and forgery guard on text the agent sends, deliberately strict: ordinary building, give and chat
 * commands never spell these. Pure.
 */
public final class Forgery {
    public static final String PREFIX = "[almighty]";
    private static final Pattern ESCAPE = Pattern.compile("\\\\+(?:u([0-9a-fA-F]{4})|U([0-9a-fA-F]{8})|x([0-9a-fA-F]{2})|N\\{([^}]{1,64})})");
    private static final Pattern TRANSLATE_FORMAT = Pattern.compile("(translate|fallback)\\W{0,4}:\\W{0,4}[^,}\\]]*%");
    private static final Pattern CUSTOM = Pattern.compile("(action|type)\\W{0,4}:\\W{0,4}(minecraft:)?custom\\b");
    private static final Pattern INTERPRET = Pattern.compile("interpret\\W{0,4}:\\W{0,4}(true|1b?\\b)");
    private static final Pattern ROOT = Pattern.compile("^(minecraft:)?dialog\\b");
    /** A selector text part and its pattern (JSON or SNBT, any quoting). */
    private static final Pattern SELECTOR_PART = Pattern.compile("selector\\W{0,4}:\\W{0,4}(@[a-z](?:\\[[^\\]]*])?)");
    private static final Pattern NBT_PART = Pattern.compile("(?<![a-z0-9_])\\W?nbt\\W{0,4}:");
    private static final Pattern PLAYER_TYPE = Pattern.compile("type\\s*=\\s*(minecraft:)?player\\b");
    /** Component fields whose values are never shown as the text itself: removed, key and value, before reading letters. */
    private static final Pattern HIDDEN_PAIR = Pattern.compile("(?<![a-z0-9_])[\"']?(color|font|shadow_color|insertion|selector|separator|keybind|objective|"
            + "name|nbt|entity|block|storage|source|sprite|atlas|action|type|id|url|value|command|dialog|hover_event|hoverevent)[\"']?\\s*:\\s*"
            + "(\"(?:[^\"]|\"\")*\"|'(?:[^']|'')*'|\\{[^{}]*}|[^,}\\]\\s]*)");
    /** Any key: removed (its name is not shown text), its value stays. */
    private static final Pattern KEY = Pattern.compile("[\"']?[a-z_][a-z0-9_]*[\"']?\\s*:");
    /** Letters that look like Latin ones in chat fonts (Cyrillic, Greek, IPA, small capitals): folded first. */
    private static final Map<Character, Character> LOOKALIKE = lookalikes(
            "аa еe оo рp сc уy хx іi јj һh ԁd ӏl мm тt гr үy ӯy ԛq ԝw ѕs ꙇi "
            + "αa οo ιi τt νv κk ρp εe ηn υu χx "
            + "ɑa ɡg ıi ℓl ɩi ʜh ɢg ʟl ʀr ʏy ɴn ɪi ᴀa ʙb ᴄc ᴅd ᴇe ғf ᴊj ᴋk ᴍm ᴏo ᴘp ǫq ꜱs ᴛt ᴜu ᴠv ᴡw ᴢz ցg");

    private Forgery() {}

    /** Why {@code command} may not run below owner, or null. */
    public static String refusal(String command) {
        String text = fold(decode(command));
        for (String seg : DenyList.segments(text)) {
            if (ROOT.matcher(trim(seg)).find()) return "Below owner, commands may not show dialogs (dialog)";
        }
        String flat = text.replace("\\", "");
        if (flat.contains("run_command") || flat.contains("suggest_command") || flat.contains("show_dialog")
                || flat.contains("dynamic/") || CUSTOM.matcher(flat).find()) {
            return "Below owner, commands may not carry clicks that act as whoever clicks (run_command, suggest_command, show_dialog,"
                    + " custom); open_url, copy_to_clipboard and change_page are fine";
        }
        if (INTERPRET.matcher(flat).find()) return "Below owner, nbt text may not be interpreted (interpret: true renders stored clicks)";
        if (TRANSLATE_FORMAT.matcher(flat).find()) return "Below owner, translation keys may not carry format codes (%)";
        String shows = worldText(flat);
        if (shows != null) return shows;
        if (names(flat)) return "Below owner, commands may not carry [Almighty] (reserved for the server's own messages)";
        return null;
    }

    /** Why a text part would show what the world holds (a mob's name, stored nbt), or null. */
    private static String worldText(String flat) {
        if (NBT_PART.matcher(flat).find()) {
            return "Below owner, text may not show stored nbt (an entity's, block's or storage's data can spell [Almighty]); write the text itself";
        }
        Matcher m = SELECTOR_PART.matcher(flat);
        while (m.find()) {
            String sel = m.group(1);
            char kind = sel.charAt(1);
            boolean typed = sel.contains("type"), player = PLAYER_TYPE.matcher(sel).find();
            // @a, @p, @r select players; @s whoever runs the command (a mob under execute as @e) unless typed player.
            boolean players = kind == 'a' || kind == 'p' || kind == 'r' || kind == 's' && player;
            if (!players || typed && !player) {
                return "Below owner, text may show selected players only (a mob's or item's name can spell [Almighty]): use @a, @p or @r,"
                        + " or a name";
            }
        }
        return null;
    }

    /** Whether shown text (a say line, a rule's say) passes for the server's prefix, folded as {@link #refusal} does. */
    public static boolean prefix(String shown) {
        return letters(fold(decode(shown))).contains("almighty");
    }

    /** The name, in all the letters, or in the letters a component shows (keys and their hidden values taken out). */
    private static boolean names(String flat) {
        if (letters(flat).contains("almighty")) return true;
        String shown = KEY.matcher(HIDDEN_PAIR.matcher(flat).replaceAll(" ")).replaceAll(" ");
        return letters(shown).contains("almighty");
    }

    private static Map<Character, Character> lookalikes(String pairs) {
        Map<Character, Character> m = new java.util.HashMap<>();
        for (String p : pairs.trim().split("\\s+")) {
            for (int i = 0; i + 1 < p.length(); i += 2) m.put(p.charAt(i), p.charAt(i + 1));
        }
        return Map.copyOf(m);
    }

    /** Lower case, accents dropped, invisible format characters (soft hyphen, zero-width) removed, look-alikes made Latin. */
    static String fold(String s) {
        String plain = Normalizer.normalize(s, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char c = Character.toLowerCase(plain.charAt(i));
            int type = Character.getType(c);
            if (type == Character.FORMAT || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) continue;
            Character latin = LOOKALIKE.get(c);
            out.append(latin != null ? latin : c);
        }
        return out.toString();
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

    /** Only the Latin letters of folded text: everything between them gone. */
    private static String letters(String folded) {
        StringBuilder out = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (c >= 'a' && c <= 'z') out.append(c);
        }
        return out.toString();
    }

    /** As the game trims a command line ({@code String.trim}: every character up to a space), and Unicode spaces too. */
    public static String trim(String s) {
        return s.strip().trim().strip();
    }
}
