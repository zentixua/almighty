package ua.zentix.almighty.mcp.signin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.zentix.almighty.policy.Policy;
import ua.zentix.almighty.policy.Role;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * "The owner's own app" at approval writes ceiling owner for that agent into policy.toml, keeps what the file had,
 * yields a file the policy parser accepts, and leaves an agent the file already lists to the owner.
 */
class OwnerAppTest {
    @TempDir
    Path dir;

    @Test
    void grantsOwnerCeilingAndKeepsTheRest() throws Exception {
        Path file = dir.resolve("policy.toml");
        Files.writeString(file, Policy.template(Policy.Preset.PRIVATE));
        assertEquals(Role.ADMIN, Policy.parse(Files.readString(file)).ceiling("claude-code"));
        assertNull(OwnerApp.grant(file, "claude-code"));
        Policy p = Policy.parse(Files.readString(file));
        assertEquals(Role.OWNER, p.ceiling("claude-code"));
        assertEquals(Role.OWNER, p.ceiling("gm"), "what the file had is kept");
        assertNotNull(OwnerApp.grant(file, "claude-code"), "a listed agent is left to the owner");
        assertNull(OwnerApp.grant(dir.resolve("new.toml"), "codex"), "a missing file is created");
        assertEquals(Role.OWNER, Policy.parse(Files.readString(dir.resolve("new.toml"))).ceiling("codex"));
    }
}
