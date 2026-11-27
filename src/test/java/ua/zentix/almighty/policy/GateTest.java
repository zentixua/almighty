package ua.zentix.almighty.policy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gate: floor without a ticket, requester's rights capped by the ceiling with one, deny by default. */
class GateTest {
    /** Every method of Api (kept in step by GmGateGameTests, which checks the live table). */
    static final List<String> METHODS = List.of("status", "notes", "players", "player", "entities", "command", "function", "say",
            "blocks", "map", "look", "build", "undo", "jobs", "job", "cancel", "area.prepare", "area.release", "areas", "bots",
            "bot", "bot.spawn", "bot.remove", "bot.act", "see", "ships", "script", "rule.add", "rule.remove", "rules",
            "event.types", "events", "audit", "give", "pending", "changes", "file");

    private final Agents agents = Agents.of(Map.of("gm", "g".repeat(32), "mod", "m".repeat(32)));
    private final Caller gm = new Caller(agents.get("gm"), "127.0.0.1");
    private final Tickets tickets = new Tickets(System::currentTimeMillis);
    private final UUID guest = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    /** A fake permission handler: admin holds everything up to admin, guest only guest nodes. */
    private final Gate.Holder holder = (id, node) -> (id.equals(admin) ? Role.ADMIN : Role.GUEST).atLeast(node.defaultRole());

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private Gate gate(String toml) throws Exception {
        return new Gate(Policies.of(Policy.parse(toml)), tickets, holder, name -> name.equals("Robo"));
    }

    @Test
    void everyMethodDeclaresANode() throws Exception {
        for (String m : METHODS) assertNotNull(Gate.node(m, new JsonObject(), n -> false), m);
        assertNull(Gate.node("delete.world", new JsonObject(), n -> false), "unknown methods have none: refused");
    }

    @Test
    void privateIsTodaysBehaviour() throws Exception {
        Gate g = gate("preset = \"private\"");
        for (String m : METHODS) assertDoesNotThrow(() -> g.check(gm, m, new JsonObject()), m);
        assertThrows(RpcException.class, () -> g.check(gm, "delete.world", new JsonObject()), "deny by default");
    }

    @Test
    void noTicketNeverExceedsTheFloor() throws Exception {
        Gate g = gate("preset = \"friends\"");
        g.check(gm, "build", new JsonObject());
        RpcException e = assertThrows(RpcException.class, () -> g.check(gm, "script", json("{\"code\":\"1\"}")));
        assertEquals(403, e.status());
        assertEquals("policy", e.code());
        assertEquals("almighty.engine", e.details().get("node").getAsString());
        assertTrue(e.details().getAsJsonArray("allowed").toString().contains("almighty.build"), "the hint lists what is allowed");
        assertThrows(RpcException.class, () -> g.check(gm, "player", json("{\"name\":\"Bob\"}")));
        Gate pub = gate("preset = \"public\"");
        assertThrows(RpcException.class, () -> pub.check(gm, "build", new JsonObject()), "public floor is guest");
        assertThrows(RpcException.class, () -> pub.check(gm, "say", json("{\"text\":\"hi\"}")), "everyone needs speak.all");
        assertThrows(RpcException.class, () -> pub.check(gm, "say", json("{\"text\":\"hi\",\"to\":[\"Bob\"]}")),
                "unasked, even a whisper is speak.all: speak.reply is a reply to the requester alone");
        String t = Tickets.newId();
        tickets.mint(t, guest, "Guest", "gm", 1, true);
        pub.check(gm, "say", json("{\"text\":\"hi\",\"to\":[\"guest\"],\"for\":\"" + t + "\"}"));
        pub.check(gm, "say", json("{\"text\":\"hi\",\"to\":\"Guest\",\"style\":\"actionbar\",\"for\":\"" + t + "\"}"));
        for (String bad : new String[]{"\"to\":null", "\"to\":[\"Guest\",\"Bob\"]", "\"to\":[\"Guest\"],\"style\":\"TITLE\""}) {
            assertThrows(RpcException.class, () -> pub.check(gm, "say", json("{\"text\":\"hi\"," + bad + ",\"for\":\"" + t + "\"}")),
                    bad + ": not a private reply to the requester");
        }
    }

