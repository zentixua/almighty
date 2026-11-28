package ua.zentix.almighty.world;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Land claims of installed claim mods, queried read-only through their own APIs (plan 4.7): a build below owner runs
 * only on chunks the requester may build in, and an unasked build only on unclaimed chunks. Firing gameplay events
 * instead was rejected: other mods act on them. Server thread.
 */
public final class Claims {
    /** One claim mod. */
    public interface Source {
        String name();

        /** Whether {@code player} (null: a stranger) may build in this chunk. */
        boolean mayBuild(UUID player, ServerLevel level, int chunkX, int chunkZ);
    }

    private static final List<Source> extra = new ArrayList<>();
    /** The most chunks one check visits (a build is bounded by its leases; a command's box is not). */
    static final long MAX_CHUNKS = 4096;
    private final List<Source> sources;

    public Claims(List<Source> sources) {
        this.sources = sources;
    }

    /** The claim mods this server checks builds against (status, the connection check). */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Source s : sources) out.add(s.name());
        return out;
    }

    /** Whether any claim mod (or a test source) is checked: commands below owner are then checked at their places. */
    public boolean active() {
        return !sources.isEmpty() || !extra.isEmpty();
    }

    /** GameTest: a source standing in for a claim mod. */
    public static void testSource(Source s) {
        extra.clear();
        if (s != null) extra.add(s);
    }

    /** The first source refusing, or null if every chunk of the box is open to {@code player}. */
    public String refusal(UUID player, ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        List<Source> all = new ArrayList<>(sources);
        all.addAll(extra);
        if (all.isEmpty()) return null;
        // A box too large to check chunk by chunk on the server thread (a command naming far coordinates) is refused.
        long chunks = ((long) (maxX >> 4) - (minX >> 4) + 1) * ((long) (maxZ >> 4) - (minZ >> 4) + 1);
        if (chunks > MAX_CHUNKS) return "the box spans " + chunks + " chunks: too large to check against claims (at most " + MAX_CHUNKS + ")";
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                for (Source s : all) {
                    if (!s.mayBuild(player, level, cx, cz)) {
                        return "chunk " + cx + ", " + cz + " is claimed (" + s.name() + ") by someone "
                                + (player == null ? "else: an unasked build only goes where nobody claims" : "the requester may not build for");
                    }
                }
            }
        }
        return null;
    }
}
