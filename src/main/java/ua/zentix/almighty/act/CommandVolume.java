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
 * largest, 32 768. Pure: unit-tested. An accident guard: {@code execute as @e run fill …} repeats per entity unseen.
 */
public final class CommandVolume {
    /** Vanilla's own limit for one fill or clone ({@code commandModificationBlockLimit}). */
    static final long VANILLA_MAX = 32_768;
    public static final long MAX_PER_CALL = 4 * VANILLA_MAX;

    private CommandVolume() {}

    public static void check(List<String> commands) throws RpcException {
        long total = 0;
        for (String c : commands) {
            for (String seg : DenyList.segments(c)) total += volume(seg);
            if (total > MAX_PER_CALL) {
                throw RpcException.badRequest("This call would fill or clone over " + MAX_PER_CALL + " blocks in one tick: use build,"
                        + " which works in portions between ticks (and keeps an undo)");
            }
        }
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
