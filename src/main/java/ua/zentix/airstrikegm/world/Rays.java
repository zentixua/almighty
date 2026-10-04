package ua.zentix.airstrikegm.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Лучи по блокам, которые не грузят чанки: то же, что {@code BlockGetter.clip} (форма блока, форма взаимодействия,
 * жидкость — ближнее), но чанк берётся только готовым ({@code getChunkNow}). Незагруженный чанк на пути — стена:
 * луч кончается на входе в него, {@link Hit#unloaded()}. Поток сервера.
 */
public final class Rays {
    private Rays() {}

    /** Итог луча: попадание или промах в конце; {@code unloaded} — упёрся в незагруженный чанк. */
    public record Hit(BlockHitResult block, boolean unloaded) {
        public boolean hit() {
            return !unloaded && block.getType() != BlockHitResult.Type.MISS;
        }
    }

    public static Hit clip(ServerLevel level, Vec3 from, Vec3 to, ClipContext.Block block, ClipContext.Fluid fluid, Entity entity) {
        CollisionContext collision = entity == null ? CollisionContext.empty() : CollisionContext.of(entity);
        ClipContext ctx = new ClipContext(from, to, block, fluid, collision);
        Chunks chunks = new Chunks(level);
        boolean[] unloaded = {false};
        BlockHitResult result = BlockGetter.traverseBlocks(from, to, ctx, (c, pos) -> {
            LevelChunk chunk = chunks.at(pos.getX(), pos.getZ());
            if (chunk == null) {
                unloaded[0] = true;
                Vec3 entry = new AABB(pos).clip(from, to).orElse(Vec3.atCenterOf(pos));
                return BlockHitResult.miss(entry, Direction.getNearest(from.x - to.x, from.y - to.y, from.z - to.z), pos);
            }
            BlockState state = chunk.getBlockState(pos);
            FluidState fluidState = state.getFluidState();
            VoxelShape shape = c.getBlockShape(state, chunk, pos);
            BlockHitResult solid = shape.clip(from, to, pos);
            if (solid != null) {
                BlockHitResult face = state.getInteractionShape(chunk, pos).clip(from, to, pos);
                if (face != null && face.getLocation().distanceToSqr(from) < solid.getLocation().distanceToSqr(from)) {
                    solid = solid.withDirection(face.getDirection());
                }
            }
            BlockHitResult wet = c.getFluidShape(fluidState, chunk, pos).clip(from, to, pos);
            double ds = solid == null ? Double.MAX_VALUE : from.distanceToSqr(solid.getLocation());
            double dw = wet == null ? Double.MAX_VALUE : from.distanceToSqr(wet.getLocation());
            return ds <= dw ? solid : wet;
        }, c -> {
            Vec3 back = from.subtract(to);
            return BlockHitResult.miss(to, Direction.getNearest(back.x, back.y, back.z), BlockPos.containing(to));
        });
        return new Hit(result, unloaded[0]);
    }

    /** Между точками нет того, что заслоняет вид (видимая форма блока; стекло и прочее без неё — насквозь). */
    public static boolean clear(ServerLevel level, Vec3 from, Vec3 to) {
        Hit hit = clip(level, from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, null);
        return !hit.unloaded() && hit.block().getType() == BlockHitResult.Type.MISS;
    }

    /** Чанки подряд идущих точек: тот же чанк — без поиска; только готовые. */
    public static final class Chunks {
        private final ServerLevel level;
        private int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        private LevelChunk chunk;

        public Chunks(ServerLevel level) {
            this.level = level;
        }

        public LevelChunk at(int x, int z) {
            if (x >> 4 != cx || z >> 4 != cz) {
                cx = x >> 4;
                cz = z >> 4;
                chunk = level.getChunkSource().getChunkNow(cx, cz);
            }
            return chunk;
        }
    }
}