    @Test
    void ticketsUseTheRequestersRightsCappedByTheCeiling() throws Exception {
        Gate g = gate("preset = \"private\"");
        String t = Tickets.newId();
        tickets.mint(t, guest, "Guest", "gm", 1, true);
        // In private the floor is owner, yet a guest's request runs with the guest's rights: a ticket only narrows.
        assertThrows(RpcException.class, () -> g.check(gm, "build", json("{\"for\":\"" + t + "\"}")));
        g.check(gm, "status", json("{\"for\":\"" + t + "\"}"));
        String a = Tickets.newId();
        tickets.mint(a, admin, "Admin", "mod", 2, true);
        Caller mod = new Caller(agents.get("mod"), "127.0.0.1");
        Gate f = gate("preset = \"friends\"\n[agents.mod]\nceiling = \"builder\"");
        f.check(mod, "area.prepare", json("{\"for\":\"" + a + "\"}"));
        RpcException e = assertThrows(RpcException.class, () -> f.check(mod, "player", json("{\"for\":\"" + a + "\"}")));
        assertTrue(e.getMessage().contains("ceiling"), "an admin's request is still capped by the agent's ceiling: " + e.getMessage());
        assertThrows(RpcException.class, () -> f.check(gm, "status", json("{\"for\":\"" + a + "\"}")), "another agent's ticket");
    }

    @Test
    void nodesFollowParameters() throws Exception {
        assertEquals(Node.OBSERVE_WORLD, Gate.node("see", json("{\"name\":\"Robo\"}"), n -> n.equals("Robo")), "a bot's eyes");
        assertEquals(Node.OBSERVE_PLAYERS, Gate.node("see", json("{\"name\":\"Bob\"}"), n -> false), "a player's eyes");
        assertEquals(Node.ENGINE, Gate.node("rule.add", json("{\"script\":\"x\"}"), n -> false));
        assertEquals(Node.RULES, Gate.node("rule.add", json("{\"event\":\"x\"}"), n -> false));
        assertEquals(Node.BOTS_ADMIN, Gate.node("bot.spawn", json("{\"gamemode\":\"creative\"}"), n -> false));
        assertEquals(Node.BOTS_ADMIN, Gate.node("bot.spawn", json("{\"marker\":false}"), n -> false));
        assertEquals(Node.BOTS, Gate.node("bot.spawn", json("{\"gamemode\":\"survival\"}"), n -> false));
        assertEquals(Node.BOTS_ADMIN, Gate.node("bot.act", json("{\"actions\":[{\"payload\":\"x:y\"}]}"), n -> false));
        assertEquals(Node.NOTES_WRITE, Gate.node("notes", json("{\"id\":\"world:a\",\"text\":\"t\"}"), n -> false));
        assertEquals(Node.SPEAK_ALL, Gate.node("say", json("{\"to\":[\"a\"],\"style\":\"title\"}"), n -> false));
        assertEquals(Node.OBSERVE_PLAYERS, Gate.node("entities", json("{\"center\":[0,0,0]}"), n -> false), "players are entities");
        assertEquals(Node.OBSERVE_WORLD, Gate.node("entities", json("{\"type\":\"zombie\"}"), n -> false));
        assertEquals(Set.of(), Set.of());
    }

    @Test
    void malformedParametersAre400() {
        for (String[] c : new String[][]{{"see", "{\"name\":{}}"}, {"entities", "{\"type\":[]}"}, {"bot.spawn", "{\"gamemode\":{}}"}}) {
            RpcException e = assertThrows(RpcException.class, () -> Gate.node(c[0], json(c[1]), n -> false));
            assertEquals(400, e.status(), c[0] + " " + c[1]);
        }
        assertDoesNotThrow(() -> Gate.node("bot.spawn", json("{\"marker\":null}"), n -> false));
    }
}
