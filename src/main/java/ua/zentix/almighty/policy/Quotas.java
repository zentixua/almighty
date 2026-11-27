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
        Deque<Use> d = uses.computeIfAbsent(player + "/" + counter, k -> new ArrayDeque<>());
        synchronized (d) {
            long now = clockMillis.getAsLong();
            while (!d.isEmpty() && now - d.peekFirst().time >= HOUR_MS) d.pollFirst();
            long used = 0;
            for (Use u : d) used += u.amount;
            if (used + amount > perHour) {
                throw new RpcException(429, "quota", "Over the hourly quota " + counter + " (" + used + " of " + perHour
                        + " used in the last hour)");
            }
            d.addLast(new Use(now, amount));
        }
    }

    /** Forget windows with nothing in the last hour. */
    public void prune() {
        long now = clockMillis.getAsLong();
        uses.values().removeIf(d -> {
            synchronized (d) {
                return d.isEmpty() || now - d.peekLast().time >= HOUR_MS;
            }
        });
    }
}
