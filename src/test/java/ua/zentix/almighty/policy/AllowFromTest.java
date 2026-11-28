package ua.zentix.almighty.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 9.1, scenario F1: an agent's token is accepted only from its allow_from addresses. */
class AllowFromTest {
    @Test
    void rangesMatch() {
        AddressRange one = AddressRange.parse("203.0.113.7"), net = AddressRange.parse("10.0.0.0/8"), six = AddressRange.parse("fd00::/8");
        assertTrue(one.contains("203.0.113.7"));
        assertFalse(one.contains("203.0.113.8"));
        assertTrue(net.contains("10.200.3.4"));
        assertFalse(net.contains("11.0.0.1"));
        assertTrue(six.contains("fd12::1"));
        assertFalse(six.contains("10.0.0.1"), "families never mix");
        assertTrue(AddressRange.parse("192.168.1.0/20").contains("192.168.15.255"));
        assertFalse(AddressRange.parse("192.168.1.0/20").contains("192.168.16.0"));
        assertThrows(IllegalArgumentException.class, () -> AddressRange.parse("example.org"), "names are never looked up");
        assertThrows(IllegalArgumentException.class, () -> AddressRange.parse("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> AddressRange.parse("cafe.be"), "hex-letter names are names too");
        assertThrows(IllegalArgumentException.class, () -> AddressRange.parse("10.1"), "short IPv4 forms");
    }

    @Test
    void policyKeyAndFriction() throws Exception {
        Policy open = Policy.parse("[agents.gm]\nceiling = \"admin\"\n");
        Policy locked = Policy.parse("[agents.gm]\nceiling = \"admin\"\nallow_from = [\"127.0.0.1\", \"10.0.0.0/8\"]\n");
        assertTrue(open.allowsFrom("gm", "198.51.100.1"), "unset: any address");
        assertTrue(locked.allowsFrom("gm", "10.1.2.3"));
        assertFalse(locked.allowsFrom("gm", "198.51.100.1"));
        assertTrue(locked.allowsFrom("other", "198.51.100.1"), "only the listed agent");
        assertNull(PolicyEdits.grants(open, locked), "adding allow_from restricts");
        assertNotNull(PolicyEdits.grants(locked, open), "dropping it grants");
        assertNotNull(PolicyEdits.grants(locked, Policy.parse("[agents.gm]\nceiling = \"admin\"\nallow_from = [\"0.0.0.0/0\"]\n")), "a wider range grants");
        assertNull(PolicyEdits.grants(locked, Policy.parse("[agents.gm]\nceiling = \"admin\"\nallow_from = [\"127.0.0.1\"]\n")), "fewer restricts");
        Policy.Invalid bad = assertThrows(Policy.Invalid.class, () -> Policy.parse("[agents.gm]\nallow_from = [\"home.example\"]\n"));
        assertTrue(bad.getMessage().contains("allow_from"), bad.getMessage());
        assertThrows(Policy.Invalid.class, () -> Policy.parse("[agents.gm]\nallow_from = []\n"));
        assertEquals(2, locked.agents().get("gm").allowFrom().size());
    }
}
