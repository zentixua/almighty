package ua.zentix.almighty.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.params;

/**
 * The red button and the audit (plan step 4): a paused agent may only read, its jobs freeze in the queue until
 * resumed, other agents go on; every call with effects and every refusal lands in the audit with its agent.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmAuditGameTests {
    private GmAuditGameTests() {}

    private static final Agents AGENTS = Agents.of(Map.of("builder", "b".repeat(32), "gm", "g".repeat(32)));
    private static final Caller BUILDER = new Caller(AGENTS.get("builder"), "test");
    private static final Caller GM = new Caller(AGENTS.get("gm"), "test");

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
    }

    private static CompletableFuture<JsonElement> as(GmServer gm, Caller c, String method, JsonObject p) throws RpcException {
        return gm.dispatch().invoke(c, method, p);
    }

    @GameTest(template = "floor", batch = "gm_audit", timeoutTicks = 400)
    public static void pauseFreezesOneAgent(GameTestHelper h) throws RpcException {
        GmServer gm = gm(h);
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        // Not joined: its wait completes off the server thread (joining here would deadlock the tick).
        as(gm, BUILDER, "build", params(
                "{\"ops\":[{\"op\":\"set\",\"pos\":[%d,%d,%d],\"block\":\"minecraft:gold_block\"}],\"wait\":0}", at.getX(), at.getY(), at.getZ()));
        long job = 0;
        for (JsonElement j : gm.jobs().describe()) job = Math.max(job, j.getAsJsonObject().get("id").getAsLong());
        check(gm.jobs().get(job).agent().equals("builder"), "job agent: " + gm.jobs().get(job).agent());
        gm.pause("builder");
        try {
            try {
                as(gm, BUILDER, "command", params("{\"commands\":[\"say hi\"]}"));
                throw new GameTestAssertException("a paused agent ran a command");
            } catch (RpcException e) {
                check(e.status() == 423, "paused: " + e.status());
            }
            as(gm, BUILDER, "status", new JsonObject()).join();
            // A view runs as a job too, but has no effects: a paused agent can still look.
            as(gm, BUILDER, "map", params("{\"center\":[%d,%d],\"size\":16,\"wait\":0}", at.getX(), at.getZ()));
            as(gm, GM, "command", params("{\"commands\":[\"say still here\"]}")).join();
        } catch (RuntimeException e) {
            gm.pause().resume(null);
            throw e;
        }
        var audit = gm.audit().recent(20, e -> true).toString();
        check(audit.contains("\"outcome\":\"paused\""), "refusal audited: " + audit);
        check(!audit.contains("\"method\":\"status\""), "a successful read is not audited");
        long id = job;
        h.runAfterDelay(20, () -> {
            boolean frozen = !gm.jobs().get(id).state().finished() && h.getLevel().getBlockState(at).isAir();
            boolean looked = false;
            for (JsonElement j : gm.jobs().describe()) {
                JsonObject o = j.getAsJsonObject();
                if (o.get("agent").getAsString().equals("builder") && !o.get("kind").getAsString().equals("build")) {
                    looked = !o.get("state").getAsString().equals("queued") && !o.get("state").getAsString().equals("waiting");
                }
            }
            // Resume first: a failed check must not leave the agent paused for the batches after this one.
            gm.pause().resume("builder");
            check(frozen, "frozen while paused");
            check(looked, "the paused agent's view ran: " + gm.jobs().describe());
            h.succeedWhen(() -> {
                check(h.getLevel().getBlockState(at).is(Blocks.GOLD_BLOCK),
                        String.format(Locale.ROOT, "resumed build not done: %s", gm.jobs().get(id).state()));
                // The build's audit line is written when its call returns (after its wait, off the server thread).
                String log = gm.audit().recent(50, e -> true).toString();
                check(log.contains("\"agent\":\"builder\",\"address\":\"test\",\"method\":\"build\"") && log.contains("\"job\":" + id),
                        "build audited with its job: " + log);
            });
        });
    }
}
