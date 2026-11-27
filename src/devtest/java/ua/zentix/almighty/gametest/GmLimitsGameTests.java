package ua.zentix.almighty.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.policy.Policy;

import java.util.Map;
import java.util.UUID;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.params;
import static ua.zentix.almighty.gametest.GmGameTests.player;

/**
 * Content limits (plan step 7): below owner a build takes block states only and never a command, structure or jigsaw
 * block; give hands the requester plain items, never #almighty:never_give; creative bots need bots.admin.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmLimitsGameTests {
    private GmLimitsGameTests() {}

    private static final Caller GM = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "test");

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
    }

    /** A refusal (at once, or a future already failed): never joins a call that would need another tick. */
    private static RpcException refused(GmServer gm, String method, JsonObject p) {
        java.util.concurrent.CompletableFuture<?> f;
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
                throw e;
            }
        }
        throw new GameTestAssertException(method + " was allowed: " + p + (f.isDone() ? " → " + f.join() : " (still running)"));
    }

    private static String set(BlockPos at, String block) {
        return String.format(java.util.Locale.ROOT, "{\"ops\":[{\"op\":\"set\",\"pos\":[%d,%d,%d],\"block\":\"%s\"}],\"wait\":0}",
                at.getX(), at.getY(), at.getZ(), block.replace("\"", "\\\""));
    }

    @GameTest(template = "floor", batch = "gm_limits")
    public static void buildsBelowOwnerTakeStatesOnly(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        try {
            gm.policies().set(Policy.parse("preset = \"friends\""));
            RpcException cb = refused(gm, "build", params(set(at, "minecraft:command_block")));
            check(cb.code().equals("policy"), "command block: " + cb.code() + " " + cb.getMessage());
            check(refused(gm, "build", params(set(at, "minecraft:jigsaw"))).code().equals("policy"), "jigsaw");
            RpcException nbt = refused(gm, "build", params(set(at, "minecraft:oak_sign{front_text:{messages:['\"a\"','\"\"','\"\"','\"\"']}}")));
            check(nbt.getMessage().contains("block data"), "sign with data: " + nbt.getMessage());
            gm.dispatch().invoke(GM, "build", params(set(at, "minecraft:oak_sign[rotation=4]")));
            RpcException palette = refused(gm, "build", params("{\"ops\":[{\"op\":\"layers\",\"origin\":[%d,%d,%d],\"palette\":{\"#\":\"minecraft:repeating_command_block\"},\"layers\":[[\"#\"]]}],\"wait\":0}",
                    at.getX(), at.getY(), at.getZ()));
            check(palette.code().equals("policy"), "a layers palette is checked too: " + palette.getMessage());
            // Owner (private: the floor is owner): anything, as before.
            gm.policies().set(Policy.parse("preset = \"private\""));
            gm.dispatch().invoke(GM, "build", params(set(at.east(), "minecraft:command_block")));
        } finally {
            gm.policies().set(before);
        }
        h.succeed();
    }

    /** An owner's build undone, then a member-level call tries to undo the undo (a redo of the command block): refused. */
    @GameTest(template = "floor", batch = "gm_limits", timeoutTicks = 200)
    public static void noRedoBelowOwner(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        BlockPos at = h.absolutePos(new BlockPos(4, 2, 4));
        gm.policies().set(Policy.parse("preset = \"private\""));
        // Job ids from the calls' own answers (other batches' jobs share the queue).
        long[] ids = new long[2];
        gm.dispatch().invoke(GM, "build", params(set(at, "minecraft:command_block{Command:\"say hi\"}")))
                .thenAccept(d -> ids[0] = d.getAsJsonObject().get("id").getAsLong());
        h.runAfterDelay(10, () -> {
            check(ids[0] > 0, "build answered");
            try {
                gm.dispatch().invoke(GM, "undo", params("{\"job\":%d,\"wait\":0}", ids[0]))
                        .thenAccept(d -> ids[1] = d.getAsJsonObject().get("id").getAsLong());
            } catch (RpcException e) {
                throw new GameTestAssertException("owner undo: " + e.getMessage());
            }
        });
        h.runAfterDelay(30, () -> {
            check(ids[1] > 0, "undo answered");
            try {
                gm.policies().set(Policy.parse("preset = \"friends\""));
                RpcException e = refused(gm, "undo", params("{\"job\":%d,\"wait\":0}", ids[1]));
                check(e.status() == 403 && e.getMessage().contains("redo"), "redo below owner: " + e.getMessage());
            } catch (Policy.Invalid e) {
                throw new GameTestAssertException(e.getMessage());
            } finally {
                gm.policies().set(before);
            }
            h.succeed();
        });
    }

    @GameTest(template = "floor", batch = "gm_limits")
    public static void giveOnlyPlainItemsToTheRequester(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        ServerPlayer alex = player(h, "limits-alex", UUID.randomUUID());
        try {
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\""));
            long after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
            h.getLevel().getServer().getCommands().performPrefixedCommand(alex.createCommandSourceStack(), "gm some bread please");
            String t = null;
            for (JsonElement e : gm.feed().read(after, 100, 0).getAsJsonArray("events")) {
                if (e.getAsJsonObject().get("type").getAsString().equals("gm")) t = e.getAsJsonObject().get("ticket").getAsString();
            }
            JsonObject got = gm.dispatch().invoke(GM, "give", params("{\"item\":\"minecraft:bread\",\"count\":3,\"for\":\"%s\"}", t)).join().getAsJsonObject();
            check(got.get("count").getAsInt() == 3 && alex.getInventory().countItem(Items.BREAD) == 3, "bread given: " + got);
            check(refused(gm, "give", params("{\"item\":\"minecraft:command_block\",\"for\":\"%s\"}", t)).status() == 403, "never_give");
            check(refused(gm, "give", params("{\"item\":\"minecraft:diamond_sword[enchantments={levels:{sharpness:255}}]\",\"for\":\"%s\"}", t))
                    .status() == 400, "components");
            check(refused(gm, "give", params("{\"item\":\"minecraft:bread\"}")).status() == 400, "only for a requester");
            check(refused(gm, "bot.spawn", params("{\"name\":\"LimitsBot\",\"gamemode\":\"creative\"}")).status() == 403, "creative bot at member floor");
            check(refused(gm, "give", params("{\"item\":\"minecraft:wither_spawn_egg\",\"for\":\"%s\"}", t)).status() == 403, "spawn eggs");
            // The hourly item quota: 3 + 64 + 61 = 128 given, one more refused.
            gm.dispatch().invoke(GM, "give", params("{\"item\":\"minecraft:dirt\",\"count\":64,\"for\":\"%s\"}", t)).join();
            gm.dispatch().invoke(GM, "give", params("{\"item\":\"minecraft:dirt\",\"count\":61,\"for\":\"%s\"}", t)).join();
            check(refused(gm, "give", params("{\"item\":\"minecraft:dirt\",\"for\":\"%s\"}", t)).status() == 429, "item quota");
        } finally {
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(alex);
        }
        h.succeed();
    }
}
