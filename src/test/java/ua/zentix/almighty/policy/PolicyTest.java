package ua.zentix.almighty.policy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** policy.toml: the template parses, mistakes are named, floors follow the preset and never pass the ceiling. */
class PolicyTest {
    @Test
    void templateIsTodaysBehaviour() throws Exception {
        Policy p = Policy.parse(Policy.template(Policy.Preset.PRIVATE));
        assertEquals(Policy.Preset.PRIVATE, p.preset());
        assertEquals(Role.OWNER, p.ceiling("gm"));
        assertEquals(Role.OWNER, p.floor("gm"), "private: the floor is the ceiling, everything as before");
        assertEquals(Role.ADMIN, p.ceiling("builder"), "unlisted agents default below owner");
        assertEquals(Policy.defaults().floor("gm"), Role.OWNER, "a missing or broken file falls back to the same");
    }

    @Test
    void floorsFollowThePreset() throws Exception {
        Policy friends = Policy.parse("preset = \"friends\"\n[agents.gm]\nceiling = \"owner\"\n[agents.mod]\nceiling = \"admin\"\nfloor = \"builder\"");
        assertEquals(Role.MEMBER, friends.floor("gm"));
        assertEquals(Role.BUILDER, friends.floor("mod"), "an explicit floor");
        Policy pub = Policy.parse("preset = \"public\"");
        assertEquals(Role.GUEST, pub.floor("gm"));
        Policy low = Policy.parse("preset = \"private\"\n[agents.narrator]\nceiling = \"member\"");
        assertEquals(Role.MEMBER, low.floor("narrator"), "never above the ceiling");
    }

    @Test
    void ownersAreUuids() throws Exception {
        UUID id = UUID.randomUUID();
        assertTrue(Policy.parse("owners = [\"" + id + "\"]").owners().contains(id));
        Policy.Invalid e = assertThrows(Policy.Invalid.class, () -> Policy.parse("owners = [\"ZentixUA\"]"));
        assertTrue(e.getMessage().contains("not a UUID"), e.getMessage());
    }

    @Test
    void mistakesAreNamed() {
        for (String[] c : new String[][]{
                {"preset = \"open\"", "preset"},
                {"identity = \"none\"", "identity"},
                {"colour = 1", "unknown key colour"},
                {"[agents.gm]\nceiling = \"god\"", "agents.gm.ceiling"},
                {"[agents.gm]\nceiling = \"member\"\nfloor = \"admin\"", "above its ceiling"},
                {"[agents.gm]\nlimit = 3", "agents.gm: unknown key limit"},
                {"preset = ", "not valid TOML"}}) {
            Policy.Invalid e = assertThrows(Policy.Invalid.class, () -> Policy.parse(c[0]), c[0]);
            assertTrue(e.getMessage().contains(c[1]), c[0] + " → " + e.getMessage());
        }
    }

    @Test
    void badFileKeepsTheLastGoodPolicy(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file = dir.resolve("policy.toml");
        Policies p = new Policies(file);
        p.load(Policy.Preset.PRIVATE);
        assertTrue(java.nio.file.Files.readString(file).contains("preset = \"private\""), "template written");
        java.nio.file.Files.writeString(file, "preset = \"friends\"");
        assertEquals(null, p.reload());
        assertEquals(Policy.Preset.FRIENDS, p.current().preset());
        java.nio.file.Files.writeString(file, "preset = \"chaos\"");
        assertTrue(p.reload().contains("preset"));
        assertEquals(Policy.Preset.FRIENDS, p.current().preset(), "the previous policy stays");
        // At startup there is no previous policy: a broken file locks down instead of opening up.
        Policies fresh = new Policies(file);
        fresh.load(Policy.Preset.PRIVATE);
        assertEquals(Policy.Preset.PUBLIC, fresh.current().preset());
        assertEquals(Role.GUEST, fresh.current().floor("gm"));
        assertTrue(fresh.current().owners().isEmpty());
    }

    /** Plan 4.5: timezone (audit days, times shown) and privacy_notice (on unless turned off; turning it off is a grant). */
    @Test
    void timezoneAndPrivacyNotice() throws Exception {
        Policy p = Policy.parse(Policy.template(Policy.Preset.FRIENDS));
        assertTrue(p.safety().privacyNotice(), "the template tells joining players");
        assertEquals(java.time.ZoneId.systemDefault(), p.timezone(), "unset: the machine's zone");
        assertEquals(java.time.ZoneId.of("Europe/Warsaw"), Policy.parse("timezone = \"Europe/Warsaw\"").timezone());
        Policy quiet = Policy.parse("[safety]\nprivacy_notice = false");
        assertTrue(!quiet.safety().privacyNotice());
        for (String bad : new String[]{"timezone = \"Mars/Olympus\"", "[safety]\nprivacy_notice = \"yes\""}) {
            Policy.Invalid e = assertThrows(Policy.Invalid.class, () -> Policy.parse(bad), bad);
            assertTrue(e.getMessage().contains(bad.contains("timezone") ? "timezone" : "privacy_notice"), e.getMessage());
        }
        assertTrue(PolicyEdits.grants(Policy.parse(""), quiet) != null, "silencing the notice needs an approval");
        assertEquals(null, PolicyEdits.grants(quiet, Policy.parse("")), "turning it on does not");
    }

    /** Rights audit m5: mojang → auto verifies Floodgate players too, so it is a grant; auto → mojang restricts. */
    @Test
    void identityOrder() throws Exception {
        Policy mojang = Policy.parse("identity = \"mojang\"\n"), auto = Policy.parse("identity = \"auto\"\n"),
                proxy = Policy.parse("identity = \"proxy\"\n"), offline = Policy.parse("identity = \"offline\"\n");
        assertTrue(PolicyEdits.grants(mojang, auto) != null);
        assertTrue(PolicyEdits.grants(auto, proxy) != null);
        assertTrue(PolicyEdits.grants(proxy, offline) != null);
        assertEquals(null, PolicyEdits.grants(auto, mojang));
        assertEquals(null, PolicyEdits.grants(offline, proxy));
    }

    /** Plan 4.5/7.2: per-agent MCP defaults are read, and named mistakes. */
    @Test
    void agentProfileAndImages() throws Exception {
        Policy p = Policy.parse("[agents.local]\nceiling = \"member\"\nprofile = \"compact\"\nimages = false");
        assertEquals("compact", p.profile("local"));
        assertEquals(Boolean.FALSE, p.images("local"));
        assertEquals(null, p.profile("gm"));
        assertThrows(Policy.Invalid.class, () -> Policy.parse("[agents.x]\nprofile = \"tiny\""));
        assertThrows(Policy.Invalid.class, () -> Policy.parse("[agents.x]\nimages = \"no\""));
    }
}
