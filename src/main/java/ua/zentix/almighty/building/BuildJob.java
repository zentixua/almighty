package ua.zentix.almighty.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.work.Budget;
import ua.zentix.almighty.work.Job;
import ua.zentix.almighty.world.Areas;
import ua.zentix.almighty.world.Dims;

import java.util.Arrays;

/**
 * Build or undo in portions between ticks. First await full chunks for the box and its neighbor ring
 * ({@link Areas} lease: background loading without ticking), then place positions in portions of {@value #UNIT}
 * while tick time remains.
 *
 * <p>Place each position as {@code /fill} does ({@code FillCommand}): first {@code getBlockEntity} initializes a
 * fresh chunk's pending block entity (otherwise replacement would leave its data under the new block), snapshot
 * the old block and block entity data, {@code Clearable.tryClear} (chest contents do not spill),
 * {@code BlockInput.place} with {@code UPDATE_CLIENTS}; after the portion, call {@code blockUpdated} for its
 * positions (26.x, as its {@code /fill}: also {@code UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS}, then
 * {@code updateNeighboursOnBlockSet} with the old state, which also runs the old block's removal updates).
 * The snapshot of all changed positions enables undo ({@code undo}), which is itself the same kind
 * of job with its own snapshot. Writes carry {@code UPDATE_SUPPRESS_DROPS}: a neighbour a write breaks (a door's
 * other half, a torch on a removed wall) drops no item (lead's F4); a block broken by the knock-on shape update of a
 * write (a lantern under a removed ceiling, where vanilla clears that flag) drops nothing either ({@link #writing},
 * lead's N9). Undo does not restore entities.
 */
public final class BuildJob extends Job {
    /** Placement flags, as {@code /fill}. */
    //? if >=26.1 {
    /*private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SUPPRESS_DROPS;
    *///?} else {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
    //?}

    @Override
    public boolean effects() {
        return true;
    }

    static final int UNIT = 32;

    /** Changed positions: previous states and block entity data in change order. */
    static final class Snapshot {
        private long[] pos = new long[256];
        private int[] state = new int[256];
        private final Int2ObjectOpenHashMap<CompoundTag> nbt = new Int2ObjectOpenHashMap<>();
        /** For a journal revert: the state each position must still hold to be reverted (conflict-aware undo). */
        private int[] expect;
        private int size;

        /**
         * A job's own undo snapshot: a position goes back only while it still holds the block the job put there (any
         * state of it: wire power, a door opened); a journal revert compares the exact state.
         */
        private boolean blockOnly;
        /** A job's snapshot: the contents (hash) each write left in a container, by index; absent: left none. */
        private final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap contents = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

        /** {@code s} and {@code tag}: what the position held before; {@code now}: the state id the write left there. */
        void add(BlockPos p, BlockState s, CompoundTag tag, int now) {
            if (size == pos.length) {
                pos = Arrays.copyOf(pos, size * 2);
                state = Arrays.copyOf(state, size * 2);
            }
            if (expect == null) expect = new int[pos.length];
            else if (expect.length < pos.length) expect = Arrays.copyOf(expect, pos.length);
            pos[size] = p.asLong();
            state[size] = Block.getId(s);
            expect[size] = now;
            if (tag != null) nbt.put(size, tag);
            size++;
        }
    }

    private final ServerLevel level;
    //? if >=26.1
    /*private BlockState lastOld;*/
    private final Areas areas;
    private final int[] bounds;
    private final long timeoutTicks;
    /** Build uses a plan; undo uses the snapshot it restores (in reverse). Both are released when the job ends. */
    private BuildPlan<BlockInput> plan;
    private Snapshot restore;
    private final long total;
    private final long undoOf;
    private final BuildPlan.Placement<BlockInput> cursor = new BuildPlan.Placement<>();
    private Areas.Lease lease;
    private Snapshot snapshot = new Snapshot();
    {
        snapshot.blockOnly = true;
    }
    private boolean undone;
    private long waited, placed, unchanged, kept, done, conflicts;
    /** The journal change this job reverts (0: none). */
    private long revertOf;
    /**
     * A piece of a revert that must wait for the piece reverting later writes ({@link #after}): writes are undone in
     * exactly the reverse order they were made, also across pieces (a block next to a piece edge may rewrite itself
     * when its neighbour changes).
     */
    private ua.zentix.almighty.work.Job after;

