package ua.zentix.airstrikegm.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenTest {
    @Test
    void generatedOnceReadableOnlyByOwner(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("gm").resolve("token");
        Token first = Token.load(file);
        String text = Files.readString(file).strip();
        assertEquals(64, text.length(), "32 байта в hex");
        assertTrue(first.accepts("Bearer " + text));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
        Token again = Token.load(file);
        assertTrue(again.accepts("Bearer " + text), "второй запуск читает тот же токен");
    }

    @Test
    void acceptsOnlyExactBearer() {
        Token t = Token.of("abc");
        assertTrue(t.accepts("Bearer abc"));
        assertFalse(t.accepts("Bearer abcd"));
        assertFalse(t.accepts("bearer abc"));
        assertFalse(t.accepts("abc"));
        assertFalse(t.accepts(null));
    }
}
