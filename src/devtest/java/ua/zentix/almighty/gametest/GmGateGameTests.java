package ua.zentix.almighty.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.policy.Gate;
import ua.zentix.almighty.policy.Policy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.params;
import static ua.zentix.almighty.gametest.GmGameTests.player;

/**
 * The gate on the live server (plan step 5): every method has a node; preset private is today's behaviour; a call
 * without a ticket never exceeds the floor; a ticket runs with that player's rights (offline identities as guests,
 * owners from the policy).
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmGateGameTests {
    private GmGateGameTests() {}

    private static final Caller GM = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "test");

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
    }

    private static RpcException refused(GmServer gm, String method, JsonObject p) {
        try {
            gm.dispatch().invoke(GM, method, p);
        } catch (RpcException e) {
            return e;
        }
        throw new GameTestAssertException(method + " was allowed: " + p);
    }

    @GameTest(template = "floor", batch = "gm_gate")
    public static void everyLiveMethodHasANode(GameTestHelper h) {
        for (String m : gm(h).dispatch().names()) check(Gate.node(m, new JsonObject(), n -> false) != null, "no node for " + m);
        h.succeed();
    }

    @GameTest(template = "floor", batch = "gm_gate")
    public static void floorAndTickets(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        UUID ownerId = UUID.randomUUID();
        ServerPlayer owner = player(h, "gate-owner", ownerId);
        ServerPlayer offline = player(h, "gate-offline", UUID.nameUUIDFromBytes("OfflinePlayer:gate-offline".getBytes(StandardCharsets.UTF_8)));
        try {
            // Private (the default): the agent does everything, as before the gate existed.
            gm.policies().set(Policy.parse("preset = \"private\""));
            gm.dispatch().invoke(GM, "script", params("{\"code\":\"1 + 1\"}"));
            gm.policies().set(Policy.parse("preset = \"friends\"\nowners = [\"" + ownerId + "\"]"));
            RpcException e = refused(gm, "script", params("{\"code\":\"1 + 1\"}"));
            check(e.status() == 403 && e.code().equals("policy") && e.details().has("allowed"), "unasked script on friends: " + e.getMessage());
            check(refused(gm, "command", params("{\"commands\":[\"op gate-offline\"]}")).status() == 403, "unasked command");
            // Tickets from the players' own /gm messages.
            String ownerTicket = ticket(h, gm, owner, "run a script");
            String offlineTicket = ticket(h, gm, offline, "op me");
            gm.dispatch().invoke(GM, "script", params("{\"code\":\"1 + 1\",\"for\":\"%s\"}", ownerTicket));
            check(refused(gm, "build", params("{\"ops\":[],\"for\":\"%s\"}", offlineTicket)).status() == 403,
                    "an unverified player's request cannot build");
            JsonObject status = gm.dispatch().invoke(GM, "status", params("{\"for\":\"%s\"}", offlineTicket)).join().getAsJsonObject();
            JsonArray allowed = status.getAsJsonObject("rights").getAsJsonArray("allowed");
            check(!allowed.toString().contains("almighty.build") && allowed.toString().contains("almighty.observe.world"),
                    "status rights for that request: " + status.get("rights"));
        } finally {
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(owner);
            h.getLevel().getServer().getPlayerList().remove(offline);
        }
        h.succeed();
    }

    private static String ticket(GameTestHelper h, GmServer gm, ServerPlayer p, String text) throws InterruptedException {
        long after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "gm " + text);
        for (JsonElement e : gm.feed().read(after, 100, 0).getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("type").getAsString().equals("gm") && o.get("player").getAsString().equals(p.getGameProfile().getName())) {
                return o.get("ticket").getAsString();
            }
        }
        throw new GameTestAssertException("no /gm event for " + p.getGameProfile().getName());
    }
}
