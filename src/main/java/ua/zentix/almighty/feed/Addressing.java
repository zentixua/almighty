package ua.zentix.almighty.feed;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which player messages are addressed to the game master, decided by the server (plan 4.6; the logic of the Claude
 * channel's {@code router.mjs}, now for every harness): {@code /gm} always; chat that names the GM (a word equal to a
 * name, or to a long name with one typo), chat in an open conversation (for {@value #CONVERSATION_MS} ms after the
 * player addressed the GM or got a reply), and chat from a player alone on the server. Also the per-player message
 * limit ({@code feed.gm_messages} per hour), enforced before an event is minted, so floods never reach the agent.
 * Server thread only.
 */
public final class Addressing {
    public static final long CONVERSATION_MS = 180_000;
    static final long HOUR_MS = 3_600_000;

    public enum Via { GM, NAME, CONVERSATION, ALONE }

    private final List<String> names;
    private final List<String> typoNames;
    private final LongSupplier clockMillis;
    private final Map<UUID, Long> talked = new HashMap<>();
    private final Map<UUID, Deque<Long>> sent = new HashMap<>();
    private final Map<UUID, Deque<Long>> implicit = new HashMap<>();
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    public Addressing(Collection<String> names, Collection<String> typoNames, LongSupplier clockMillis) {
        // Blank names (a new list entry in a config editor) would match nothing or throw: dropped.
        this.typoNames = typoNames.stream().map(Addressing::fold).filter(n -> !n.isBlank()).toList();
        this.names = java.util.stream.Stream.concat(names.stream().map(Addressing::fold).filter(n -> !n.isBlank()),
                this.typoNames.stream()).toList();
        this.clockMillis = clockMillis;
    }

    /** How a chat line reaches the GM, or null if it does not. */
    public Via chat(UUID player, String text, boolean alone) {
        if (addressed(text, names, typoNames)) return Via.NAME;
        Long since = talked.get(player);
        if (since != null && clockMillis.getAsLong() - since < CONVERSATION_MS) return Via.CONVERSATION;
        return alone ? Via.ALONE : null;
    }

    /** The player addressed the GM: their conversation (re)opens. */
    public void opened(UUID player) {
        talked.put(player, clockMillis.getAsLong());
    }

    /** The GM replied to these players: their conversations (re)open. */
    public void replied(Collection<UUID> players) {
        long now = clockMillis.getAsLong();
        for (UUID p : players) talked.put(p, now);
    }

    public void forget(UUID player) {
        talked.remove(player);
    }

    /** Count one addressed message; false if the player is over {@code perHour} in the last hour (nothing is counted). */
    public boolean allow(UUID player, int perHour) {
        return count(sent, player, perHour);
    }

    /** The separate, larger limit for implicit lines (a solo player's chat, an open conversation). */
    public boolean allowImplicit(UUID player, int perHour) {
        return count(implicit, player, perHour);
    }

    /** Forget counters with nothing in the last hour (they cannot affect a limit any more). */
    public void prune() {
        long now = clockMillis.getAsLong();
        sent.values().removeIf(d -> d.isEmpty() || now - d.peekLast() >= HOUR_MS);
        implicit.values().removeIf(d -> d.isEmpty() || now - d.peekLast() >= HOUR_MS);
        talked.values().removeIf(t -> now - t >= CONVERSATION_MS);
    }

    int tracked() {
        return sent.size() + implicit.size();
    }

    private boolean count(Map<UUID, Deque<Long>> table, UUID player, int perHour) {
        long now = clockMillis.getAsLong();
        Deque<Long> times = table.computeIfAbsent(player, p -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= HOUR_MS) times.pollFirst();
        if (times.size() >= perHour) return false;
        times.addLast(now);
        return true;
    }

    /** Normalized word: lowercase, ё → е, repeated letters folded ("Клооод" → "клод"). */
    static String fold(String word) {
        return word.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е').replaceAll("(\\p{L})\\1+", "$1");
    }

    /** Damerau-Levenshtein distance at most 1. */
    static boolean near(String a, String b) {
        if (a.equals(b)) return true;
        if (Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        if (a.length() > b.length()) return a.substring(i + 1).equals(b.substring(i));
        if (a.length() < b.length()) return a.substring(i).equals(b.substring(i + 1));
        return a.substring(i + 1).equals(b.substring(i + 1))
                || (i + 1 < a.length() && a.charAt(i) == b.charAt(i + 1) && a.charAt(i + 1) == b.charAt(i)
                && a.substring(i + 2).equals(b.substring(i + 2)));
    }

    static boolean addressed(String text, List<String> names, List<String> typoNames) {
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String w = fold(m.group());
            if (names.contains(w)) return true;
            for (String n : typoNames) if (!w.isEmpty() && w.charAt(0) == n.charAt(0) && near(w, n)) return true;
        }
        return false;
    }
}
