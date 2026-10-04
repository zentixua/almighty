package ua.zentix.airstrikegm.view;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.zentix.airstrikegm.bot.Eye;
import ua.zentix.airstrikegm.work.Budget;
import ua.zentix.airstrikegm.world.Rays;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Картинка от глаза: перспектива, луч на каждый пиксель по блокам (цвет блока — как на карте, грань — светлее сверху,
 * темнее снизу и по бокам, как в игре; свет — небо с учётом времени суток и источники; вдали — дымка), сущности —
 * цветными рамками по роду, вода — полупрозрачная, стекло — налётом. Незагруженный чанк — шахматка. Перекрестье — в
 * центре. Блоки читаются в потоке сервера порциями под бюджетом тика ({@link View}); вместе с картинкой — то, что
 * {@link Eye} видит без неё.
 */
public final class EyeView extends View {
    public static final int MAX_WIDTH = 480, MAX_HEIGHT = 270, MAX_DISTANCE = 256, MAX_BOXES = 128;
    private static final int GLASS = 0xF0FAFF, WATER = 0x3F76E4, LAVA = 0xFF6A00;

    private record Box(AABB box, int colour, int x0, int y0, int x1, int y1) {}

    private final ServerLevel level;
    private final Eye.Camera cam;
    private final int width, height;
    private final double distance;
    private final JsonObject seen;
    private final List<Box> boxes = new ArrayList<>();
    private final Png png;
    private final Rays.Chunks chunks;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final int sky;
    private final float ambient;
    private final int darken;
    private int next;

    /** {@code self} — чей глаз (его и то, на чём он едет, на картинке нет); null — свободная камера. */
    public EyeView(ServerLevel level, Eye.Camera cam, int width, int height, double distance, Entity self, JsonObject seen) {
        super("eye");
        this.level = level;
        this.cam = cam;
        this.width = width;
        this.height = height;
        this.distance = distance;
        this.seen = seen;
        this.png = new Png(width, height);
        this.chunks = new Rays.Chunks(level);
        this.darken = level.getSkyDarken();
        this.ambient = level.dimensionType().ambientLight();
        if (!level.dimensionType().hasSkyLight()) sky = level.dimensionType().ultraWarm() ? 0x3A0E0A : 0x140F1E;
        else sky = lerp(0x0B1020, 0x7BA4FF, 1.0 - darken / 11.0);
        collect(self);
    }

