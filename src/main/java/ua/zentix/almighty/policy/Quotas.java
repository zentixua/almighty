package ua.zentix.almighty.policy;

import ua.zentix.almighty.bridge.RpcException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-player quotas over a sliding hour (plan 4.3 {@code almighty.quota.<counter>}): how much one player's requests
 * may take. Charged before the work runs; a request over the quota is refused (429) and nothing is charged.
 * Thread-safe.
 */
public final class Quotas {
    static final long HOUR_MS = 3_600_000;

    private record Use(long time, long amount) {}

    private final LongSupplier clockMillis;
    private final Map<String, Deque<Use>> uses = new ConcurrentHashMap<>();

    public Quotas(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Charge {@code amount} of {@code counter} to {@code player}, at most {@code perHour}; 429 if it would exceed it. */
    public void charge(UUID player, String counter, long amount, long perHour) throws RpcException {
        charge(player.toString(), counter, amount, perHour);
    }

    /**
     * Charge to {@code who}: a player's UUID, or {@code agent:<name>} for the agent's own initiative (its calls
     * without a ticket are charged too, or dropping the ticket would lift the quota).
     */
    public void charge(String who, String counter, long amount, long perHour) throws RpcException {
        RpcException[] over = new RpcException[1];
        // Atomic per key with prune(): a window pruned while being charged would lose the charge.
        uses.compute(who + "/" + counter, (k, d) -> {
            if (d == null) d = new ArrayDeque<>();
            long now = clockMillis.getAsLong();
            while (!d.isEmpty() && now - d.peekFirst().time >= HOUR_MS) d.pollFirst();
            long used = 0;
            for (Use u : d) used += u.amount;
            if (used + amount > perHour) {
                over[0] = new RpcException(429, "quota", "Over the hourly quota " + counter + " (" + used + " of " + perHour
                        + " used in the last hour)");
            } else {
                d.addLast(new Use(now, amount));
            }
            return d;
        });
        if (over[0] != null) throw over[0];
    }

    /** Forget windows with nothing in the last hour. */
    public void prune() {
        long now = clockMillis.getAsLong();
        for (String key : uses.keySet()) {
            uses.computeIfPresent(key, (k, d) -> d.isEmpty() || now - d.peekLast().time >= HOUR_MS ? null : d);
        }
    }
}
