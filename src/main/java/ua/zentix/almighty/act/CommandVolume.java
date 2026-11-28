package ua.zentix.almighty.act;

import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.policy.DenyList;

import java.util.List;
import java.util.Locale;

/**
 * The block volume one {@code command} or {@code function} call may write with {@code fill}, {@code clone} and
 * {@code fillbiome} (plan 4.11, F3): those run in one go on the server thread, so past {@value #MAX_PER_CALL}
 * positions a call is refused with "use build", which runs in portions between ticks. A box whose size cannot be
 * read from the text (local {@code ^} coordinates, macros, mixed absolute and relative corners) counts as vanilla's
 * largest, 32 768. Pure: unit-tested. Below owner, a fill, clone or fillbiome under an {@code execute} that repeats
 * it for an unknown number of entities ({@code as @e}, {@code at @a}, {@code on passengers} …, without
 * {@code limit=1}) is refused: its volume cannot be bounded from the text. The owner's such commands count once.
 */
public final class CommandVolume {
    /** Vanilla's own limit for one fill or clone ({@code commandModificationBlockLimit}). */
    static final long VANILLA_MAX = 32_768;
    public static final long MAX_PER_CALL = 4 * VANILLA_MAX;

    private CommandVolume() {}

    /** Execute modifiers that run the rest once per matched entity, with the selector they match. */
    private static final java.util.regex.Pattern FAN_OUT = java.util.regex.Pattern.compile(
            "\\b(?:as|at|positioned\\s+as|rotated\\s+as|facing\\s+entity)\\s+(@[aesprn])(\\[[^\\]]*\\])?", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern MANY_RELATION = java.util.regex.Pattern.compile("\\bon\\s+passengers\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern LIMIT = java.util.regex.Pattern.compile("\\blimit\\s*=\\s*(\\d+)");
    private static final java.util.regex.Pattern RUN = java.util.regex.Pattern.compile("\\brun\\s+");

    public static void check(List<String> commands) throws RpcException {
        check(commands, true);
    }

    /** {@code owner}: the call's effective role is the owner (an execute's repeats are not counted). */
    public static void check(List<String> commands, boolean owner) throws RpcException {
        long total = 0;
        for (String c : commands) {
            if (!owner && repeatsWrites(c)) {
                throw RpcException.badRequest("This command would fill or clone once per matched entity (execute as/at @e or @a"
                        + " without limit=1), which cannot be bounded: use build, or name one target");
            }
            for (String seg : DenyList.segments(c)) total += volume(seg);
            if (total > MAX_PER_CALL) {
                throw RpcException.badRequest("This call would fill or clone over " + MAX_PER_CALL + " blocks in one tick: use build,"
                        + " which works in portions between ticks (and keeps an undo)");
            }
        }
    }

    /** Whether a fill, clone or fillbiome in this command runs under an execute that repeats for many entities. */
    static boolean repeatsWrites(String command) {
        List<String> segs = DenyList.segments(command);
        if (segs.size() < 2) return false;
        // The execute text before each run, joined: a repeat anywhere before a write multiplies it.
        String[] parts = RUN.split(segs.get(0), -1);
        StringBuilder before = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0 && volume(parts[i]) > 0 && many(before)) return true;
            before.append(parts[i]).append(' ');
        }
        return false;
    }

    private static boolean many(CharSequence executeText) {
        if (MANY_RELATION.matcher(executeText).find()) return true;
        java.util.regex.Matcher m = FAN_OUT.matcher(executeText);
        while (m.find()) {
            String kind = m.group(1).toLowerCase(java.util.Locale.ROOT);
            if (kind.equals("@s")) continue;
            java.util.regex.Matcher limit = m.group(2) == null ? null : LIMIT.matcher(m.group(2));
            String n = limit != null && limit.find() ? limit.group(1) : null;
            // @a and @e match any number unless limit=1; @p, @r and @n match one unless a larger limit.
            boolean single = kind.equals("@a") || kind.equals("@e") ? "1".equals(n) : n == null || n.equals("1") || n.equals("0");
            if (!single) return true;
        }
        return false;
    }

    /** Positions one command segment writes with fill, clone or fillbiome; 0 for any other command. */
    static long volume(String segment) {
        String[] t = segment.strip().split("\\s+");
        if (t.length == 0) return 0;
        String root = t[0].toLowerCase(Locale.ROOT);
        if (root.startsWith("minecraft:")) root = root.substring("minecraft:".length());
        if (!root.equals("fill") && !root.equals("clone") && !root.equals("fillbiome")) return 0;
        int i = 1;
        // clone from <dimension> <begin> <end> …
        if (root.equals("clone") && t.length > i && t[i].equalsIgnoreCase("from")) i += 2;
        if (t.length < i + 6) return VANILLA_MAX;
        long v = 1;
        for (int a = 0; a < 3; a++) {
            long d = span(t[i + a], t[i + 3 + a]);
            if (d < 0) return VANILLA_MAX;
            // The real size: an owner may have raised vanilla's limit (gamerule commandModificationBlockLimit).
            v = (long) Math.min((double) v * d, 1e15);
        }
        return v;
    }

    /** Positions along one axis between two coordinates of the same kind; -1 when it cannot be read. */
    private static long span(String from, String to) {
        boolean relFrom = from.startsWith("~"), relTo = to.startsWith("~");
        if (from.startsWith("^") || to.startsWith("^") || relFrom != relTo) return -1;
        try {
            double a = number(relFrom ? from.substring(1) : from), b = number(relTo ? to.substring(1) : to);
            return (long) Math.floor(Math.abs(Math.floor(a) - Math.floor(b))) + 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double number(String s) {
        return s.isEmpty() ? 0 : Double.parseDouble(s);
    }
}
