package ua.zentix.almighty.compat.sable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import ua.zentix.almighty.world.Observe;
import ua.zentix.almighty.world.Ship;
import ua.zentix.almighty.world.Ships;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Корабли Sable (Create Aeronautics) через sable-companion — открытую библиотеку Sable для модов (MIT; её несёт сам
 * Sable, у нас — только {@code compileOnly}). Внутренние классы Sable не трогаем: их API не обещает стабильности, а
 * часть их на выделенном сервере не грузится (подписи с {@code ClientLevel}). Класс грузится, только когда Sable стоит.
 * Скорость — по физике Sable ({@code getVelocity} точки), угловая — из скоростей трёх точек рядом.
 */
public final class SableShips implements Ships.Source {
    public static final String MOD_ID = "sable";
    /** Больше стольких чанков участка блок-сущности не ищутся (корабль в 500 блоков — около 1000 чанков). */
    static final int MAX_CHUNKS = 1024;
    private static final BoundingBox3dc EVERYWHERE = new BoundingBox3d(-3.0e7, -1.0e4, -3.0e7, 3.0e7, 1.0e4, 3.0e7);

    private static SableCompanion companion() {
        return SableCompanion.INSTANCE;
    }

    @Override
    public List<Ship> all(ServerLevel level) {
        List<Ship> out = new ArrayList<>();
        for (SubLevelAccess sub : companion().getAllIntersecting(level, EVERYWHERE)) out.add(new SableShip(level, sub));
        return out;
    }

    /** Открытый класс: скрипты Groovy зовут его методы. */
    public record SableShip(ServerLevel level, SubLevelAccess sub) implements Ship {
        @Override
        public String name() {
            String name = sub.getName();
            return name == null || name.isBlank() ? null : name;
        }

        @Override
        public UUID uuid() {
            return sub.getUniqueId();
        }

        private Pose3dc pose() {
            return sub.logicalPose();
        }

        @Override
        public Vec3 pos() {
            Vector3d p = new Vector3d(pose().position());
            return new Vec3(p.x, p.y, p.z);
        }

        @Override
        public Vec3 pivot() {
            Vector3d p = new Vector3d(pose().rotationPoint());
            return new Vec3(p.x, p.y, p.z);
        }

        @Override
        public Quaterniond orientation() {
            return new Quaterniond(pose().orientation());
        }

        @Override
        public Vec3 velocity() {
            return velocityAt(pos());
        }

        @Override
        public Vec3 velocityAt(Vec3 world) {
            return companion().getVelocity(level, sub, (Position) world);
        }

        /** v(p + e) − v(p) = ω × e: по единичному шагу вдоль каждой оси. */
        @Override
        public Vec3 angularVelocity() {
            Vec3 p = pos();
            Vec3 v = velocityAt(p);
            Vec3 dx = velocityAt(p.add(1, 0, 0)).subtract(v);
            Vec3 dy = velocityAt(p.add(0, 1, 0)).subtract(v);
            Vec3 dz = velocityAt(p.add(0, 0, 1)).subtract(v);
            return new Vec3(dy.z, dz.x, dx.y);
        }

        @Override
        public AABB box() {
            BoundingBox3dc b = sub.boundingBox();
            return new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        }

        @Override
        public BoundingBox plotBox() {
            AABB w = box();
            // рамку Sable считает в своём тике: у только что собранного корабля её ещё нет
            if (w.getSize() == 0) throw new IllegalStateException("у корабля ещё нет рамки (только собран): подождать тик");
            double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int i = 0; i < 8; i++) {
                Vec3 c = toPlot(new Vec3((i & 1) == 0 ? w.minX : w.maxX, (i & 2) == 0 ? w.minY : w.maxY, (i & 4) == 0 ? w.minZ : w.maxZ));
                min[0] = Math.min(min[0], c.x);
                min[1] = Math.min(min[1], c.y);
                min[2] = Math.min(min[2], c.z);
                max[0] = Math.max(max[0], c.x);
                max[1] = Math.max(max[1], c.y);
                max[2] = Math.max(max[2], c.z);
            }
            return new BoundingBox((int) Math.floor(min[0]), (int) Math.floor(min[1]), (int) Math.floor(min[2]),
                    (int) Math.floor(max[0]), (int) Math.floor(max[1]), (int) Math.floor(max[2]));
        }

