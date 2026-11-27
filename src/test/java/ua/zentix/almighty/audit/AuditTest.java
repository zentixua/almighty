package ua.zentix.almighty.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit: effects and refusals are recorded (memory and a JSON-lines file), successful reads are not; old files pruned. */
class AuditTest {
    private final Caller gm = new Caller(Agents.of(Map.of("gm", "a".repeat(32))).get("gm"), "127.0.0.1");

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void recordsEffectsAndRefusalsNotReads(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("audit-" + LocalDate.now().minusDays(40) + ".jsonl"), "{}\n");
        Files.writeString(dir.resolve("audit-" + LocalDate.now().minusDays(3) + ".jsonl"), "{}\n");
        Audit audit = new Audit(dir, 30);
        audit.ended(gm, "status", new JsonObject(), ua.zentix.almighty.bridge.Dispatch.clipped(new JsonObject(), 4096), new JsonObject(), null, 1_000_000, true, 0);
        JsonObject job = json("{\"id\":12,\"kind\":\"build\",\"state\":\"done\"}");
        audit.ended(gm, "build", json("{\"ops\":[],\"for\":\"t1\"}"), ua.zentix.almighty.bridge.Dispatch.clipped(json("{\"ops\":[],\"for\":\"t1\"}"), 4096), job, null, 5_000_000, false, 0);
        audit.ended(gm, "command", json("{\"commands\":[\"stop\"]}"), ua.zentix.almighty.bridge.Dispatch.clipped(json("{\"commands\":[\"stop\"]}"), 4096), null, new RpcException(403, "denied", "stops the server"), 100_000, false, 0);
        audit.ended(gm, "map", new JsonObject(), ua.zentix.almighty.bridge.Dispatch.clipped(new JsonObject(), 4096), null, new RpcException(423, "paused", "paused"), 100_000, true, 0);
        audit.owner("Server", "pause", "all");
        JsonArray recent = audit.recent(10, e -> true);
        assertEquals(List.of("build", "command", "map", "pause"),
                recent.asList().stream().map(e -> e.getAsJsonObject().get("method").getAsString()).toList(), "no successful read");
        JsonObject build = recent.get(0).getAsJsonObject();
        assertEquals(12, build.get("job").getAsLong());
        assertEquals("t1", build.get("ticket").getAsString());
        assertEquals("gm", build.get("agent").getAsString());
        assertEquals("denied", recent.get(1).getAsJsonObject().get("outcome").getAsString());
        assertEquals(1, audit.recent(10, e -> e.get("agent").getAsString().equals("owner")).size());
        audit.stop();
        List<String> lines = Files.readAllLines(dir.resolve("audit-" + LocalDate.now() + ".jsonl"));
        assertEquals(4, lines.size(), "one JSON line per entry");
        assertEquals("build", json(lines.get(0)).get("method").getAsString());
        assertFalse(Files.exists(dir.resolve("audit-" + LocalDate.now().minusDays(40) + ".jsonl")), "older than audit.days: deleted");
        assertTrue(Files.exists(dir.resolve("audit-" + LocalDate.now().minusDays(3) + ".jsonl")));
    }

    @Test
    void boundedAndClipped() {
        Audit audit = new Audit(null, 30);
        JsonObject big = new JsonObject();
        big.add("code", new JsonPrimitive("x".repeat(Audit.MAX_PARAMS * 2)));
        for (int i = 0; i < Audit.KEEP + 20; i++) audit.ended(gm, "script", big, ua.zentix.almighty.bridge.Dispatch.clipped(big, 4096), new JsonObject(), null, 1, false, 0);
        JsonArray all = audit.recent(Audit.KEEP * 2, e -> true);
        assertEquals(Audit.KEEP, all.size());
        assertTrue(all.get(0).getAsJsonObject().get("params").getAsString().length() <= Audit.MAX_PARAMS + 1);
        assertEquals(Audit.MAX_PARAMS + 1, ua.zentix.almighty.bridge.Dispatch.clipped(big, Audit.MAX_PARAMS).length(), "clipped with …");
        assertEquals("{\"a\":1}", ua.zentix.almighty.bridge.Dispatch.clipped(com.google.gson.JsonParser.parseString("{\"a\":1}"), 100));
    }

    @Test
    void pause() {
        Pause p = new Pause();
        p.pause("builder");
        assertTrue(p.paused("builder"));
        assertFalse(p.paused("gm"));
        p.pause(null);
        assertTrue(p.paused("gm"));
        p.resume(null);
        assertFalse(p.paused("builder"), "resume all clears agents too");
    }
}
