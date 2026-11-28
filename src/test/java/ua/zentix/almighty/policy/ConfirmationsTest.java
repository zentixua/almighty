package ua.zentix.almighty.policy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which requests wait for a person, who may decide, and that admins are never flooded. */
class ConfirmationsTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Confirmations c = new Confirmations(now::get);
    private final Caller gm = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "t");
    private final UUID alex = UUID.randomUUID();

    private static JsonObject cmd(String command) {
        JsonObject p = new JsonObject();
        p.addProperty("commands", command);
        return p;
    }

    private static JsonObject fn(String line, String argsJson) {
        JsonObject p = new JsonObject();
        p.add("lines", JsonParser.parseString("[\"" + line.replace("\"", "\\\"") + "\"]"));
        p.add("args", JsonParser.parseString(argsJson));
        return p;
    }

    @Test
    void onlyKnownSafeCommandsSkipConfirmation() {
        Confirmations.players(n -> n.equalsIgnoreCase("Bob") || n.equalsIgnoreCase("Jo") || n.equalsIgnoreCase(".Steve"));
        UUID me = UUID.randomUUID();
        for (String c : new String[]{
                // Rights audit M2: a quoted name, a short name, a Floodgate name, a UUID without its leading zeros.
                "kill \"Bob\"", "clear Jo", "kill .Steve", "kill 69a79f4-44e9-4726-a5be-fca90e38aaf5",
                "kill \"069a79f4-44e9-4726-a5be-fca90e38aaf5\"", "kill 00069a79f4-44e9-4726-a5be-fca90e38aaf5",
                "execute as @a at @s run setblock ~ ~ ~ lava",
                "kill @e[type=zombie]", "give Bob diamond 64", "execute as @a run clear", "minecraft:ban Bob",
                "ban Offline", "deop Somebody", "return run kill @a", "kill 069a79f4-44e9-4726-a5be-fca90e38aaf5",
                "execute on passengers run kill @s", "summon zombie", "data merge block 0 0 0 {}", "mymod:explode", "function x:y",
                "schedule function x:y 1t", "gamerule keepInventory false", "transfer some.host"}) {
            assertTrue(Confirmations.needsRequester("command", cmd(c), "Alex", me, Set.of()), c);
        }
        for (String c : new String[]{"give @s diamond 1", "give Alex diamond 1", "kill", "time set day", "give @s diamond_sword",
                "tp @s 0 64 0", "setblock 0 64 0 stone", "execute positioned 0 64 0 run setblock ~ ~ ~ stone", "say hi",
                "kill " + me}) {
            assertFalse(Confirmations.needsRequester("command", cmd(c), "Alex", me, Set.of()), c);
        }
        assertTrue(Confirmations.needsRequester("function", fn("$kill $(t)", "{\"t\":\"@a\"}"), "Alex", me, Set.of()),
                "macros are expanded before checking");
        assertEquals("/kill @a", Confirmations.render("function", fn("$kill $(t)", "{\"t\":\"@a\"}")), "and shown as they run");
        assertTrue(Confirmations.needsRequester("build", new JsonObject(), "Alex", Set.of("build")), "confirm_first");
        assertTrue(Confirmations.needsRequester("command", cmd("time set day"), "Alex", Set.of("time")));
        Confirmations.players(n -> true);
    }

    /** The question's last line is true: undo covers journaled block writes only; "cannot be undone" only for what is asked as irreversible. */
    @Test
    void theQuestionSaysUndoOnlyWhereItIsTrue() {
        Confirmations.players(n -> n.equalsIgnoreCase("Bob"));
        UUID me = UUID.randomUUID();
        assertEquals(Confirmations.Undo.REVERTIBLE, Confirmations.undo("command", cmd("fill 0 64 0 9 64 9 stone"), "Alex", me));
        assertEquals(Confirmations.Undo.REVERTIBLE, Confirmations.undo("command", cmd("execute at @s run setblock ~ ~ ~ lava"), "Alex", me));
        assertEquals(Confirmations.Undo.REVERTIBLE, Confirmations.undo("function", fn("clone 0 0 0 1 1 1 5 5 5", "{}"), "Alex", me));
        assertEquals(Confirmations.Undo.REVERTIBLE, Confirmations.undo("build", new JsonObject(), "Alex", me));
        // Irreversible for others: asked without confirm_first, and no undo brings it back.
        JsonObject both = new JsonObject();
        both.add("commands", JsonParser.parseString("[\"setblock 0 64 0 stone\", \"kill Bob\"]"));
        assertEquals(Confirmations.Undo.PERMANENT, Confirmations.undo("command", cmd("kill @a"), "Alex", me));
        assertEquals(Confirmations.Undo.PERMANENT, Confirmations.undo("command", cmd("give Bob diamond 64"), "Alex", me));
        assertEquals(Confirmations.Undo.PERMANENT, Confirmations.undo("command", both, "Alex", me));
        // execute store writes a score or data the journal does not keep.
        assertEquals(Confirmations.Undo.PERMANENT,
                Confirmations.undo("command", cmd("execute as Bob store result score Bob x run setblock ~ ~ ~ air"), "Alex", me));
        // Asked only because the owner listed it in confirm_first (reversible kinds), or a teleport: neither claim.
        assertTrue(Confirmations.needsRequester("command", cmd("tp @s 0 80 0"), "Alex", me, Set.of("tp")));
        assertEquals(Confirmations.Undo.UNSTATED, Confirmations.undo("command", cmd("tp @s 0 80 0"), "Alex", me));
        assertEquals(Confirmations.Undo.UNSTATED, Confirmations.undo("command", cmd("tp Bob"), "Alex", me));
        assertEquals(Confirmations.Undo.UNSTATED, Confirmations.undo("command", cmd("time set night"), "Alex", me));
        assertEquals(Confirmations.Undo.UNSTATED, Confirmations.undo("command", cmd("fillbiome 0 0 0 9 9 9 desert"), "Alex", me));
        assertEquals(Confirmations.Undo.UNSTATED, Confirmations.undo("give", new JsonObject(), "Alex", me));
    }

    @Test
    void requesterQuestionsAreLimited() throws Exception {
        for (int i = 0; i < Confirmations.PER_REQUESTER; i++) c.ask(Confirmations.Kind.REQUESTER, gm, "command", cmd("kill @e" + i), alex, Node.COMMAND, alex, "Alex");
        assertEquals(429, assertThrows(RpcException.class, () -> c.ask(Confirmations.Kind.REQUESTER, gm, "command", cmd("kill @a"),
                alex, Node.COMMAND, alex, "Alex")).status(), "one player gets at most a few questions at once");
        assertEquals(400, assertThrows(RpcException.class, () -> c.ask(Confirmations.Kind.REQUESTER, gm, "command",
                cmd("say " + "x".repeat(Confirmations.MAX_SHOWN)), UUID.randomUUID(), Node.COMMAND, UUID.randomUUID(), "B")).status(),
                "a call too long to show in full is refused, never shown clipped");
        assertFalse(Confirmations.render("command", cmd("say a§cb\nc")).contains("§"), "shown verbatim");
    }

    @Test
    void onlyTheRequesterDecidesAndTheFirstAnswerCounts() throws Exception {
        UUID bob = UUID.randomUUID();
        var p = c.ask(Confirmations.Kind.REQUESTER, gm, "command", cmd("kill @e"), alex, Node.COMMAND, alex, "Alex");
        assertNotEquals(p.id, p.nonce(), "the agent's id is not the nonce");
        assertTrue(c.decide(p, bob, "Bob", true, n -> true).contains("only Alex"));
        assertNull(c.decide(p, alex, "Alex", false, n -> true));
        assertEquals(Confirmations.State.DENIED, p.state());
        assertTrue(c.decide(p, alex, "Alex", true, n -> true).contains("already"), "the first valid answer decides");
        assertEquals("/kill @e", p.describe().get("call").getAsString());
    }

    @Test
    void questionsExpire() throws Exception {
        var p = c.ask(Confirmations.Kind.REQUESTER, gm, "command", cmd("kill @e"), alex, Node.COMMAND, alex, "Alex");
        now.addAndGet(Confirmations.REQUESTER_TTL_MS);
        assertEquals(1, c.expire(now.get()).size());
        assertEquals(Confirmations.State.EXPIRED, p.state());
        assertTrue(p.describe().get("state").getAsString().equals("expired"));
    }

    @Test
    void adminsAreNeverFlooded() throws Exception {
        UUID verifiedAdmin = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        var first = c.ask(Confirmations.Kind.ADMIN, gm, "build", JsonParser.parseString("{\"ops\":[1]}").getAsJsonObject(), null,
                Node.BUILD, alex, "Alex");
        // The same request again merges, from anyone.
        var again = c.ask(Confirmations.Kind.ADMIN, gm, "build", JsonParser.parseString("{\"ops\":[1]}").getAsJsonObject(), null,
                Node.BUILD, UUID.randomUUID(), "Sam");
        assertSame(first, again);
        assertEquals(2, again.describe().get("count").getAsInt());
        // A different request from the same player within 10 minutes: refused, nobody is asked.
        RpcException e = assertThrows(RpcException.class, () -> c.ask(Confirmations.Kind.ADMIN, gm, "build",
                JsonParser.parseString("{\"ops\":[2]}").getAsJsonObject(), null, Node.BUILD, alex, "Alex"));
        assertEquals(429, e.status());
        // At most 20 per hour overall.
        for (int i = 0; i < Confirmations.PER_HOUR - 1; i++) {
            c.ask(Confirmations.Kind.ADMIN, gm, "build", JsonParser.parseString("{\"ops\":[" + (10 + i) + "]}").getAsJsonObject(),
                    null, Node.BUILD, UUID.randomUUID(), "P" + i);
        }
        assertThrows(RpcException.class, () -> c.ask(Confirmations.Kind.ADMIN, gm, "build",
                JsonParser.parseString("{\"ops\":[99]}").getAsJsonObject(), null, Node.BUILD, UUID.randomUUID(), "Late"));
        assertEquals(Confirmations.PER_HOUR, c.waitingForAdmins().size());
        // Only a verified holder of the right decides.
        assertTrue(c.decide(first, UUID.randomUUID(), "Nobody", true, n -> false).contains("do not hold"));
        // Holding the call's own node is not enough: deciding for others needs almighty.approve (final audit m2).
        assertTrue(c.decide(first, verifiedAdmin, "Member", true, n -> n == Node.BUILD).contains("do not hold"));
        assertNull(c.decide(first, verifiedAdmin, "Admin", true, n -> true));
    }

    /** Scenario P10: which teleports are the other player's to allow. */
    @Test
    void teleportPartners() {
        java.util.function.Function<String, String> partner = c -> Confirmations.teleportPartner("command",
                JsonParser.parseString("{\"commands\":[\"" + c + "\"]}").getAsJsonObject(), "Alex");
        assertEquals("Bob", partner.apply("tp Bob"));
        assertEquals("Bob", partner.apply("tp @s Bob"));
        assertEquals("Bob", partner.apply("teleport Alex Bob"));
        assertEquals("Bob", partner.apply("minecraft:tp Bob Alex"));
        assertEquals(null, partner.apply("tp @s 0 64 0"), "a place: the asker's own move");
        assertEquals(null, partner.apply("tp Bob Carl"), "two others: the asker confirms, as before");
        assertEquals(null, partner.apply("tp @a Bob"));
        assertEquals(null, partner.apply("execute as Bob run tp Alex"));
    }
}
