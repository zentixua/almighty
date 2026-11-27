package ua.zentix.almighty.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lag guard: on after 10 s above 50 mspt, off after 10 s below; short spikes change nothing. */
class LagGuardTest {
    @Test
    void holdsForTenSeconds() {
        LagGuard g = new LagGuard();
        long t = 0;
        for (; t < LagGuard.HOLD_MS; t += 50) assertFalse(g.update(80, t), "not yet at " + t);
        assertTrue(g.update(80, t));
        assertTrue(g.lagging());
        // A short dip does not release it.
        g.update(20, t += 50);
        g.update(80, t += 50);
        assertTrue(g.lagging());
        long start = t;
        for (t += 50; t - start <= LagGuard.HOLD_MS + 50; t += 50) g.update(20, t);
        assertFalse(g.lagging());
    }

    @Test
    void spikesIgnored() {
        LagGuard g = new LagGuard();
        for (long t = 0; t < 60_000; t += 50) g.update(t % 5000 < 4000 ? 80 : 10, t);
        assertFalse(g.lagging(), "lag that never lasts 10 s straight does not trip the guard");
    }
}
