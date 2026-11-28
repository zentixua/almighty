package ua.zentix.almighty.policy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan 9.1: the first status shows the setup and every problem with its plain fix. */
class ConnectionCheckTest {
    private static ConnectionCheck.Facts facts(boolean online, Set<String> mods) {
        return new ConnectionCheck.Facts(online, mods::contains, List.of("Open Parties and Claims"), "127.0.0.1", false, false, "", true);
    }

    @Test
    void reportsTheSetup() throws Exception {
        JsonObject c = ConnectionCheck.of(Policy.parse("preset = \"friends\"\nowners = [\"" + java.util.UUID.randomUUID() + "\"]"),
                facts(true, Set.of("luckperms", "floodgate")));
        assertEquals("friends", c.get("preset").getAsString());
        assertEquals("luckperms", c.get("permissions").getAsString());
        assertEquals("floodgate", c.get("bedrock").getAsString());
        assertEquals("Open Parties and Claims", c.getAsJsonArray("claims").get(0).getAsString());
        assertEquals(0, c.getAsJsonArray("problems").size(), c.toString());
    }

    @Test
    void namesProblemsWithFixes() throws Exception {
        JsonObject c = ConnectionCheck.of(Policy.parse("preset = \"private\""), facts(false, Set.of()));
        String problems = c.getAsJsonArray("problems").toString();
        assertTrue(problems.contains("No owners") && problems.contains("/almighty owner add"), problems);
        assertTrue(problems.contains("online-mode=false") && problems.contains("identity = \\\"proxy\\\""), problems);
        assertEquals("role ladder (/almighty role)", c.get("permissions").getAsString());
        JsonObject pub = ConnectionCheck.of(Policy.parse("preset = \"public\"\nowners = [\"" + java.util.UUID.randomUUID() + "\"]"),
                new ConnectionCheck.Facts(true, m -> false, List.of(), "0.0.0.0", false, true, "http://mc.example.org", true));
        String p = pub.getAsJsonArray("problems").toString();
        assertTrue(p.contains("scripts.enabled") && p.contains("plain http") && p.contains("without TLS"), p);
    }
}
