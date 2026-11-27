package ua.zentix.almighty.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Several agents, each with its own token: the existing token file is agent gm; add, revoke, remove, reload. */
class AgentsTest {
    private static String bearer(Path file) throws Exception {
        return "Bearer " + Files.readString(file).strip();
    }

    @Test
    void existingTokenIsAgentGm(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("token"), "a".repeat(64) + "\n");
        Agents agents = Agents.load(dir);
        assertEquals("gm", agents.authenticate("Bearer " + "a".repeat(64)).name(), "today's token keeps working as agent gm");
        assertNull(agents.authenticate("Bearer " + "b".repeat(64)));
        assertNull(agents.authenticate(null));
    }

    @Test
    void addedAgentHasItsOwnTokenFile(@TempDir Path dir) throws Exception {
        Agents agents = Agents.load(dir);
        Path file = agents.add("builder");
        assertEquals(dir.resolve("agents").resolve("builder.token"), file);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
        assertEquals("builder", agents.authenticate(bearer(file)).name());
        assertEquals("gm", agents.authenticate(bearer(dir.resolve("token"))).name());
        assertNotEquals(bearer(file), bearer(dir.resolve("token")));
        assertThrows(IllegalArgumentException.class, () -> agents.add("builder"), "duplicate");
        assertThrows(IllegalArgumentException.class, () -> agents.add("Bad Name"), "name rule");
        assertThrows(IllegalArgumentException.class, () -> agents.add("../x"), "no path escapes");
    }

    @Test
    void revokeRotatesAtOnce(@TempDir Path dir) throws Exception {
        Agents agents = Agents.load(dir);
        Path file = agents.add("moderator");
        String old = bearer(file);
        agents.revoke("moderator");
        assertNull(agents.authenticate(old), "old token refused immediately");
        assertEquals("moderator", agents.authenticate(bearer(file)).name());
        String oldGm = bearer(dir.resolve("token"));
        agents.revoke("gm");
        assertNull(agents.authenticate(oldGm));
        assertEquals("gm", agents.authenticate(bearer(dir.resolve("token"))).name());
    }

    @Test
    void removeAndReload(@TempDir Path dir) throws Exception {
        Agents agents = Agents.load(dir);
        Path file = agents.add("narrator");
        String token = bearer(file);
        agents.remove("narrator");
        assertNull(agents.authenticate(token));
        assertThrows(IllegalArgumentException.class, () -> agents.remove("gm"), "gm can only be revoked");
        // A token file dropped in by hand becomes an agent on reload; a short or misnamed one is ignored.
        Files.writeString(dir.resolve("agents").resolve("film.token"), "f".repeat(40));
        Files.writeString(dir.resolve("agents").resolve("short.token"), "abc");
        Files.writeString(dir.resolve("agents").resolve("Upper.token"), "u".repeat(40));
        agents.reload();
        assertEquals(java.util.List.of("gm", "film"), agents.names());
        Agent film = agents.authenticate("Bearer " + "f".repeat(40));
        agents.reload();
        assertSame(film, agents.get("film"), "unchanged token keeps the agent (and its presence)");
    }
}