        @Override
        public Vec3 toWorld(Vec3 plot) {
            return pose().transformPosition(plot);
        }

        @Override
        public Vec3 toPlot(Vec3 world) {
            return pose().transformPositionInverse(world);
        }

        @Override
        public Vec3 toWorldDir(Vec3 plotDir) {
            Vector3d d = orientation().transform(new Vector3d(plotDir.x, plotDir.y, plotDir.z));
            return new Vec3(d.x, d.y, d.z);
        }

        /**
         * Только готовые чанки участка ({@code getChunkNow}: их держит Sable) и только этого корабля: рамка с запасом
         * могла бы задеть соседний участок.
         */
        @Override
        public List<BlockEntity> blockEntities(String type) {
            BoundingBox box = plotBox();
            int x0 = box.minX() >> 4, z0 = box.minZ() >> 4, x1 = box.maxX() >> 4, z1 = box.maxZ() >> 4;
            if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > MAX_CHUNKS) {
                throw new IllegalStateException("корабль на участке больше " + MAX_CHUNKS + " чанков: блок-сущности не ищу");
            }
            UUID id = uuid();
            List<BlockEntity> out = new ArrayList<>();
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    SubLevelAccess owner = companion().getContaining(level, new ChunkPos(cx, cz));
                    if (owner == null || !id.equals(owner.getUniqueId())) continue;
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        BlockPos p = be.getBlockPos();
                        if (!box.isInside(p)) continue;
                        if (type != null && !type.equals(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())))) continue;
                        out.add(be);
                    }
                }
            }
            return out;
        }

        @Override
        public JsonObject describe() {
            JsonObject o = new JsonObject();
            if (name() != null) o.addProperty("name", name());
            o.addProperty("uuid", uuid().toString());
            o.addProperty("dimension", level.dimension().location().toString());
            o.add("pos", vec(pos(), 2));
            Quaterniond q = orientation();
            JsonArray quat = new JsonArray();
            for (double c : new double[] {q.x, q.y, q.z, q.w}) quat.add(Observe.round(c, 4));
            o.add("orientation", quat);
            Vector3d e = q.getEulerAnglesYXZ(new Vector3d());
            JsonObject euler = new JsonObject();
            euler.addProperty("yaw", Observe.round(Math.toDegrees(e.y), 1));
            euler.addProperty("pitch", Observe.round(Math.toDegrees(e.x), 1));
            euler.addProperty("roll", Observe.round(Math.toDegrees(e.z), 1));
            o.add("rotation_deg", euler);
            o.add("up", vec(toWorldDir(new Vec3(0, 1, 0)), 3));
            Vec3 v = velocity();
            o.add("velocity", vec(v, 2));
            o.addProperty("speed", Observe.round(v.length(), 2));
            o.add("angular_velocity", vec(angularVelocity(), 3));
            AABB w = box();
            if (w.getSize() > 0) {
                o.add("box", box(w.minX, w.minY, w.minZ, w.maxX, w.maxY, w.maxZ));
                BoundingBox plot = plotBox();
                o.add("plot", box(plot.minX(), plot.minY(), plot.minZ(), plot.maxX(), plot.maxY(), plot.maxZ()));
            }
            o.add("pivot", vec(pivot(), 2));
            return o;
        }

        private static JsonArray vec(Vec3 v, int digits) {
            JsonArray a = new JsonArray();
            a.add(Observe.round(v.x, digits));
            a.add(Observe.round(v.y, digits));
            a.add(Observe.round(v.z, digits));
            return a;
        }

        private static JsonObject box(double x0, double y0, double z0, double x1, double y1, double z1) {
            JsonObject b = new JsonObject();
            b.add("from", vec(new Vec3(x0, y0, z0), 1));
            b.add("to", vec(new Vec3(x1, y1, z1), 1));
            return b;
        }
    }
}