    /** Сущности в дальности картинки — рамками; у каждой — прямоугольник кадра, чтобы пиксель проверял только свои. */
    private void collect(Entity self) {
        AABB around = new AABB(cam.eye(), cam.eye()).inflate(distance);
        Entity root = self == null ? null : self.getRootVehicle();
        List<Entity> found = new ArrayList<>(level.getEntities(self, around, e -> !e.isSpectator() && !e.isInvisible()
                && (root == null || e.getRootVehicle() != root)));
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(cam.eye())));
        double tanH = cam.tanH(), tanV = cam.tanV();
        for (Entity e : found) {
            if (boxes.size() >= MAX_BOXES) break;
            AABB b = e.getBoundingBox();
            int x0 = width, y0 = height, x1 = -1, y1 = -1;
            boolean behind = false;
            for (int i = 0; i < 8; i++) {
                Vec3 c = new Vec3((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ).subtract(cam.eye());
                double z = c.dot(cam.forward());
                if (z <= 0.05) {
                    behind = true;
                    continue;
                }
                double sx = c.dot(cam.right()) / (z * tanH), sy = c.dot(cam.up()) / (z * tanV);
                int px = (int) Math.floor((sx + 1) / 2 * width), py = (int) Math.floor((1 - sy) / 2 * height);
                x0 = Math.min(x0, px);
                x1 = Math.max(x1, px);
                y0 = Math.min(y0, py);
                y1 = Math.max(y1, py);
            }
            if (behind) {
                // угол позади глаза: проекция не годится — весь кадр
                x0 = 0;
                y0 = 0;
                x1 = width - 1;
                y1 = height - 1;
            }
            if (x1 < 0 || y1 < 0 || x0 >= width || y0 >= height) continue;
            boxes.add(new Box(b, Eye.colour(e), Math.max(0, x0 - 1), Math.max(0, y0 - 1), Math.min(width - 1, x1 + 1), Math.min(height - 1, y1 + 1)));
        }
    }

    @Override
    protected void step(Budget budget) {
        running();
        do {
            int i = next % width, j = next / width;
            png.set(i, j, pixel(i, j));
            if (++next == width * height) {
                complete();
                return;
            }
        } while (budget.left());
    }

    private int pixel(int i, int j) {
        double sx = (i + 0.5) / width * 2 - 1, sy = 1 - (j + 0.5) / height * 2;
        Vec3 dir = cam.forward().add(cam.right().scale(sx * cam.tanH())).add(cam.up().scale(sy * cam.tanV())).normalize();
        // сущность ближе всего, что на этом пикселе
        double entityT = Double.MAX_VALUE;
        int entityColour = 0, entityAxis = 1;
        for (Box b : boxes) {
            if (i < b.x0 || i > b.x1 || j < b.y0 || j > b.y1) continue;
            double[] hit = slab(b.box, cam.eye(), dir);
            if (hit != null && hit[0] < entityT) {
                entityT = hit[0];
                entityColour = b.colour;
                entityAxis = (int) hit[1];
            }
        }
        return march(dir, entityT, entityColour, entityAxis);
    }

    /**
     * Луч по клеткам (DDA): первая клетка с формой — попадание (полный куб — сразу, неполная — по форме); стекло и
     * прочее без цвета карты — налёт и дальше; вода — дальше, окрашивая; лава — поверхность.
     */
    private int march(Vec3 dir, double entityT, int entityColour, int entityAxis) {
        Vec3 eye = cam.eye();
        int x = Mth.floor(eye.x), y = Mth.floor(eye.y), z = Mth.floor(eye.z);
        int stepX = dir.x > 0 ? 1 : -1, stepY = dir.y > 0 ? 1 : -1, stepZ = dir.z > 0 ? 1 : -1;
        double dx = Math.abs(1 / dir.x), dy = Math.abs(1 / dir.y), dz = Math.abs(1 / dir.z);
        double tx = (dir.x > 0 ? x + 1 - eye.x : eye.x - x) * dx;
        double ty = (dir.y > 0 ? y + 1 - eye.y : eye.y - y) * dy;
        double tz = (dir.z > 0 ? z + 1 - eye.z : eye.z - z) * dz;
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight() - 1;
        double t = 0, waterFrom = -1, water = 0;
        int axis = -1;
        boolean glass = false;
        double limit = Math.min(distance, entityT);
        while (t <= limit) {
            if (y >= minY && y <= maxY) {
                LevelChunk chunk = chunks.at(x, z);
                if (chunk == null) return tone(((x >> 2) + (z >> 2) & 1) == 0 ? 0x303030 : 0x484848, t, glass, water);
                pos.set(x, y, z);
                BlockState state = chunk.getBlockState(pos);
                if (!state.isAir()) {
                    FluidState fluid = state.getFluidState();
                    boolean wet = !fluid.isEmpty() && fluid.is(FluidTags.WATER);
                    if (wet && waterFrom < 0) waterFrom = t;
                    if (!wet && waterFrom >= 0) {
                        water += t - waterFrom;
                        waterFrom = -1;
                    }
                    if (!fluid.isEmpty() && fluid.is(FluidTags.LAVA)) return surface(LAVA, axis, stepY, 15, t, glass, water);
                    VoxelShape shape = state.getShape(chunk, pos);
                    double hitT = -1;
                    int hitAxis = axis;
                    if (shape == Shapes.block()) {
                        hitT = t;
                    } else if (!shape.isEmpty()) {
                        Vec3 from = eye.add(dir.scale(t)), to = eye.add(dir.scale(Math.min(t + 1.8, limit + 1.8)));
                        BlockHitResult r = shape.clip(from, to, pos);
                        if (r != null) {
                            hitT = r.getLocation().distanceTo(eye);
                            hitAxis = r.getDirection().getAxis().ordinal();
                        }
                    }
                    if (hitT >= 0 && hitT <= limit) {
                        MapColor colour = state.getMapColor(chunk, pos);
                        if (colour == MapColor.NONE) {
                            glass = true;
                        } else {
                            if (waterFrom >= 0) water += hitT - waterFrom;
                            return surface(colour.col, hitAxis, stepY, light(x, y, z, hitAxis, stepX, stepY, stepZ), hitT, glass, water);
                        }
                    }
                }
            } else if ((y < minY && stepY < 0) || (y > maxY && stepY > 0)) {
                break;
            }
            if (tx < ty && tx < tz) {
                x += stepX;
                t = tx;
                tx += dx;
                axis = 0;
            } else if (ty < tz) {
                y += stepY;
                t = ty;
                ty += dy;
                axis = 1;
            } else {
                z += stepZ;
                t = tz;
                tz += dz;
                axis = 2;
            }
        }
        if (waterFrom >= 0) water += Math.min(t, limit) - waterFrom;
        if (entityT <= distance) {
            BlockPos at = BlockPos.containing(eye.add(dir.scale(entityT)));
            int l = Math.max(level.getBrightness(LightLayer.SKY, at) - darken, level.getBrightness(LightLayer.BLOCK, at));
            return surface(entityColour, entityAxis, stepY, Math.max(l, 6), entityT, glass, water);
        }
        return tone(sky, distance, glass, water);
    }

    /** Свет у грани: в клетке, откуда луч вошёл в блок. */
    private int light(int x, int y, int z, int axis, int stepX, int stepY, int stepZ) {
        BlockPos p = switch (axis) {
            case 0 -> new BlockPos(x - stepX, y, z);
            case 1 -> new BlockPos(x, y - stepY, z);
            case 2 -> new BlockPos(x, y, z - stepZ);
            default -> new BlockPos(x, y, z);
        };
        return Math.max(level.getBrightness(LightLayer.SKY, p) - darken, level.getBrightness(LightLayer.BLOCK, p));
    }

    /** Грань: тень по стороне (верх 1, низ 0,5, север-юг 0,8, запад-восток 0,6), свет, дымка, вода и стекло. */
    private int surface(int colour, int axis, int stepY, int light, double t, boolean glass, double water) {
        double face = switch (axis) {
            case 0 -> 0.6;
            case 1 -> stepY < 0 ? 1.0 : 0.5;
            case 2 -> 0.8;
            default -> 1.0;
        };
        return tone(Png.shade(colour, face * brightness(light)), t, glass, water);
    }

    /** Яркость по уровню света — кривая игры (с окружающим светом измерения) и яркость «по умолчанию». */
    private double brightness(int light) {
        float f = Mth.clamp(light, 0, 15) / 15.0F;
        double curve = f / (4.0F - 3.0F * f);
        curve = Mth.lerp(ambient, curve, 1.0);
        double gamma = 1 - Math.pow(1 - curve, 4);
        return Math.max(0.06, Mth.lerp(0.5, curve, gamma));
    }

    private int tone(int colour, double t, boolean glass, double water) {
        if (water > 0) colour = lerp(colour, WATER, Math.min(0.85, 0.25 + water / 12));
        if (glass) colour = lerp(colour, GLASS, 0.33);
        double fog = Mth.clamp((t - 0.6 * distance) / (0.4 * distance), 0, 1);
        return lerp(colour, sky, fog);
    }

    private static int lerp(int a, int b, double k) {
        int r = (int) Mth.lerp(k, a >> 16 & 255, b >> 16 & 255);
        int g = (int) Mth.lerp(k, a >> 8 & 255, b >> 8 & 255);
        int bl = (int) Mth.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    /** Луч и рамка: {расстояние входа, ось грани входа}; мимо или позади — null. */
    private static double[] slab(AABB b, Vec3 o, Vec3 d) {
        double tmin = Double.NEGATIVE_INFINITY, tmax = Double.POSITIVE_INFINITY;
        int axis = 1;
        double[] lo = {b.minX, b.minY, b.minZ}, hi = {b.maxX, b.maxY, b.maxZ}, org = {o.x, o.y, o.z}, dir = {d.x, d.y, d.z};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(dir[a]) < 1e-12) {
                if (org[a] < lo[a] || org[a] > hi[a]) return null;
                continue;
            }
            double t1 = (lo[a] - org[a]) / dir[a], t2 = (hi[a] - org[a]) / dir[a];
            double near = Math.min(t1, t2), far = Math.max(t1, t2);
            if (near > tmin) {
                tmin = near;
                axis = a;
            }
            tmax = Math.min(tmax, far);
            if (tmin > tmax) return null;
        }
        if (tmax < 0) return null;
        return new double[] {Math.max(0, tmin), axis};
    }

    private void complete() {
        // перекрестье: белое, с тёмной обводкой — видно на любом фоне
        int cx = width / 2, cy = height / 2;
        for (int k = -4; k <= 4; k++) {
            if (k == 0) continue;
            mark(cx + k, cy, 0xFFFFFF);
            mark(cx, cy + k, 0xFFFFFF);
        }
        legend.add("seen", seen);
        legend.addProperty("pixels", width + "×" + height);
        legend.addProperty("fov_vertical", cam.fov());
        legend.addProperty("distance", distance);
        legend.addProperty("colours", "блоки — цвет карты, верх светлее, низ и бока темнее, свет — время суток и источники; вдали — дымка цвета неба; "
                + "сущности рамками: красный — игрок, фиолетовый — враждебный моб, жёлтый — другое живое, белый — предмет, оранжевый — снаряд, голубой — прочее (транспорт); "
                + "синева — вода, белёсый налёт — стекло, шахматка — чанк не загружен; перекрестье — центр взгляда");
        image(png, 0, 0, 0);
        finish();
    }

    private void mark(int x, int y, int colour) {
        if (x >= 0 && x < width && y >= 0 && y < height) png.set(x, y, colour);
    }

    @Override
    protected void details(JsonObject out) {
        out.addProperty("pixels_done", next);
        out.addProperty("pixels", width * height);
    }
}
