package ua.zentix.almighty.bridge;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-agent call rate (plan S6, F2, F14): at most {@code perMinute} calls of one agent in any 60 s; more get 429 with
 * the seconds until a call is free again. Refused calls are not counted, so a client that backs off recovers. Memory
 * per agent is bounded by the limit. In-process callers (GameTest, the server) are never limited. Thread-safe.
 */
public final class CallRate {
    static final long WINDOW_MS = 60_000;

    private final LongSupplier clockMillis;
    private final Map<String, ArrayDeque<Long>> calls = new ConcurrentHashMap<>();

    public CallRate(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Count one call of {@code agent}; 429 when it already made {@code perMinute} in the last minute (0: no limit). */
    public void charge(String agent, long perMinute) throws RpcException {
        if (perMinute <= 0) return;
        ArrayDeque<Long> times = calls.computeIfAbsent(agent, a -> new ArrayDeque<>());
        long wait;
        synchronized (times) {
            long now = clockMillis.getAsLong();
            while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MS) times.pollFirst();
            while (times.size() > perMinute) times.pollFirst();
            if (times.size() < perMinute) {
                times.addLast(now);
                return;
            }
            wait = Math.max(1, (WINDOW_MS - (now - times.peekFirst()) + 999) / 1000);
        }
        throw new RpcException(429, "rate", "Over " + perMinute + " calls a minute for agent " + agent
                + " ([quotas] calls_per_minute): wait " + wait + " s, and look for a loop that repeats a call");
    }
}
