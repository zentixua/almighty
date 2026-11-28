package ua.zentix.almighty.policy;

import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.RpcException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scenario O17 "make Bob a builder until 23:00": the end time, and which changes grant. */
class RoleCallsTest {
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Test
    void untilIsTheNextSuchTimeInThePolicyZone() throws Exception {
        Instant evening = ZonedDateTime.of(2026, 11, 28, 20, 15, 0, 0, WARSAW).toInstant();
        assertEquals(ZonedDateTime.of(2026, 11, 28, 23, 0, 0, 0, WARSAW).toInstant().toEpochMilli(), RoleCalls.until("23:00", WARSAW, evening));
        assertEquals(ZonedDateTime.of(2026, 11, 29, 8, 30, 0, 0, WARSAW).toInstant().toEpochMilli(), RoleCalls.until("8:30", WARSAW, evening),
                "a time already past today is tomorrow");
        assertThrows(RpcException.class, () -> RoleCalls.until("25:00", WARSAW, evening));
        assertThrows(RpcException.class, () -> RoleCalls.until("11pm", WARSAW, evening));
    }

    @Test
    void grantsAreRaisesOrLongerGrants() {
        assertTrue(RoleCalls.grants(Role.MEMBER, null, Role.BUILDER, 0));
        assertFalse(RoleCalls.grants(Role.BUILDER, null, Role.MEMBER, 0), "lower: restricts");
        assertFalse(RoleCalls.grants(Role.BUILDER, null, null, 0), "clear: restricts");
        Roles.Grant until10 = new Roles.Grant(Role.BUILDER, 10_000, "Bob", "x");
        assertTrue(RoleCalls.grants(Role.BUILDER, until10, Role.BUILDER, 20_000), "the same role for longer");
        assertTrue(RoleCalls.grants(Role.BUILDER, until10, Role.BUILDER, 0), "the same role for good");
        assertFalse(RoleCalls.grants(Role.BUILDER, until10, Role.BUILDER, 5_000), "shorter: restricts");
        assertFalse(RoleCalls.grants(Role.ADMIN, null, Role.BUILDER, 0), "an op admin given builder gains nothing");
    }
}
