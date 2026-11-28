package ua.zentix.almighty.work;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.feed.Feed;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tick budget between agents (plan S6, F2): one agent's huge build never starves another's. */
class JobsTest {
    /** A job of {@code units} work units, each costing 1 ms on the test clock. */
    private static class Units extends Job {
        final AtomicLong clock;
        long left;
        int done;

        Units(AtomicLong clock, long units) {
            super("test");
            this.clock = clock;
            this.left = units;
        }

        @Override
        protected void step(Budget budget) {
            running();
            do {
                clock.addAndGet(1_000_000L);
                done++;
                if (--left == 0) {
                    finish();
                    return;
                }
            } while (budget.left());
        }

        @Override
        protected void release() {}

        @Override
        protected void details(JsonObject out) {}
    }

    @Test
    void oneAgentKeepsSubmissionOrder() {
        AtomicLong t = new AtomicLong();
        Jobs jobs = new Jobs(new Feed(), t::get);
        Units big = jobs.submit(new Units(t, 1000), "a", null);
        Units small = jobs.submit(new Units(t, 2), "a", null);
        jobs.tick(3_000_000L);
        assertEquals(3, big.done);
        assertEquals(0, small.done);
    }

    @Test
    void severalAgentsShareEachTick() {
        AtomicLong t = new AtomicLong();
        Jobs jobs = new Jobs(new Feed(), t::get);
        Units big = jobs.submit(new Units(t, 1000), "a", null);
        Units small = jobs.submit(new Units(t, 4), "b", null);
        for (int tick = 0; tick < 4 && !small.state().finished(); tick++) jobs.tick(4_000_000L);
        assertTrue(small.state().finished(), "the second agent's build finishes beside the first's");
        assertTrue(big.done > 0 && !big.state().finished());
        // Within the budget: 4 ms a tick, each agent at most its share plus one unit.
        long before = t.get();
        Units more = jobs.submit(new Units(t, 1000), "b", null);
        jobs.tick(4_000_000L);
        assertTrue(t.get() - before <= 6_000_000L, "one tick took " + (t.get() - before) / 1_000_000 + " ms");
        assertTrue(more.done > 0);
    }

    @Test
    void pausedAgentsJobsWait() {
        AtomicLong t = new AtomicLong();
        Jobs jobs = new Jobs(new Feed(), t::get);
        jobs.paused(a -> a.equals("a"));
        Units frozen = new Units(t, 10) {
            @Override
            public boolean effects() {
                return true;
            }
        };
        jobs.submit(frozen, "a", null);
        Units other = jobs.submit(new Units(t, 2), "b", null);
        jobs.tick(3_000_000L);
        assertEquals(0, frozen.done);
        assertTrue(other.state().finished());
    }
}
