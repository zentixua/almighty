package ua.zentix.almighty.policy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline permission answers never wait for the handler (stability audit M10): asked in the background, cached. */
class OfflineAnswersTest {
    private final List<Runnable> queued = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000);
    private final OfflineAnswers answers = new OfflineAnswers(queued::add, now::get);
    private final UUID bob = UUID.randomUUID();

    @Test
    void firstAskReturnsAtOnceAndTheAnswerCountsOnceKnown() {
        AtomicInteger asked = new AtomicInteger();
        assertNull(answers.get(bob, "almighty.build", 0, () -> {
            asked.incrementAndGet();
            return true;
        }), "no answer yet: the ladder decides; the handler was not called on this thread");
        assertEquals(0, asked.get());
        assertNull(answers.get(bob, "almighty.build", 0, () -> true), "still asking: not asked twice");
        assertEquals(1, queued.size());
        queued.remove(0).run();
        assertEquals(1, asked.get());
        assertEquals(Boolean.TRUE, answers.get(bob, "almighty.build", 0, () -> false));
        assertTrue(queued.isEmpty(), "a fresh answer is not asked again");
    }

    @Test
    void staleAnswersAreRefreshedInTheBackground() {
        answers.get(bob, "almighty.build", 0, () -> true);
        queued.remove(0).run();
        now.addAndGet(OfflineAnswers.FRESH_MS);
        assertEquals(Boolean.TRUE, answers.get(bob, "almighty.build", 0, () -> false), "the old answer while asking again");
        queued.remove(0).run();
        assertEquals(Boolean.FALSE, answers.get(bob, "almighty.build", 0, () -> false));
    }

    @Test
    void aFailingHandlerLeavesTheLadderInCharge() {
        answers.get(bob, "almighty.build", 0, () -> {
            throw new IllegalStateException("no handler yet");
        });
        queued.remove(0).run();
        assertNull(answers.get(bob, "almighty.build", 0, () -> true));
    }

    @Test
    void aPolicyOrRoleChangeDropsTheAnswers() {
        answers.get(bob, "almighty.build", 0, () -> true);
        queued.remove(0).run();
        assertEquals(Boolean.TRUE, answers.get(bob, "almighty.build", 0, () -> true));
        assertNull(answers.get(bob, "almighty.build", 1, () -> false), "a role changed: the ladder decides until the new answer");
        queued.remove(0).run();
        assertEquals(Boolean.FALSE, answers.get(bob, "almighty.build", 1, () -> true));
    }

    /** Review of #95: an ask still running for an old generation does not leave the new one unanswered. */
    @Test
    void aChangeWhileAskingStartsItsOwnAsk() {
        answers.get(bob, "almighty.rules", 0, () -> true);
        assertNull(answers.get(bob, "almighty.rules", 1, () -> false), "changed while the first ask runs");
        assertEquals(2, queued.size(), "the new generation asks too");
        queued.remove(0).run();
        assertNull(answers.get(bob, "almighty.rules", 1, () -> false), "the old answer does not count for the new generation");
        queued.remove(0).run();
        assertEquals(Boolean.FALSE, answers.get(bob, "almighty.rules", 1, () -> false));
    }

    /** A known "no" holds across a change until the new answer: never wider than the handler said. */
    @Test
    void aKnownDenyHoldsAcrossAChange() {
        answers.get(bob, "almighty.build", 0, () -> false);
        queued.remove(0).run();
        assertEquals(Boolean.FALSE, answers.get(bob, "almighty.build", 1, () -> true), "still no while asking again");
        queued.remove(0).run();
        assertEquals(Boolean.TRUE, answers.get(bob, "almighty.build", 1, () -> true));
    }
}
