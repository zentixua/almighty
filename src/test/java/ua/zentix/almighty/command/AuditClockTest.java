package ua.zentix.almighty.command;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Lead's F11: /almighty log shows times in the policy's zone (the JVM's by default), not UTC. */
class AuditClockTest {
    @Test
    void auditTimesShowInTheServersZone() {
        assertEquals("07:50:42", AlmightyCommand.clock("2026-11-28T06:50:42.123Z", ZoneId.of("Europe/Warsaw")));
        assertEquals("06:50:42", AlmightyCommand.clock("2026-11-28T06:50:42Z", ZoneId.of("UTC")));
        assertEquals("not a time", AlmightyCommand.clock("not a time", ZoneId.of("UTC")));
    }
}
