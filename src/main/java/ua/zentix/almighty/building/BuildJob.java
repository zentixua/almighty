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
import ua.zentix.almighty.work.Budget;
import ua.zentix.almighty.work.Job;
import ua.zentix.almighty.world.Areas;
import ua.zentix.almighty.world.Dims;

import java.util.Arrays;

/**
 * Постройка или откат частями между тиками. Сначала ждёт, пока чанки рамки с кольцом соседей станут полными (аренда
 * {@link Areas}: загрузка без тика, в фоне), затем ставит места порциями по {@value #UNIT}, пока есть время тика.
 *
 * <p>Место ставится как у {@code /fill} ({@code FillCommand}): сперва {@code getBlockEntity} — он поднимает отложенную
 * блок-сущность свежего чанка (иначе замена оставила бы её данные при новом блоке), снимок старого блока с данными
 * блок-сущности, {@code Clearable.tryClear} (содержимое сундука не высыпается), {@code BlockInput.place} с
 * {@code UPDATE_CLIENTS}; после порции — {@code blockUpdated} её мест. Снимок всех изменённых мест — для отката
 * ({@code undo}), он сам — такая же работа со своим снимком. Откат не возвращает то, что изменилось вне рамки (факел у
 * стены, отвалившийся при её сносе, — предметом), и сущности.
 */
public final class BuildJob extends Job {
    static final int UNIT = 32;

    /** Изменённые места: старое состояние и данные блок-сущности — по порядку изменения. */
    static final class Snapshot {
        private long[] pos = new long[256];
        private int[] state = new int[256];
        private final Int2ObjectOpenHashMap<CompoundTag> nbt = new Int2ObjectOpenHashMap<>();
        private int size;

        void add(BlockPos p, BlockState s, CompoundTag tag) {
            if (size == pos.length) {
                pos = Arrays.copyOf(pos, size * 2);
                state = Arrays.copyOf(state, size * 2);
            }
            pos[size] = p.asLong();
            state[size] = Block.getId(s);
            if (tag != null) nbt.put(size, tag);
            size++;
        }
    }

    private final ServerLevel level;
    private final Areas areas;
    private final int[] bounds;
    private final long timeoutTicks;
    /** Постройка — план; откат — снимок, который он возвращает (с конца). Оба отпускаются с концом работы. */
    private BuildPlan<BlockInput> plan;
    private Snapshot restore;
    private final long total;
    private final long undoOf;
    private final BuildPlan.Placement<BlockInput> cursor = new BuildPlan.Placement<>();
    private Areas.Lease lease;
    private Snapshot snapshot = new Snapshot();
    private boolean undone;
    private long waited, placed, unchanged, kept, done;
    private int restoreAt;

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

    /** Постройка по плану; чанки берутся сразу (нет места под аренду — ошибка вызова). */
    public static BuildJob build(ServerLevel level, Areas areas, BuildPlan<BlockInput> plan, long timeoutTicks) throws RpcException {
        BuildJob job = new BuildJob("build", level, areas, plan.bounds(), timeoutTicks, plan, null, 0);
        job.lease(areas);
        return job;
    }

    /** Откат постройки {@code of}: её снимок переходит в откат (второй откат той же постройки — ошибка). */
    public static BuildJob undo(BuildJob of, long timeoutTicks) throws RpcException {
        if (!of.state().finished()) throw RpcException.conflict("Работа №" + of.id() + " ещё идёт: сначала cancel");
        if (of.snapshot == null) {
            throw RpcException.conflict(of.undone ? "Работа №" + of.id() + " уже откачена" : "Снимок работы №" + of.id() + " выброшен (старые снимки уходят, когда их больше 4 млн мест)");
        }
        if (of.snapshot.size == 0) throw RpcException.conflict("Работа №" + of.id() + " ничего не изменила");
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
        lease.owner(kind() + " №" + id());
    }

    @Override
    protected void step(Budget budget) {
        if (state() == State.WAITING) {
            if (!lease.isReady()) {
                String refusal = lease.refusal();
                if (refusal != null) {
                    fail(refusal);
                } else if (++waited > timeoutTicks) {
                    fail("Чанки не загрузились за " + timeoutTicks + " тиков: готово " + lease.ready() + " из " + lease.chunks());
                }
                return;
            }
            running();
        }
        long[] updated = new long[UNIT];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        do {
            // порция — UNIT мест, считая и нетронутые (воздух на воздух, keep): иначе пустая коробка шла бы одной порцией
            int n = 0;
            boolean more = true;
            for (int k = 0; k < UNIT && (more = advance(pos)); k++) {
                if (put(pos)) updated[n++] = pos.asLong();
            }
            for (int i = 0; i < n; i++) {
                pos.set(updated[i]);
                level.blockUpdated(pos, level.getBlockState(pos).getBlock());
            }
            if (!more) {
                finish();
                return;
            }
        } while (budget.left());
    }

    /** Следующее место в {@code pos}; false — всё. */
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

    /** Поставить место; true — блок изменился (его соседям — обновление). */
    private boolean put(BlockPos pos) {
        done++;
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            throw new IllegalStateException("чанк " + (pos.getX() >> 4) + ", " + (pos.getZ() >> 4) + " выгрузился под постройкой");
        }
        BlockState old = level.getBlockState(pos);
        if (plan != null && cursor.keep && !old.isAir()) {
            kept++;
            return false;
        }
        // поднимает отложенную блок-сущность свежего чанка: её данные не останутся при новом блоке
        BlockEntity be = level.getBlockEntity(pos);
        CompoundTag oldTag = be != null ? be.saveWithFullMetadata(level.registryAccess()) : null;
        Clearable.tryClear(be);
        boolean changed;
        if (plan != null) {
            changed = cursor.block.place(level, pos, Block.UPDATE_CLIENTS);
        } else {
            BlockState target = Block.stateById(restore.state[restoreAt]);
            changed = level.setBlock(pos, target, Block.UPDATE_CLIENTS);
            CompoundTag tag = restore.nbt.get(restoreAt);
            BlockEntity restored = tag != null ? level.getBlockEntity(pos) : null;
            if (restored != null) {
                restored.loadWithComponents(tag, level.registryAccess());
                restored.setChanged();
                level.sendBlockUpdated(pos, target, target, Block.UPDATE_CLIENTS);
                changed = true;
            }
        }
        if (changed || oldTag != null) {
            snapshot.add(pos, old, oldTag);
            placed++;
        } else {
            unchanged++;
        }
        return changed;
    }

    @Override
    protected void release() {
        if (lease != null) lease.release();
        // после конца в истории держится только свой снимок: план и возвращённый откатом снимок не нужны
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
        if (state() == State.WAITING && lease != null && !lease.released()) {
            out.addProperty("chunks_ready", lease.ready());
            out.addProperty("chunks", lease.chunks());
        }
        if (state().finished()) {
            out.addProperty("undo", snapshot != null && snapshot.size > 0 ? "можно: undo {job: " + id() + "}" : undone ? "откачена" : "нет снимка");
        }
    }
}
