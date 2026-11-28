package ua.zentix.almighty.feed;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How often a player hears each server notice about the game master ("away", "paused"): at most once per
 * {@code everyMs} each, counted separately, so an earlier "away" never hides a later pause (lead's N2). Server thread.
 */
final class Notices {
    private final long everyMs;
    private final Map<UUID, Map<String, Long>> told = new HashMap<>();

    Notices(long everyMs) {
        this.everyMs = everyMs;
    }

    /** Whether the player should hear this notice now; if so, it counts as told at {@code now}. */
    boolean due(UUID player, String key, long now) {
        Map<String, Long> mine = told.computeIfAbsent(player, p -> new HashMap<>());
        Long last = mine.get(key);
        if (last != null && now - last < everyMs) return false;
        mine.put(key, now);
        return true;
    }

    /** When the player last heard this notice ({@code key} null: any), 0 if never. */
    long at(UUID player, String key) {
        Map<String, Long> mine = told.get(player);
        if (mine == null) return 0;
        if (key != null) return mine.getOrDefault(key, 0L);
        return mine.values().stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /** Forget notices older than the interval. */
    void prune(long now) {
        told.values().removeIf(mine -> {
            mine.values().removeIf(t -> now - t >= everyMs);
            return mine.isEmpty();
        });
    }
}
