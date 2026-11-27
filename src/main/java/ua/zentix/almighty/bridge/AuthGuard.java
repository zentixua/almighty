package ua.zentix.almighty.bridge;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Failed-authentication backoff per remote address: after {@value #FREE_FAILURES} failures within a minute the address
 * is locked out (answered 429 without checking any token) for 5 s, doubling with each further lockout up to 5
 * minutes. A successful call clears the address. Bounded: at most {@value #MAX_ADDRESSES} addresses are tracked.
 */
final class AuthGuard {
    static final int FREE_FAILURES = 5;
    static final long WINDOW_MS = 60_000;
    static final long FIRST_LOCK_MS = 5_000;
    static final long MAX_LOCK_MS = 300_000;
    static final int MAX_ADDRESSES = 4096;

    private final LongSupplier clockMillis;
    private final Map<String, State> byAddress = new ConcurrentHashMap<>();

    AuthGuard(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    private static final class State {
        int failures;
        long windowStart;
        int lockouts;
        long lockedUntil;
        long lastFailure;
    }

    /** Milliseconds the address is still locked out for; 0 if it may try. */
    long lockedFor(String address) {
        State s = byAddress.get(address);
        if (s == null) return 0;
        synchronized (s) {
            return Math.max(0, s.lockedUntil - clockMillis.getAsLong());
        }
    }

    /** Record a failure; returns the lockout length in ms if this failure started one, else 0. */
    long failure(String address) {
        long now = clockMillis.getAsLong();
        if (byAddress.size() >= MAX_ADDRESSES) prune(now);
        State s = byAddress.computeIfAbsent(address, a -> new State());
        synchronized (s) {
            if (now - s.windowStart > WINDOW_MS) {
                s.windowStart = now;
                s.failures = 0;
            }
            // Lockouts are forgiven after a quiet period as long as the longest lockout.
            if (s.lockouts > 0 && now - s.lastFailure > MAX_LOCK_MS * 2) s.lockouts = 0;
            s.lastFailure = now;
            if (++s.failures < FREE_FAILURES) return 0;
            s.failures = 0;
            long lock = Math.min(MAX_LOCK_MS, FIRST_LOCK_MS << Math.min(s.lockouts, 16));
            s.lockouts++;
            s.lockedUntil = now + lock;
            return lock;
        }
    }

    /** First failure of a window: worth one log line (later ones are summarized by the lockout line). */
    boolean firstInWindow(String address) {
        State s = byAddress.get(address);
        if (s == null) return true;
        synchronized (s) {
            return s.failures == 1;
        }
    }

    int tracked() {
        return byAddress.size();
    }

    void success(String address) {
        byAddress.remove(address);
    }

    private void prune(long now) {
        for (Iterator<State> it = byAddress.values().iterator(); it.hasNext(); ) {
            State s = it.next();
            synchronized (s) {
                if (s.lockedUntil < now && now - s.lastFailure > WINDOW_MS) it.remove();
            }
        }
        // Still full (an address-rotating flood): forget everything rather than grow without bound.
        if (byAddress.size() >= MAX_ADDRESSES) byAddress.clear();
    }
}