    /** Start only after {@code job} has finished, and fail if it did not complete (revert pieces in order). */
    public void after(ua.zentix.almighty.work.Job job) {
        this.after = job;
    }
    private int restoreAt;
    /** The positions an undo or revert writes, for {@link #partnerWritten}; built on first need. */
    private it.unimi.dsi.fastutil.longs.LongOpenHashSet restored;

    /** Whether {@code old} is one half of a door, bed or tall plant whose other half this undo or revert also writes. */
    private boolean partnerWritten(BlockState old, BlockPos pos) {
        BlockPos other;
        if (old.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            other = old.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        } else if (old.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                && old.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            other = pos.relative(net.minecraft.world.level.block.BedBlock.getConnectedDirection(old));
        } else {
            return false;
        }
        if (restored == null) {
            restored = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(restore.size);
            for (int i = 0; i < restore.size; i++) restored.add(restore.pos[i]);
        }
        return restored.contains(other.asLong());
    }

    private BuildJob(String kind, ServerLevel level, Areas areas, int[] bounds, long timeoutTicks,
                     BuildPlan<BlockInput> plan, Snapshot restore, long undoOf) {
        super(kind);
        this.level = level;
        this.areas = areas;
        this.bounds = bounds;
        this.timeoutTicks = timeoutTicks;
        this.plan = plan;
        this.restore = restore;
        this.total = plan != null ? plan.size() : restore.size;
        this.undoOf = undoOf;
        this.restoreAt = restore != null ? restore.size : 0;
    }

    /** Build from a plan; acquire chunks immediately (no lease capacity is a call error). */
    public static BuildJob build(ServerLevel level, Areas areas, BuildPlan<BlockInput> plan, long timeoutTicks) throws RpcException {
        BuildJob job = new BuildJob("build", level, areas, plan.bounds(), timeoutTicks, plan, null, 0);
        job.lease(areas);
        return job;
    }

