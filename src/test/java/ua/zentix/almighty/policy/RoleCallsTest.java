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

    /** Review of #97: a stored grant outlives op levels, presets and the old grant's end; only narrowing restricts. */
    @Test
    void onItsOwnTheAgentOnlyClearsOrNarrows() {
        assertFalse(RoleCalls.grants(null, null, 0), "clear restricts");
        assertTrue(RoleCalls.grants(null, Role.BUILDER, 0), "no grant yet: any grant grants (it outlives a deop)");
        assertTrue(RoleCalls.grants(null, Role.MEMBER, 10_000), "even a member grant (it outlives a preset change)");
        Roles.Grant until10 = new Roles.Grant(Role.BUILDER, 10_000, "Bob", "x");
        assertTrue(RoleCalls.grants(until10, Role.MEMBER, 0), "lower but for good outlives the old end");
        assertTrue(RoleCalls.grants(until10, Role.BUILDER, 20_000), "the same role for longer");
        assertTrue(RoleCalls.grants(until10, Role.ADMIN, 5_000), "a higher role");
        assertFalse(RoleCalls.grants(until10, Role.MEMBER, 5_000), "lower and sooner: restricts");
        assertFalse(RoleCalls.grants(until10, Role.BUILDER, 10_000), "the same, ending as before");
        Roles.Grant forGood = new Roles.Grant(Role.ADMIN, 0, "Bob", "x");
        assertFalse(RoleCalls.grants(forGood, Role.BUILDER, 0), "lowering a permanent grant restricts");
    }
}
