package ua.zentix.almighty.bridge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Per-agent calls a minute (plan F2, F14): a runaway loop gets 429; other agents and a backed-off client go on. */
class CallRateTest {
    @Test
    void limitsEachAgentWithinAMinute() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        CallRate rate = new CallRate(now::get);
        for (int i = 0; i < 5; i++) rate.charge("a", 5);
        RpcException over = assertThrows(RpcException.class, () -> rate.charge("a", 5));
        assertEquals(429, over.status());
        assertDoesNotThrow(() -> rate.charge("b", 5), "another agent is not limited by the first");
        now.addAndGet(30_000);
        assertThrows(RpcException.class, () -> rate.charge("a", 5), "refusals are not counted, but the minute is not over");
        now.addAndGet(30_000);
        assertDoesNotThrow(() -> rate.charge("a", 5), "a minute after the first call one is free again");
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10_000; i++) rate.charge("c", 0);
        }, "0 is no limit");
    }

    @Test
    void aLowerLimitAppliesAtOnce() throws Exception {
        AtomicLong now = new AtomicLong();
        CallRate rate = new CallRate(now::get);
        for (int i = 0; i < 10; i++) rate.charge("a", 100);
        assertThrows(RpcException.class, () -> rate.charge("a", 3));
    }
}
