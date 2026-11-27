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

    @Test
    void irreversibleForOthersNeedsTheRequester() {
        Confirmations.players(n -> n.equalsIgnoreCase("Bob"));
        assertTrue(Confirmations.needsRequester("command", cmd("kill @e[type=zombie]"), "Alex", Set.of()));
        assertTrue(Confirmations.needsRequester("command", cmd("give Bob diamond 64"), "Alex", Set.of()));
        assertTrue(Confirmations.needsRequester("command", cmd("execute as @a run clear"), "Alex", Set.of()), "chains");
        assertTrue(Confirmations.needsRequester("command", cmd("minecraft:ban Bob"), "Alex", Set.of()), "namespaced");
        assertFalse(Confirmations.needsRequester("command", cmd("give @s diamond 1"), "Alex", Set.of()), "on oneself");
        assertFalse(Confirmations.needsRequester("command", cmd("give Alex diamond 1"), "Alex", Set.of()));
        assertFalse(Confirmations.needsRequester("command", cmd("kill"), "Alex", Set.of()), "no target: the requester");
        assertFalse(Confirmations.needsRequester("command", cmd("time set day"), "Alex", Set.of()), "reversible");
        assertFalse(Confirmations.needsRequester("command", cmd("give @s diamond_sword"), "Alex", Set.of()), "item names are not players");
        assertTrue(Confirmations.needsRequester("build", new JsonObject(), "Alex", Set.of("build")), "confirm_first");
        assertTrue(Confirmations.needsRequester("command", cmd("time set day"), "Alex", Set.of("time")));
        Confirmations.players(n -> true);
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
        assertNull(c.decide(first, verifiedAdmin, "Admin", true, n -> true));
    }
}
