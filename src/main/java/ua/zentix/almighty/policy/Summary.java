package ua.zentix.almighty.policy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ua.zentix.almighty.feed.GmMessages;

import java.util.List;

/**
 * The agent's own short words for a call ({@code summary}, any method): shown to the player in quotes, marked as the
 * agent's words, always beside the server's {@link Facts}. Cleaned so it can never pass for the server's text: one
 * line of at most {@value #MAX} characters, no formatting codes, invisible or control characters, no brackets (no
 * fake {@code [Allow]} or {@code [Almighty]}), no quotes (it cannot close its own quote), and nothing that spells the
 * mod's name or its own labels in either language (folded as {@link Forgery} folds: look-alike letters, accents,
 * spacing); such a summary is dropped, and the server's facts alone are shown. Pure, any thread.
 */
public final class Summary {
    public static final int MAX = 80;
    public static final String PARAM = "summary";

    /** Lang keys whose text is the server's own voice in a prompt or receipt; a summary may not spell them. */
    private static final List<String> RESERVED_KEYS = List.of("almighty.prompt.what_runs", "almighty.prompt.its_words",
            "almighty.prompt.irreversible", "almighty.prompt.asked_by", "almighty.prompt.for_admin", "almighty.prompt.for_admin_agent");

    private Summary() {}

    /** The summary a call carries, cleaned; null when absent, empty or dropped. */
    public static String of(JsonObject params) {
        JsonElement e = params == null ? null : params.get(PARAM);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
        return clean(e.getAsString());
    }

    /** Why a summary is dropped (it spells the server's own text), or null when it may be shown (after {@link #clean}). */
    static String forged(String text) {
        String letters = letters(Forgery.fold(Forgery.decode(text)));
        if (letters.contains("almighty")) return "it spells the server's name";
        for (String key : RESERVED_KEYS) {
            for (String lang : List.of("en_us", "ru_ru")) {
                String label = letters(Forgery.fold(GmMessages.raw(key, lang).replaceAll("%(\\d+\\$)?s", " ")));
                if (!label.isEmpty() && letters.contains(label)) return "it spells the server's own words";
            }
        }
        return null;
    }

    /** One plain line, at most {@value #MAX} characters; null when nothing is left or it spells the server's text. */
    public static String clean(String raw) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), 4 * MAX));
        boolean space = false;
        for (int i = 0; i < raw.length() && out.length() < 4 * MAX; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            // A formatting code is the sign and the letter after it (§c): both go.
            if (cp == '§') {
                if (i < raw.length()) i += Character.charCount(raw.codePointAt(i));
                continue;
            }
            if (type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.UNASSIGNED || "[]{}<>\"'`“”„‟«»‹›＂＇［］｛｝＜＞〈〉《》「」『』【】".indexOf(cp) >= 0) continue;
            if (Character.isWhitespace(cp) || Character.isISOControl(cp) || Character.isSpaceChar(cp)) {
                space = true;
                continue;
            }
            if (space && !out.isEmpty()) out.append(' ');
            space = false;
            out.appendCodePoint(cp);
        }
        String s = out.toString();
        if (s.codePointCount(0, s.length()) > MAX) s = s.substring(0, s.offsetByCodePoints(0, MAX - 1)).strip() + "…";
        if (s.isEmpty() || forged(s) != null) return null;
        return s;
    }

    /** Every letter of folded text (Latin, Cyrillic, any script), nothing between them. */
    private static String letters(String folded) {
        StringBuilder out = new StringBuilder(folded.length());
        folded.codePoints().filter(Character::isLetter).forEach(out::appendCodePoint);
        return out.toString();
    }
}
