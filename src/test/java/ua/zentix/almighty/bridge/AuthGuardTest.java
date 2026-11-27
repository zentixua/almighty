package ua.zentix.almighty.bridge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Failed-authentication backoff: free failures, doubling lockouts capped at 5 minutes, reset on success, per address. */
class AuthGuardTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AuthGuard guard = new AuthGuard(now::get);

    private long fail(String address, int times) {
        long lock = 0;
        for (int i = 0; i < times; i++) lock = guard.failure(address);
        return lock;
    }

    @Test
    void locksAfterFiveFailuresAndDoubles() {
        assertEquals(0, fail("1.2.3.4", 4));
        assertEquals(0, guard.lockedFor("1.2.3.4"));
        assertEquals(5_000, guard.failure("1.2.3.4"));
        assertEquals(5_000, guard.lockedFor("1.2.3.4"));
        assertEquals(0, guard.lockedFor("5.6.7.8"), "other addresses unaffected");
        now.addAndGet(5_000);
        assertEquals(0, guard.lockedFor("1.2.3.4"));
        assertEquals(10_000, fail("1.2.3.4", 5));
        long lock = 0;
        for (int i = 0; i < 10; i++) {
            now.addAndGet(lock);
            lock = fail("1.2.3.4", 5);
        }
        assertEquals(AuthGuard.MAX_LOCK_MS, lock, "capped");
    }

    @Test
    void slowFailuresNeverLock() {
        for (int i = 0; i < 20; i++) {
            guard.failure("1.2.3.4");
            now.addAndGet(AuthGuard.WINDOW_MS / 4 + 1);
        }
        assertEquals(0, guard.lockedFor("1.2.3.4"), "four per minute is below the limit");
    }

    @Test
    void successClears() {
        fail("1.2.3.4", 4);
        guard.success("1.2.3.4");
        assertEquals(0, fail("1.2.3.4", 4));
    }

    @Test
    void boundedMemory() {
        for (int i = 0; i < AuthGuard.MAX_ADDRESSES * 3; i++) guard.failure("10.0." + (i >> 8) + "." + (i & 255));
        assertTrue(guard.tracked() <= AuthGuard.MAX_ADDRESSES, "an address-rotating flood cannot grow the table");
    }
}
