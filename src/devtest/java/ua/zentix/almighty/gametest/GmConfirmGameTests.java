package ua.zentix.almighty.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.policy.Confirmations;
import ua.zentix.almighty.policy.Nodes;
import ua.zentix.almighty.policy.Policy;

import java.util.Map;
import java.util.UUID;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.params;
import static ua.zentix.almighty.gametest.GmGameTests.player;

/**
 * Origins and confirmations (plan step 6): a request's command runs as the requesting player at their level; an
 * irreversible request waits until that player confirms the exact call; a request above the player's rights reaches
 * an admin only if its kind is in ask_admin, and then runs with the deciding admin's rights.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmConfirmGameTests {
    private GmConfirmGameTests() {}

    private static final Caller GM = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "test");

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
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
        throw new GameTestAssertException("no /gm event");
    }

    private static void op(GameTestHelper h, ServerPlayer p, int level) {
        h.getLevel().getServer().getPlayerList().getOps().add(new ServerOpListEntry(p.getGameProfile(), level, false));
        Nodes.refresh(h.getLevel().getServer());
    }

    private static void deop(GameTestHelper h, ServerPlayer p) {
        h.getLevel().getServer().getPlayerList().getOps().remove(p.getGameProfile());
    }

    @GameTest(template = "floor", batch = "gm_confirm")
    public static void requestsRunAsTheRequester(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        ServerPlayer alex = player(h, "confirm-alex", UUID.randomUUID());
        try {
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\""));
            Nodes.refresh(h.getLevel().getServer());
            String t = ticket(h, gm, alex, "make it day");
            JsonArray r = gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"time set day\"],\"for\":\"%s\"}", t)).join().getAsJsonArray();
            check(!r.get(0).getAsJsonObject().get("ok").getAsBoolean(), "a member cannot set the time (vanilla needs level 2): " + r);
            op(h, alex, 2);
            JsonArray ok = gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"time set day\"],\"for\":\"%s\"}", t)).join().getAsJsonArray();
            check(ok.get(0).getAsJsonObject().get("ok").getAsBoolean(), "as a level-2 operator it runs: " + ok);
            // Unasked, the agent runs commands at its floor (member: level 0).
            JsonArray floor = gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"time set day\"]}")).join().getAsJsonArray();
            check(!floor.get(0).getAsJsonObject().get("ok").getAsBoolean(), "unasked at the member floor: " + floor);
        } finally {
            deop(h, alex);
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(alex);
        }
        h.succeed();
    }

    @GameTest(template = "floor", batch = "gm_confirm")
    public static void irreversibleWaitsForTheRequester(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        ServerPlayer alex = player(h, "confirm-bea", UUID.randomUUID());
        try {
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\""));
            op(h, alex, 2);
            String t = ticket(h, gm, alex, "kill the pigs");
            String id;
            try {
                gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"kill @e[type=pig]\"],\"for\":\"%s\"}", t));
                throw new GameTestAssertException("an irreversible request ran without confirmation");
            } catch (RpcException e) {
                check(e.status() == 202 && e.code().equals("pending"), "pending: " + e.status() + " " + e.getMessage());
                id = e.details().get("id").getAsString();
            }
            Confirmations.Pending p = gm.decisions().confirmations().get(id);
            check(!e2s(gm.decisions().decide(p.nonce(), null, true)).startsWith("Allowed"), "the console cannot answer for the requester");
            String answer = gm.decisions().decide(p.nonce(), alex, true);
            check(answer.startsWith("Allowed"), answer);
            JsonObject state = gm.decisions().pending("gm", id, 0).join();
            check(state.get("state").getAsString().equals("done"), "ran after confirmation: " + state);
        } finally {
            deop(h, alex);
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(alex);
        }
        h.succeed();
    }

    private static String e2s(String s) {
        return s;
    }

    @GameTest(template = "floor", batch = "gm_confirm")
    public static void askAdminOnlyForListedKinds(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        ServerPlayer guest = player(h, "confirm-guest", UUID.randomUUID());
        ServerPlayer admin = player(h, "confirm-admin", UUID.randomUUID());
        try {
            gm.policies().set(Policy.parse("preset = \"public\"\nidentity = \"proxy\"\n[safety]\nask_admin = [\"area.prepare\"]"));
            op(h, admin, 3);
            String t = ticket(h, gm, guest, "load the arena");
            try {
                gm.dispatch().invoke(GM, "build", params("{\"ops\":[],\"for\":\"%s\"}", t));
                throw new GameTestAssertException("a guest built");
            } catch (RpcException e) {
                check(e.status() == 403, "not on ask_admin: refused, nobody is asked: " + e.status());
            }
            String id;
            try {
                gm.dispatch().invoke(GM, "area.prepare", params("{\"from\":[0,0],\"to\":[1,1],\"for\":\"%s\"}", t));
                throw new GameTestAssertException("a guest prepared an area");
            } catch (RpcException e) {
                check(e.status() == 202, "on ask_admin: waits for an admin: " + e.status() + " " + e.getMessage());
                id = e.details().get("id").getAsString();
            }
            Confirmations.Pending p = gm.decisions().confirmations().get(id);
            check(gm.decisions().decide(p.nonce(), guest, true).startsWith("Not decided"), "the guest cannot approve themself");
            check(gm.decisions().decide(p.nonce(), admin, true).startsWith("Allowed"), "the admin approves");
            h.succeedWhen(() -> {
                String state = p.describe().get("state").getAsString();
                check(state.equals("done"), "ran with the admin's rights: " + p.describe());
                gm.areas().releaseAll();
            });
        } finally {
            deop(h, admin);
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(guest);
            h.getLevel().getServer().getPlayerList().remove(admin);
        }
    }
}
