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
import ua.zentix.almighty.platform.Mc;
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
    public static void everyLiveMethodHasANode(GameTestHelper h) throws RpcException {
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
            // GameTest runs offline: identity proxy lets the test's version-4 UUIDs count as verified.
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\"\nowners = [\"" + ownerId + "\"]"));
            RpcException e = refused(gm, "script", params("{\"code\":\"1 + 1\"}"));
            check(e.status() == 403 && e.code().equals("policy") && e.details().has("allowed"), "unasked script on friends: " + e.getMessage());
            // Unasked, a command runs at the floor's permission level (member: 0), so vanilla refuses /op itself.
            JsonArray op = gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"op gate-offline\"]}")).join().getAsJsonArray();
            check(!op.get(0).getAsJsonObject().get("ok").getAsBoolean(), "unasked /op at the member floor: " + op);
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

    /** Undo or cancel of another agent's job needs undo.any (admin), even for an agent that may build. */
    @GameTest(template = "floor", batch = "gm_gate", timeoutTicks = 100)
    public static void othersJobsNeedUndoAny(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        Agents two = Agents.of(Map.of("gm", "g".repeat(32), "builder", "b".repeat(32)));
        Caller builder = new Caller(two.get("builder"), "test"), gmAgent = new Caller(two.get("gm"), "test");
        gm.policies().set(Policy.parse("preset = \"friends\""));
        try {
            var at = h.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3));
            gm.dispatch().invoke(builder, "build", params("{\"ops\":[{\"op\":\"set\",\"pos\":[%d,%d,%d],\"block\":\"minecraft:stone\"}],\"wait\":0}",
                    at.getX(), at.getY(), at.getZ()));
            long job = 0;
            for (JsonElement j : gm.jobs().describe()) job = Math.max(job, j.getAsJsonObject().get("id").getAsLong());
            try {
                gm.dispatch().invoke(gmAgent, "cancel", params("{\"id\":%d}", job)).join();
                throw new GameTestAssertException("cancelled another agent's job");
            } catch (java.util.concurrent.CompletionException e) {
                check(e.getCause() instanceof RpcException r && r.status() == 403, "refused: " + e.getCause());
            }
            gm.dispatch().invoke(builder, "cancel", params("{\"id\":%d}", job)).join();
        } finally {
            gm.policies().set(before);
        }
        h.succeed();
    }

    private static String ticket(GameTestHelper h, GmServer gm, ServerPlayer p, String text) throws InterruptedException {
        long after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "gm " + text);
        for (JsonElement e : gm.feed().read(after, 100, 0).getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("type").getAsString().equals("gm") && o.get("player").getAsString().equals(Mc.name(p.getGameProfile()))) {
                return o.get("ticket").getAsString();
            }
        }
        throw new GameTestAssertException("no /gm event for " + Mc.name(p.getGameProfile()));
    }
}
