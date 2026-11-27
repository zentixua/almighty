package ua.zentix.almighty.gametest;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.audit.Journal;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;

import java.util.Map;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.params;

/**
 * The change journal (plan step 8): block writes of any GM call — a command, a script — are recorded on disk as
 * they happen; undo of a change restores only positions that still hold what the change put there.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmJournalGameTests {
    private GmJournalGameTests() {}

    private static final Caller GM = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "test");

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
    }

    private static long newest(GmServer gm) {
        return gm.journal().list(1).get(0).getAsJsonObject().get("id").getAsLong();
    }

    @GameTest(template = "floor", batch = "gm_journal", timeoutTicks = 200)
    public static void commandWritesRevertConflictAware(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        BlockPos a = h.absolutePos(new BlockPos(1, 2, 1)), b = h.absolutePos(new BlockPos(3, 2, 3));
        gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"fill %d %d %d %d %d %d minecraft:stone\"]}",
                a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ())).join();
        long id = newest(gm);
        JsonObject meta = gm.journal().describe(id);
        check(meta.get("method").getAsString().equals("command") && meta.get("positions").getAsLong() == 9, "the fill was recorded: " + meta);
        var blocks = h.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK);
        Journal.Records r = gm.journal().read(id, tag -> NbtUtils.readBlockState(blocks, tag));
        check(r.size() == 9 && r.after()[0].is(Blocks.STONE) && r.before()[0].isAir(), "on disk, before and after: " + r.size());
        // Someone else changes one of those positions afterwards: the revert leaves it.
        h.getLevel().setBlockAndUpdate(a, Blocks.GOLD_BLOCK.defaultBlockState());
        gm.dispatch().invoke(GM, "undo", params("{\"change\":%d,\"wait\":0}", id));
        h.succeedWhen(() -> {
            check(h.getLevel().getBlockState(a).is(Blocks.GOLD_BLOCK), "a position changed since stays as it is");
            check(h.getLevel().getBlockState(b).isAir(), "the others go back: " + h.getLevel().getBlockState(b));
            check(gm.journal().describe(id).has("undone_by"), "marked undone");
        });
    }

    @GameTest(template = "floor", batch = "gm_journal", timeoutTicks = 200)
    public static void scriptWritesAreRecorded(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        BlockPos p = h.absolutePos(new BlockPos(5, 2, 5));
        JsonObject r = gm.dispatch().invoke(GM, "script", params("{\"code\":\"gm.level(\\\"minecraft:overworld\\\").setBlockAndUpdate(new BlockPos(%d, %d, %d), Blocks.DIAMOND_BLOCK.defaultBlockState()); 1\"}",
                p.getX(), p.getY(), p.getZ())).join().getAsJsonObject();
        check(r.get("ok").getAsBoolean() && h.getLevel().getBlockState(p).is(Blocks.DIAMOND_BLOCK), "script ran: " + r);
        long id = newest(gm);
        check(gm.journal().describe(id).get("method").getAsString().equals("script"), "the script's write is a change");
        gm.dispatch().invoke(GM, "undo", params("{\"change\":%d,\"wait\":0}", id));
        h.succeedWhen(() -> check(h.getLevel().getBlockState(p).isAir(), "reverted"));
    }

    /**
     * A crash mid-build leaves an undoable journal: while a slow build still runs, what it wrote is already on disk,
     * and a journal opened afresh on the same directory (a restart) lists the change as interrupted and reads it.
     */
    @GameTest(template = "floor", batch = "gm_journal_crash", timeoutTicks = 200)
    public static void crashMidBuildLeavesAnUndoableJournal(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        java.util.function.LongSupplier clock = GmServer.clock;
        java.util.concurrent.atomic.AtomicLong t = new java.util.concurrent.atomic.AtomicLong();
        // One work unit per tick: the build takes many ticks.
        GmServer.clock = () -> t.addAndGet(1_000_000_000L);
        BlockPos a = h.absolutePos(new BlockPos(0, 2, 0)), b = h.absolutePos(new BlockPos(7, 5, 7));
        gm.dispatch().invoke(GM, "build", params("{\"ops\":[{\"op\":\"fill\",\"from\":[%d,%d,%d],\"to\":[%d,%d,%d],\"block\":\"minecraft:stone\"}],\"wait\":0}",
                a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        long id = newest(gm);
        h.runAfterDelay(5, () -> {
            try {
                check(gm.journal().describe(id).has("open"), "the build is still running");
                java.nio.file.Path dir = h.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("almighty").resolve("changes");
                Journal restarted = new Journal(dir);
                JsonObject meta = restarted.describe(id);
                check(meta != null && meta.has("interrupted"), "after a restart the change is listed as interrupted: " + meta);
                var blocks = h.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK);
                Journal.Records r = restarted.read(id, tag -> NbtUtils.readBlockState(blocks, tag));
                check(r.size() > 0 && r.size() < 256, "what was written so far is on disk: " + r.size());
            } catch (java.io.IOException e) {
                throw new GameTestAssertException(e.toString());
            } finally {
                GmServer.clock = clock;
            }
            h.succeed();
        });
    }
}
