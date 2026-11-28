package ua.zentix.almighty.audit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A crash before the journal's index was ever saved (the world's first changes, still running) leaves change files
 * only: a journal opened on the directory lists them as interrupted, so they stay undoable (plan 4.9).
 */
class JournalRecoveryTest {
    @Test
    void changeFilesWithoutAnIndexAreListedAsInterrupted(@TempDir Path dir) throws Exception {
        JsonObject head = new JsonObject();
        head.addProperty("id", 2);
        head.addProperty("agent", "gm");
        head.addProperty("method", "build");
        head.addProperty("time", 1L);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.resolve("2.bin")))) {
            out.writeByte(4);
            out.writeUTF(head.toString());
        }
        Journal journal = new Journal(dir);
        JsonObject meta = journal.describe(2);
        assertNotNull(meta, "the change file was not listed without an index");
        assertTrue(meta.has("interrupted"), meta.toString());
        assertEquals("build", meta.get("method").getAsString());
        // The next change does not reuse its id.
        assertEquals(3, journal.begin("gm", "build", null).id);
    }
}
