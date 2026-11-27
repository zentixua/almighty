package ua.zentix.almighty.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 9: roles below one's own with a duration, zones and quotas in policy.toml, hourly quotas, the friends template. */
class LadderTest {
    private final AtomicLong now = new AtomicLong(1_000_000);

    @Test
    void rolesOnlyBelowYourOwnAndTheyExpire(@TempDir Path dir) {
        Roles roles = new Roles(dir.resolve("roles.json"), now::get);
        UUID bob = UUID.randomUUID();
        assertNull(roles.set(bob, "Bob", Role.BUILDER, Roles.parse("3h"), Role.ADMIN, "Ann"));
        assertEquals(Role.BUILDER, roles.of(bob));
        assertNotNull(roles.set(bob, "Bob", Role.ADMIN, null, Role.ADMIN, "Ann"), "an admin cannot make an admin");
        assertNotNull(roles.set(bob, "Bob", Role.OWNER, null, Role.OWNER, "console"), "owner is never granted");
        assertEquals(Role.BUILDER, new Roles(dir.resolve("roles.json"), now::get).of(bob), "kept on disk");
        now.addAndGet(Duration.ofHours(3).toMillis());
        assertNull(roles.of(bob), "expired");
        assertEquals(Duration.ofMinutes(90), Roles.parse("1h30m"));
        assertNull(Roles.parse("soon"));
    }

    @Test
    void zonesAndQuotasParse() throws Exception {
        Policy p = Policy.parse("""
                [zones.spawn]
                dim = "overworld"
                from = [-10, -64, -10]
                to = [10, 320, 10]
                deny = ["build", "almighty.area"]
                [zones.arena]
                from = [100, 0, 100]
                to = [200, 100, 200]
                agent_may = ["build"]
                [quotas]
                build_blocks = 10
                """);
        Policy.Zone spawn = p.zones().get("spawn");
        assertEquals("minecraft:overworld", spawn.dim());
        assertTrue(spawn.deny().contains("area"), "almighty. prefix accepted");
        assertTrue(spawn.meets("minecraft:overworld", new int[]{5, 64, 5}, new int[]{50, 64, 50}));
        assertTrue(!spawn.holds("minecraft:overworld", new int[]{5, 64, 5}, new int[]{50, 64, 50}));
        assertTrue(!spawn.meets("minecraft:the_nether", new int[]{0, 0, 0}, new int[]{0, 0, 0}));
        assertEquals(10, p.limits().buildBlocks());
        Policy.Zone bots = Policy.parse("[zones.b]\nfrom = [0,0,0]\nto = [1,1,1]\ndeny = [\"bots\"]").zones().get("b");
        assertTrue(bots.denies(Node.BOTS) && bots.denies(Node.BOTS_ADMIN) && !bots.denies(Node.BUILD), "bots covers bots.admin");
        assertEquals(Policy.Limits.DEFAULT.giveItems(), p.limits().giveItems());
        for (String bad : new String[]{"[zones.x]\nfrom = [1, 2]\nto = [1, 2, 3]", "[zones.x]\nfrom = [0,0,0]\nto = [1,1,1]\ndeny = [\"fly\"]",
                "[quotas]\nbuild_blocks = -1", "[quotas]\nbots = 3",
                // Nodes no zone check enforces are refused, not silently ignored; a dimension must be an id.
                "[zones.x]\nfrom = [0,0,0]\nto = [1,1,1]\ndeny = [\"give.self\"]", "[zones.x]\nfrom = [0,0,0]\nto = [1,1,1]\nagent_may = [\"command\"]",
                "[zones.x]\ndim = \"Over World\"\nfrom = [0,0,0]\nto = [1,1,1]"}) {
            assertThrows(Policy.Invalid.class, () -> Policy.parse(bad), bad);
        }
    }

    @Test
    void hourlyQuota() throws Exception {
        Quotas q = new Quotas(now::get);
        UUID a = UUID.randomUUID();
        q.charge(a, "build.blocks", 8, 10);
        assertEquals(429, assertThrows(ua.zentix.almighty.bridge.RpcException.class, () -> q.charge(a, "build.blocks", 3, 10)).status());
        q.charge(UUID.randomUUID(), "build.blocks", 10, 10);
        q.charge("agent:gm", "build.blocks", 10, 10);
        assertEquals(429, assertThrows(ua.zentix.almighty.bridge.RpcException.class, () -> q.charge("agent:gm", "build.blocks", 1, 10)).status(),
                "the agent's own builds have a budget too");
        q.prune();
        assertEquals(429, assertThrows(ua.zentix.almighty.bridge.RpcException.class, () -> q.charge(a, "build.blocks", 3, 10)).status(), "prune keeps live windows");
        now.addAndGet(Quotas.HOUR_MS);
        q.charge(a, "build.blocks", 10, 10);
    }

    @Test
    void freshInstallTemplateIsFriends(@TempDir Path dir) {
        Policies p = new Policies(dir.resolve("policy.toml"));
        p.load(Policy.Preset.FRIENDS);
        assertEquals(Policy.Preset.FRIENDS, p.current().preset());
        assertEquals(Role.MEMBER, p.current().floor("gm"));
    }
}
