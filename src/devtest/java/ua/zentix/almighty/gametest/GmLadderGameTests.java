package ua.zentix.almighty.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
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
import ua.zentix.almighty.policy.Node;
import ua.zentix.almighty.policy.Nodes;
import ua.zentix.almighty.policy.Policy;
import ua.zentix.almighty.policy.Role;
import ua.zentix.almighty.world.Claims;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.params;
import static ua.zentix.almighty.gametest.GmGameTests.player;

/**
 * Presets, zones, roles, quotas and claims (plan step 9): a zone that denies building, an arena the agent may build in
 * on its own, a granted role, a permission handler (the LuckPerms path), the hourly block quota, and a claim mod.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmLadderGameTests {
    private GmLadderGameTests() {}

    private static final Caller GM = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "test");

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
    }

    private static String ticket(GameTestHelper h, GmServer gm, ServerPlayer p) throws InterruptedException {
        long after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "gm please build");
        for (JsonElement e : gm.feed().read(after, 100, 0).getAsJsonArray("events")) {
            if (e.getAsJsonObject().get("type").getAsString().equals("gm")) return e.getAsJsonObject().get("ticket").getAsString();
        }
        throw new GameTestAssertException("no /gm event");
    }

    private static String set(BlockPos at, String extra) {
        return String.format(Locale.ROOT, "{\"ops\":[{\"op\":\"set\",\"pos\":[%d,%d,%d],\"block\":\"minecraft:stone\"}],\"wait\":0%s}",
                at.getX(), at.getY(), at.getZ(), extra);
    }

    /** Refused at once (thrown, or a future already failed); never joins a call that needs another tick. */
    private static RpcException refused(GmServer gm, String method, JsonObject p) {
        CompletableFuture<?> f;
        try {
            f = gm.dispatch().invoke(GM, method, p);
        } catch (RpcException e) {
            return e;
        }
        if (f.isCompletedExceptionally()) {
            try {
                f.join();
            } catch (java.util.concurrent.CompletionException e) {
                if (e.getCause() instanceof RpcException r) return r;
            }
        }
        throw new GameTestAssertException(method + " was allowed: " + p);
    }

    private static String box(BlockPos a, BlockPos b) {
        return String.format(Locale.ROOT, "from = [%d, %d, %d]\nto = [%d, %d, %d]\n", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    @GameTest(template = "floor", batch = "gm_ladder")
    public static void zonesDenyAndAgentMay(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        BlockPos in = h.absolutePos(new BlockPos(2, 2, 2)), out = h.absolutePos(new BlockPos(12, 2, 12));
        try {
            gm.policies().set(Policy.parse("preset = \"friends\"\n[zones.spawn]\n" + box(in.offset(-1, -1, -1), in.offset(1, 1, 1))
                    + "deny = [\"build\"]"));
            check(refused(gm, "build", params(set(in, ""))).getMessage().contains("Zone spawn"), "a zone denies building");
            gm.dispatch().invoke(GM, "build", params(set(out, "")));
            // Public: the agent is a guest unasked, but may build in its arena.
            gm.policies().set(Policy.parse("preset = \"public\"\n[zones.arena]\n" + box(in.offset(-1, -1, -1), in.offset(1, 1, 1))
                    + "agent_may = [\"build\"]"));
            gm.dispatch().invoke(GM, "build", params(set(in, "")));
            check(refused(gm, "build", params(set(out, ""))).status() == 403, "outside the arena the floor applies");
            JsonObject sneaky = params(set(out, ""));
            sneaky.addProperty("\u0000zone_only", true);
            check(refused(gm, "build", sneaky).status() == 403, "the marker cannot be sent by the agent");
        } finally {
            gm.policies().set(before);
        }
        h.succeed();
    }

    @GameTest(template = "floor", batch = "gm_ladder")
    public static void rolesHandlersQuotasClaims(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        UUID id = UUID.randomUUID();
        ServerPlayer guest = player(h, "ladder-guest", id);
        BlockPos at = h.absolutePos(new BlockPos(5, 2, 5));
        try {
            gm.policies().set(Policy.parse("preset = \"public\"\nidentity = \"proxy\"\n[quotas]\nbuild_blocks = 2"));
            String t = ticket(h, gm, guest);
            check(refused(gm, "build", params(set(at, ",\"for\":\"" + t + "\""))).status() == 403, "a guest cannot build");
            // A role from the ladder (what /almighty role does).
            check(gm.roles().set(id, "ladder-guest", Role.MEMBER, null, Role.OWNER, "test") == null, "role granted");
            gm.dispatch().invoke(GM, "build", params(set(at, ",\"for\":\"" + t + "\"")));
            // The hourly block quota: 1 used, 2 more would exceed 2.
            String two = String.format(Locale.ROOT, "{\"ops\":[{\"op\":\"fill\",\"from\":[%d,%d,%d],\"to\":[%d,%d,%d],\"block\":\"minecraft:stone\"}],\"wait\":0,\"for\":\"%s\"}",
                    at.getX(), at.getY() + 1, at.getZ(), at.getX() + 1, at.getY() + 1, at.getZ(), t);
            check(refused(gm, "build", params(two)).status() == 429, "block quota");
            gm.roles().set(id, "ladder-guest", null, null, Role.OWNER, "test");
            // A permission handler deciding (the LuckPerms path): it grants build to this guest.
            Nodes.handler((player, node) -> player.equals(id) && node == Node.BUILD ? Boolean.TRUE : null);
            gm.policies().set(Policy.parse("preset = \"public\"\nidentity = \"proxy\""));
            gm.dispatch().invoke(GM, "build", params(set(at.north(), ",\"for\":\"" + t + "\"")));
            // A claim mod: this chunk is someone else's claim.
            Claims.testSource(new Claims.Source() {
                public String name() {
                    return "test claims";
                }

                public boolean mayBuild(UUID player, net.minecraft.server.level.ServerLevel level, int cx, int cz) {
                    return cx != (at.getX() >> 4) || cz != (at.getZ() >> 4);
                }
            });
            check(refused(gm, "build", params(set(at.north(2), ",\"for\":\"" + t + "\""))).getMessage().contains("claimed"), "claimed chunk");
        } finally {
            Claims.testSource(null);
            Nodes.handler(null);
            gm.roles().set(id, "ladder-guest", null, null, Role.OWNER, "test");
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(guest);
        }
        h.succeed();
    }
}