    /**
     * Revert a journal change in {@code level} (plan 4.9): positions in reverse write order; each goes back to its
     * earlier state only if it still holds what the change put there, otherwise it is counted as a conflict and left.
     */
    public static BuildJob revert(ServerLevel level, Areas areas, long change, long[] pos, BlockState[] before, BlockState[] after,
                                  CompoundTag[] tags, int n, long timeoutTicks) throws RpcException {
        Snapshot s = new Snapshot();
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            p.set(pos[i]);
            s.add(p, before[i], tags[i], Block.getId(after[i]));
            int[] c = {p.getX(), p.getY(), p.getZ()};
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], c[a]);
                b[a + 3] = Math.max(b[a + 3], c[a]);
            }
        }
        BuildJob job = new BuildJob("revert", level, areas, b, timeoutTicks, null, s, 0);
        job.revertOf = change;
        job.lease(areas);
        return job;
    }

    /** Undo build {@code of}: transfer its snapshot to the undo job (a second undo of the same build is an error). */
    public static BuildJob undo(BuildJob of, long timeoutTicks) throws RpcException {
        if (!of.state().finished()) throw RpcException.conflict("Job #" + of.id() + " is still running: cancel it first");
        if (of.snapshot == null) {
            throw RpcException.conflict(of.undone ? "Job #" + of.id() + " is already undone" : "The snapshot of job #" + of.id() + " was dropped (old snapshots are dropped once all of them together exceed 4 million positions)");
        }
        if (of.snapshot.size == 0) throw RpcException.conflict("Job #" + of.id() + " changed nothing");
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int i = 0; i < of.snapshot.size; i++) {
            long p = of.snapshot.pos[i];
            int[] c = {BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p)};
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], c[a]);
                b[a + 3] = Math.max(b[a + 3], c[a]);
            }
        }
        BuildJob job = new BuildJob("undo", of.level, of.areas, b, timeoutTicks, null, of.snapshot, of.id());
        job.lease(of.areas);
        of.snapshot = null;
        of.undone = true;
        return job;
    }

    private void lease(Areas areas) throws RpcException {
        lease = areas.acquire(level, kind(), bounds[0], bounds[2], bounds[3], bounds[5], 0);
    }

    @Override
    protected void submitted() {
        lease.owner(kind() + " #" + id());
    }

    @Override
    protected void step(Budget budget) {
        if (after != null) {
            if (!after.state().finished()) return;
            if (after.state() != State.DONE) {
                fail("An earlier piece of this revert (job #" + after.id() + ") did not complete: " + after.state().name().toLowerCase(java.util.Locale.ROOT));
                return;
            }
            after = null;
        }
        if (state() == State.WAITING) {
            if (!lease.isReady()) {
                String refusal = lease.refusal();
                if (refusal != null) {
                    fail(refusal);
                } else if (++waited > timeoutTicks) {
                    fail("Chunks did not load within " + timeoutTicks + " ticks: " + lease.ready() + " of " + lease.chunks() + " ready");
                }
                return;
            }
            running();
        }
        // Lead's N9: a block an undone (or built) block held up — a lantern under a removed ceiling, a torch on a removed
        // wall — breaks in the knock-on shape update, where vanilla drops it; with this job writing, it drops no item.
        WRITING.set(Boolean.TRUE);
        try {
            slices(budget);
        } finally {
            WRITING.remove();
        }
    }

    private static final ThreadLocal<Boolean> WRITING = new ThreadLocal<>();

    /** Whether a build, undo or revert is writing on this thread: blocks broken by its updates drop nothing ({@code mixin/LevelMixin}). */
    public static boolean writing() {
        return WRITING.get() != null;
    }

    private void slices(Budget budget) {
        long[] updated = new long[UNIT];
        //? if >=26.1
        /*BlockState[] before = new BlockState[UNIT];*/
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        do {
            // A portion is UNIT positions, including unchanged ones (air over air, keep); otherwise an empty box would run in one portion.
            int n = 0;
            boolean more = true;
            for (int k = 0; k < UNIT && (more = advance(pos)); k++) {
                if (put(pos)) {
                    //? if >=26.1
                    /*before[n] = lastOld;*/
                    updated[n++] = pos.asLong();
                }
            }
            for (int i = 0; i < n; i++) {
                pos.set(updated[i]);
                //? if >=26.1 {
                /*level.updateNeighboursOnBlockSet(pos, before[i]);
                *///?} else {
                level.blockUpdated(pos, level.getBlockState(pos).getBlock());
                //?}
            }
            if (!more) {
                finish();
                return;
            }
        } while (budget.left());
    }

    /** Next position in {@code pos}; false means done. */
    private boolean advance(BlockPos.MutableBlockPos pos) {
        if (plan != null) {
            if (!plan.next(cursor)) return false;
            pos.set(cursor.x, cursor.y, cursor.z);
            return true;
        }
        if (restoreAt == 0) return false;
        pos.set(restore.pos[--restoreAt]);
        return true;
    }

    /** Place a position; true means the block changed (its neighbors need an update). */
    private boolean put(BlockPos pos) {
        done++;
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            throw new IllegalStateException("chunk " + (pos.getX() >> 4) + ", " + (pos.getZ() >> 4) + " unloaded during the build");
        }
        BlockState old = level.getBlockState(pos);
        //? if >=26.1
        /*lastOld = old;*/
        if (plan != null && cursor.keep && !old.isAir()) {
            kept++;
            return false;
        }
        // Conflict-aware (journal revert): a position someone changed since is reported and left, before anything
        // about it (its container) is touched.
        // A job's undo too (plan 4.9, critique S3): a player's block built there since stays, and so do its contents.
        if (plan == null && restore.expect != null && !holds(old, restore.expect[restoreAt], restore.blockOnly)) {
            // Already as it was before (the world lost the write in a crash, or someone restored it): nothing to do.
            if (Block.getId(old) == restore.state[restoreAt]) unchanged++;
            else conflicts++;
            return false;
        }
        // Initialize the fresh chunk's pending block entity so its data does not survive under the new block.
        BlockEntity be = level.getBlockEntity(pos);
        // An undo never empties a container someone filled since (a player's items in a chest the build placed): a
        // conflict, left as it is, unless the undo puts the same block back (its contents then are the snapshot's).
        if (plan == null && be instanceof net.minecraft.world.Container box && !box.isEmpty()
                && Block.stateById(restore.state[restoreAt]).getBlock() != old.getBlock()
                && !(restore.contents.containsKey(restoreAt) && restore.contents.get(restoreAt) == contents(box))) {
            conflicts++;
            return false;
        }
        CompoundTag oldTag = be != null ? be.saveWithFullMetadata(level.registryAccess()) : null;
        if (be instanceof Clearable container) {
            // The journal records this write after the clear: give it the full contents, so its undo restores them.
            ua.zentix.almighty.audit.Journal.Change change = ua.zentix.almighty.audit.Journal.current();
            if (change != null && ua.zentix.almighty.audit.Journal.recording()) change.cleared(Mc.id(level.dimension()).toString(), pos, oldTag);
            container.clearContent();
        }
        boolean changed;
        // Lead's F4: replacing one half of a door, bed or tall plant breaks the other half through its shape update,
        // with a drop (vanilla clears UPDATE_SUPPRESS_DROPS for neighbour updates). Without shape updates here the
        // other half stays for its own write (both halves are in a build or a change), and nothing drops.
        // Only in an undo or revert, and only when the other half is written by this job too (review of #101: a build
        // over one half, or a revert of one half, keeps vanilla's shape update, or the other half would float).
        int flags = FLAGS;
        if (plan == null && partnerWritten(old, pos)) flags |= Block.UPDATE_KNOWN_SHAPE;
        if (plan != null) {
            changed = cursor.block.place(level, pos, flags);
        } else {
            BlockState target = Block.stateById(restore.state[restoreAt]);
            changed = level.setBlock(pos, target, flags);
            CompoundTag tag = restore.nbt.get(restoreAt);
            BlockEntity restored = tag != null ? level.getBlockEntity(pos) : null;
            if (restored != null) {
                Mc.loadBlockEntity(restored, tag, level.registryAccess());
                restored.setChanged();
                level.sendBlockUpdated(pos, target, target, Block.UPDATE_CLIENTS);
                changed = true;
            }
        }
        if (changed || oldTag != null) {
            // What this write left in a container (an undo of it brought a chest back full, an owner's chest with
            // items): undoing it later is not "someone filled it since".
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container left && !left.isEmpty()) {
                snapshot.contents.put(snapshot.size, contents(left));
            }
            snapshot.add(pos, old, oldTag, Block.getId(level.getBlockState(pos)));
            placed++;
        } else {
            unchanged++;
        }
        return changed;
    }

    private static int contents(net.minecraft.world.Container box) {
        int h = 1;
        for (int i = 0; i < box.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = box.getItem(i);
            h = 31 * h + (stack.isEmpty() ? 0 : net.minecraft.world.item.ItemStack.hashItemAndComponents(stack) * 31 + stack.getCount());
        }
        return h;
    }

    private static boolean holds(BlockState now, int expected, boolean blockOnly) {
        return blockOnly ? now.getBlock() == Block.stateById(expected).getBlock() : Block.getId(now) == expected;
    }

    @Override
    protected void release() {
        if (lease != null) lease.release();
        // After completion, history retains only this job's snapshot: the plan and the snapshot restored by undo are no longer needed.
        plan = null;
        restore = null;
    }

    @Override
    public long retained() {
        Snapshot s = snapshot, r = restore;
        return (s == null ? 0 : s.size) + (r == null ? 0 : r.size);
    }

    @Override
    public void dropRetained() {
        snapshot = null;
    }

    @Override
    protected void details(JsonObject out) {
        out.addProperty("dimension", Dims.id(level));
        JsonArray from = new JsonArray(), to = new JsonArray();
        for (int a = 0; a < 3; a++) {
            from.add(bounds[a]);
            to.add(bounds[a + 3]);
        }
        out.add("from", from);
        out.add("to", to);
        out.addProperty("total", total);
        out.addProperty("done", done);
        out.addProperty("changed", placed);
        if (unchanged > 0) out.addProperty("unchanged", unchanged);
        if (kept > 0) out.addProperty("kept", kept);
        if (undoOf != 0) out.addProperty("undo_of", undoOf);
        if (revertOf != 0) out.addProperty("revert_of_change", revertOf);
        if (conflicts > 0) out.addProperty("conflicts", conflicts);
        if (state() == State.WAITING && lease != null && !lease.released()) {
            out.addProperty("chunks_ready", lease.ready());
            out.addProperty("chunks", lease.chunks());
        }
        if (state().finished()) {
            out.addProperty("undo", snapshot != null && snapshot.size > 0 ? "available: undo {job: " + id() + "}" : undone ? "undone" : "no snapshot");
        }
    }
}
