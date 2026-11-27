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
    }
}
