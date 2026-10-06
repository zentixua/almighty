package ua.zentix.almighty.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Пакеты движения клиента: стоящий — раз в 20 тиков, идущий, поворачивающий и приземлившийся — каждый тик. */
class MovePacketsTest {
    @Test
    void standingStillReportsEveryTwentyTicks() {
        MovePackets m = new MovePackets();
        assertTrue(m.tick(0, 64, 0, 0, 0, true), "первый тик");
        for (int round = 0; round < 5; round++) {
            for (int i = 1; i < MovePackets.REMINDER_TICKS; i++) assertFalse(m.tick(0, 64, 0, 0, 0, true), "тик " + i);
            assertTrue(m.tick(0, 64, 0, 0, 0, true), "тик " + MovePackets.REMINDER_TICKS);
        }
    }

    @Test
    void turningDoesNotResetReminder() {
        MovePackets m = new MovePackets();
        m.tick(0, 64, 0, 0, 0, true);
        for (int i = 1; i < 10; i++) m.tick(0, 64, 0, 0, 0, true);
        assertTrue(m.tick(0, 64, 0, 45, 0, true), "поворот на 10-м тике");
        for (int i = 11; i < MovePackets.REMINDER_TICKS; i++) assertFalse(m.tick(0, 64, 0, 45, 0, true), "тик " + i);
        assertTrue(m.tick(0, 64, 0, 45, 0, true), "место — всё так же через 20 тиков после прошлого с местом");
    }

    @Test
    void walkingReportsEveryTick() {
        MovePackets m = new MovePackets();
        m.tick(0, 64, 0, 0, 0, true);
        for (int i = 1; i <= 50; i++) assertTrue(m.tick(i * 0.2, 64, 0, 0, 0, true), "тик " + i);
    }

    @Test
    void creepBelowThresholdAddsUp() {
        MovePackets m = new MovePackets();
        m.tick(0, 64, 0, 0, 0, true);
        // сдвиг считается от места прошлого пакета, а не от прошлого тика: ползущий понемногу — сообщает, когда набралось
        assertFalse(m.tick(1.5E-4, 64, 0, 0, 0, true));
        assertTrue(m.tick(3.0E-4, 64, 0, 0, 0, true));
        assertFalse(m.tick(3.0E-4, 64, 0, 0, 0, true));
    }

    @Test
    void turningAndLandingReport() {
        MovePackets m = new MovePackets();
        m.tick(0, 64, 0, 0, 0, false);
        assertTrue(m.tick(0, 64, 0, 10, 0, false), "поворот");
        assertFalse(m.tick(0, 64, 0, 10, 0, false));
        assertTrue(m.tick(0, 64, 0, 10, 0, true), "приземлился");
        assertFalse(m.tick(0, 64, 0, 10, 0, true));
        assertTrue(m.tick(0, 64, 0, 10, 0, false), "оторвался от земли");
    }
}
