package ua.zentix.almighty.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Client movement packets: stationary every 20 ticks; moving, turning and landing every tick. */
class MovePacketsTest {
    @Test
    void standingStillReportsEveryTwentyTicks() {
        MovePackets m = new MovePackets();
        assertTrue(m.tick(0, 64, 0, 0, 0, true), "first tick");
        for (int round = 0; round < 5; round++) {
            for (int i = 1; i < MovePackets.REMINDER_TICKS; i++) assertFalse(m.tick(0, 64, 0, 0, 0, true), "tick " + i);
            assertTrue(m.tick(0, 64, 0, 0, 0, true), "tick " + MovePackets.REMINDER_TICKS);
        }
    }

    @Test
    void turningDoesNotResetReminder() {
        MovePackets m = new MovePackets();
        m.tick(0, 64, 0, 0, 0, true);
        for (int i = 1; i < 10; i++) m.tick(0, 64, 0, 0, 0, true);
        assertTrue(m.tick(0, 64, 0, 45, 0, true), "rotation on tick 10");
        for (int i = 11; i < MovePackets.REMINDER_TICKS; i++) assertFalse(m.tick(0, 64, 0, 45, 0, true), "tick " + i);
        assertTrue(m.tick(0, 64, 0, 45, 0, true), "position still reported 20 ticks after the previous position packet");
    }

    @Test
    void walkingReportsEveryTick() {
        MovePackets m = new MovePackets();
        m.tick(0, 64, 0, 0, 0, true);
        for (int i = 1; i <= 50; i++) assertTrue(m.tick(i * 0.2, 64, 0, 0, 0, true), "tick " + i);
    }

    @Test
    void creepBelowThresholdAddsUp() {
        MovePackets m = new MovePackets();
        m.tick(0, 64, 0, 0, 0, true);
        // Displacement is relative to the last packet, not the last tick: slow movement reports once enough accumulates.
        assertFalse(m.tick(1.5E-4, 64, 0, 0, 0, true));
        assertTrue(m.tick(3.0E-4, 64, 0, 0, 0, true));
        assertFalse(m.tick(3.0E-4, 64, 0, 0, 0, true));
    }

    @Test
    void turningAndLandingReport() {
        MovePackets m = new MovePackets();
        m.tick(0, 64, 0, 0, 0, false);
        assertTrue(m.tick(0, 64, 0, 10, 0, false), "rotation");
        assertFalse(m.tick(0, 64, 0, 10, 0, false));
        assertTrue(m.tick(0, 64, 0, 10, 0, true), "landed");
        assertFalse(m.tick(0, 64, 0, 10, 0, true));
        assertTrue(m.tick(0, 64, 0, 10, 0, false), "left the ground");
    }
}
