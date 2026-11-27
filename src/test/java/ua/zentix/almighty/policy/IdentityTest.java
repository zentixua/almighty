package ua.zentix.almighty.policy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verified identity: Mojang (v4) and Floodgate yes, offline name-derived (v3) no, unless the owner says so. */
class IdentityTest {
    private static final UUID MOJANG = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID OFFLINE = UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(StandardCharsets.UTF_8));
    private static final UUID FLOODGATE = UUID.fromString("00000000-0000-0000-0009-01f64f65c7c3");

    @Test
    void auto() {
        assertTrue(Identity.verified(MOJANG, Identity.Mode.AUTO));
        assertTrue(Identity.verified(FLOODGATE, Identity.Mode.AUTO));
        assertTrue(Identity.bedrock(FLOODGATE));
        assertFalse(Identity.bedrock(MOJANG));
        assertFalse(Identity.verified(OFFLINE, Identity.Mode.AUTO), "anyone can claim a name on an offline server");
    }

    @Test
    void ownerChoices() {
        assertFalse(Identity.verified(FLOODGATE, Identity.Mode.MOJANG));
        assertTrue(Identity.verified(OFFLINE, Identity.Mode.OFFLINE));
        assertTrue(Identity.verified(MOJANG, Identity.Mode.PROXY));
    }
}
