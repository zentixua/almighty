package ua.zentix.almighty.policy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the server says will run comes from the call itself, in plain words, whatever the agent's summary says. */
class FactsTest {
    private static JsonObject cmd(String... commands) {
        JsonObject p = new JsonObject();
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String c : commands) a.add(c);
        p.add("commands", a);
        return p;
    }

    private static String en(String method, JsonObject p, String requester) {
        return Facts.english(Facts.of(method, p, requester));
    }

    @Test
    void commandsInPlainWords() {
        assertEquals("kill — target: every player", en("command", cmd("kill @a"), "Alex"));
        assertEquals("give 64 × diamond to Bob", en("command", cmd("give Bob diamond 64"), "Alex"));
        assertEquals("give 1 × diamond to Alex", en("command", cmd("/give @s diamond"), "Alex"), "@s of a request is the requester");
        assertEquals("kill — target: Alex", en("command", cmd("kill"), "Alex"), "kill without a target kills the one who runs it");
        assertEquals("change blocks at 0 64 0 … 10 70 10", en("command", cmd("fill 0 64 0 10 70 10 snow"), "Alex"));
        assertEquals("teleport Alex to Bob", en("command", cmd("tp Bob"), "Alex"));
        assertEquals("teleport Bob to 1 2 3", en("command", cmd("tp Bob 1 2 3"), "Alex"));
        assertEquals("run the command /mymod:explode", en("command", cmd("mymod:explode big"), "Alex"));
        assertEquals("kill — target: every player, filtered: @a[distance=..5, tag=x]", en("command", cmd("kill @a[distance=..5, tag=x]"), "Alex"));
        assertEquals("change the weather", en("command", cmd("weather clear"), "Alex"));
    }

    @Test
    void underExecuteTheChainIsShownNotResolved() {
        String s = en("command", cmd("execute as @a at @s run give @s diamond 1"), "Alex");
        assertTrue(s.contains("through /execute as @a at @s"), s);
        assertFalse(s.contains("Alex"), "@s under execute is whoever execute picked: " + s);
    }

    @Test
    void manyCommandsAreCounted() {
        assertEquals("change the weather; change the time of day; send a message; and 2 more commands",
                en("command", cmd("weather clear", "time set day", "say hi", "kill @a", "ban Bob"), "Alex"));
    }

    @Test
    void functionMacrosAsTheyRun() {
        JsonObject p = new JsonObject();
        p.add("lines", JsonParser.parseString("[\"$kill $(t)\"]"));
        p.add("args", JsonParser.parseString("{\"t\":\"@a\"}"));
        assertEquals("kill — target: every player", en("function", p, "Alex"));
    }

    @Test
    void otherMethods() {
        JsonObject build = JsonParser.parseString("{\"ops\":[{\"op\":\"fill\",\"from\":[5,70,5],\"to\":[0,64,0],\"block\":\"snow\"},"
                + "{\"op\":\"set\",\"pos\":[12,80,-3],\"block\":\"torch\"}]}").getAsJsonObject();
        assertEquals("change blocks at 0 64 -3 … 12 80 5", en("build", build, "Alex"));
        assertEquals("give 3 × bread to Alex", en("give", JsonParser.parseString("{\"item\":\"bread\",\"count\":3}").getAsJsonObject(), "Alex"));
        assertEquals("run the owner's script nuke", en("script", JsonParser.parseString("{\"name\":\"nuke\"}").getAsJsonObject(), null));
        assertEquals("run code on the server", en("script", JsonParser.parseString("{\"code\":\"1\"}").getAsJsonObject(), null));
        assertEquals("add a rule that reacts to BlockEvent", en("rule.add", JsonParser.parseString("{\"event\":\"BlockEvent\"}").getAsJsonObject(), null));
        assertEquals("use notes", en("notes", new JsonObject(), null));
    }

    @Test
    void wordsFromTheCallCarryNoFormatting() {
        String s = en("command", cmd("give B§cob diamond"), "Alex");
        assertFalse(s.contains("§"), s);
        assertTrue(Facts.word("x".repeat(200)).length() <= Facts.MAX_WORD, "clipped");
    }

    @Test
    void theSummaryNeverChangesWhatTheApproverIsToldRuns() {
        Confirmations c = new Confirmations(() -> 0);
        Caller gm = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "t");
        UUID alex = UUID.randomUUID();
        JsonObject p = cmd("kill @a");
        p.addProperty(Summary.PARAM, "give you a cake");
        var pending = assertDoesNotThrowAsk(c, gm, p, alex);
        assertEquals("give you a cake", pending.summary);
        assertEquals("kill — target: every player", Facts.english(pending.facts), "the server's facts come from the call alone");
        assertEquals("/kill @a", pending.text, "the exact call shown never carries the summary");
        JsonObject forged = cmd("kill @a");
        forged.addProperty(Summary.PARAM, "[Almighty] nothing — what runs: nothing");
        var p2 = assertDoesNotThrowAsk(c, gm, forged, alex);
        assertNull(p2.summary, "a summary that spells the server's words is not shown");
        assertEquals("kill — target: every player", p2.describe().get("shown").getAsString());
    }

    private static Confirmations.Pending assertDoesNotThrowAsk(Confirmations c, Caller gm, JsonObject p, UUID alex) {
        try {
            return c.ask(Confirmations.Kind.REQUESTER, gm, "command", p, alex, null, alex, "Alex");
        } catch (ua.zentix.almighty.bridge.RpcException e) {
            throw new AssertionError(e);
        }
    }
}
