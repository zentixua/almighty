package ua.zentix.airstrikegm.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrikegm.world.Dims;
import ua.zentix.airstrikegm.world.Observe;
import ua.zentix.airstrikegm.world.Rays;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Зрение без картинки: откуда и куда смотрит глаз, что под прицелом (в досягаемости рук и дальше по лучу), какие
 * сущности рядом — с расстоянием, направлением от взгляда, в кадре ли и не закрыты ли блоками, — и что бот слышал.
 * Только загруженное: лучи по готовым чанкам ({@link Rays}), сущности — из загруженных секций. Поток сервера.
 */
public final class Eye {
    public static final int MAX_ENTITIES = 40;

    private Eye() {}

    /**
     * Камера: точка глаза, поворот (yaw по часовой сверху, 0 — на юг; pitch вниз положительный), вертикальный угол
     * обзора и соотношение сторон кадра; базис — вперёд, вправо, вверх.
     */
    public record Camera(Vec3 eye, float yaw, float pitch, double fov, double aspect, Vec3 forward, Vec3 right, Vec3 up) {
        public static Camera of(Vec3 eye, float yaw, float pitch, double fov, double aspect) {
            Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
            Vec3 up = Vec3.directionFromRotation(pitch - 90.0F, yaw);
            Vec3 right = forward.cross(up);
            return new Camera(eye, yaw, pitch, fov, aspect, forward, right, up);
        }

        public double tanV() {
            return Math.tan(Math.toRadians(fov) / 2);
        }

        public double tanH() {
            return tanV() * aspect;
        }

        /** Сколько градусов вправо (+) и вниз (+) от взгляда до точки. */
        double[] offset(Vec3 at) {
            Vec3 d = at.subtract(eye);
            double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
            float yawTo = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
            float pitchTo = (float) -(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG);
            return new double[] {Mth.wrapDegrees(yawTo - yaw), pitchTo - pitch};
        }

        /** Точка в кадре (перед глазом и внутри угла обзора). */
        boolean inView(Vec3 at) {
            Vec3 d = at.subtract(eye);
            double z = d.dot(forward);
            if (z <= 0.05) return false;
            return Math.abs(d.dot(right) / z) <= tanH() && Math.abs(d.dot(up) / z) <= tanV();
        }
    }

    /**
     * Что видит глаз: {@code viewer} — чей (null — свободная камера), {@code bot} — его слух (null — нет).
     * {@code radius} — сущности в шаре, {@code distance} — дальний луч прицела.
     */
    public static JsonObject observe(ServerLevel level, Camera cam, Entity viewer, Bot bot, int radius, double distance, int soundTicks) {
        JsonObject out = new JsonObject();
        out.addProperty("dimension", Dims.id(level));
        out.add("eye", Observe.vec(cam.eye()));
        out.addProperty("yaw", Observe.round(Mth.wrapDegrees(cam.yaw()), 1));
        out.addProperty("pitch", Observe.round(cam.pitch(), 1));
        out.addProperty("facing", facing(cam.yaw(), cam.pitch()));
        out.addProperty("fov", cam.fov());
        BlockPos at = BlockPos.containing(cam.eye());
        JsonObject light = new JsonObject();
        light.addProperty("sky", level.getBrightness(LightLayer.SKY, at));
        light.addProperty("block", level.getBrightness(LightLayer.BLOCK, at));
        light.addProperty("sky_darkening", level.getSkyDarken());
        out.add("light", light);
        if (viewer instanceof ServerPlayer player) {
            JsonObject reach = new JsonObject();
            Controls.describe(player, Controls.pick(player), reach);
            out.add("aim", reach.get("target"));
        }
        out.add("aim_far", far(level, cam, viewer, distance));
        out.add("entities", entities(level, cam, viewer, radius));
        out.addProperty("offsets", "yaw_offset — градусов вправо (+) или влево (−) от взгляда, pitch_offset — вниз (+) или вверх (−)");
        if (bot != null) out.add("sounds", sounds(level, cam, bot, soundTicks));
        return out;
    }

    private static String facing(float yaw, float pitch) {
        if (pitch > 60) return "вниз";
        if (pitch < -60) return "вверх";
        String[] sides = {"юг", "юго-запад", "запад", "северо-запад", "север", "северо-восток", "восток", "юго-восток"};
        return sides[Math.floorMod(Math.round(Mth.wrapDegrees(yaw) / 45.0F), 8)];
    }

    /** Первое на луче взгляда до {@code distance}: блок (жидкость тоже) или сущность. */
    private static JsonObject far(ServerLevel level, Camera cam, Entity viewer, double distance) {
        Vec3 end = cam.eye().add(cam.forward().scale(distance));
        Rays.Hit ray = Rays.clip(level, cam.eye(), end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, viewer);
        double blockDist = ray.hit() ? ray.block().getLocation().distanceTo(cam.eye()) : distance;
        Vec3 blockEnd = cam.eye().add(cam.forward().scale(blockDist));
        AABB box = new AABB(cam.eye(), blockEnd).inflate(1.0);
        EntityHitResult entity = viewer != null
                ? ProjectileUtil.getEntityHitResult(viewer, cam.eye(), blockEnd, box, Controls.PICKABLE, blockDist * blockDist)
                : ProjectileUtil.getEntityHitResult(level, null, cam.eye(), blockEnd, box, Controls.PICKABLE, 0.0F);
        JsonObject o = new JsonObject();
        if (entity != null) {
            o.add("entity", entity(entity.getEntity()));
            o.addProperty("distance", Observe.round(entity.getLocation().distanceTo(cam.eye()), 1));
        } else if (ray.hit()) {
            BlockHitResult b = ray.block();
            o.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(b.getBlockPos()).getBlock()).toString());
            o.add("pos", Observe.pos(b.getBlockPos()));
            o.addProperty("face", b.getDirection().getSerializedName());
            o.addProperty("distance", Observe.round(blockDist, 1));
        } else if (ray.unloaded()) {
            o.addProperty("none", "дальше " + Observe.round(ray.block().getLocation().distanceTo(cam.eye()), 0) + " блоков чанк не загружен");
        } else {
            o.addProperty("none", "в пределах " + Math.round(distance) + " блоков ничего");
        }
        return o;
    }

    private static JsonArray entities(ServerLevel level, Camera cam, Entity viewer, int radius) {
        AABB box = new AABB(cam.eye(), cam.eye()).inflate(radius);
        double r2 = (double) radius * radius;
        List<Entity> found = new ArrayList<>(level.getEntities(viewer, box, e -> e.isAlive() && !e.isSpectator()
                && e.getBoundingBox().getCenter().distanceToSqr(cam.eye()) <= r2));
        found.sort(Comparator.comparingDouble(e -> e.getBoundingBox().getCenter().distanceToSqr(cam.eye())));
        JsonArray out = new JsonArray();
        for (Entity e : found.subList(0, Math.min(MAX_ENTITIES, found.size()))) {
            AABB b = e.getBoundingBox();
            Vec3 centre = b.getCenter();
            JsonObject o = entity(e);
            o.addProperty("distance", Observe.round(centre.distanceTo(cam.eye()), 1));
            double[] off = cam.offset(centre);
            o.addProperty("yaw_offset", Math.round(off[0]));
            o.addProperty("pitch_offset", Math.round(off[1]));
            boolean riding = viewer != null && (e == viewer.getVehicle() || e.getVehicle() == viewer);
            if (riding) o.addProperty("riding_with", true);
            o.addProperty("in_view", cam.inView(centre) || cam.inView(new Vec3(centre.x, b.maxY, centre.z)));
            o.addProperty("visible", riding || visible(level, cam.eye(), b));
            out.add(o);
        }
        return out;
    }

    /** Виден ли кусок рамки сущности: середина, верх, низ — хоть один без заслона. */
    private static boolean visible(ServerLevel level, Vec3 eye, AABB b) {
        Vec3 c = b.getCenter();
        return Rays.clear(level, eye, c) || Rays.clear(level, eye, new Vec3(c.x, b.maxY - 0.1, c.z)) || Rays.clear(level, eye, new Vec3(c.x, b.minY + 0.1, c.z));
    }

    static JsonObject entity(Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", EntityType.getKey(e.getType()).toString());
        if (e instanceof ServerPlayer || e.hasCustomName()) o.addProperty("name", e.getName().getString());
        o.addProperty("uuid", e.getUUID().toString());
        o.add("pos", Observe.vec(e.position()));
        if (e instanceof LivingEntity living) {
            o.addProperty("health", Observe.round(living.getHealth(), 1));
            if (!living.getMainHandItem().isEmpty()) o.addProperty("holding", BuiltInRegistries.ITEM.getKey(living.getMainHandItem().getItem()).toString());
        }
        if (e instanceof Enemy) o.addProperty("hostile", true);
        if (e.getVehicle() != null) o.addProperty("riding", EntityType.getKey(e.getVehicle().getType()).toString());
        return o;
    }

    private static JsonArray sounds(ServerLevel level, Camera cam, Bot bot, int ticks) {
        JsonArray out = new JsonArray();
        long now = level.getServer().getTickCount();
        for (Bot.Heard h : bot.heard(ticks)) {
            JsonObject o = new JsonObject();
            o.addProperty("sound", h.sound());
            o.addProperty("source", h.source());
            if (h.entity() != null) o.addProperty("entity", h.entity());
            o.addProperty("distance", Observe.round(h.pos().distanceTo(cam.eye()), 1));
            double[] off = cam.offset(h.pos());
            o.addProperty("yaw_offset", Math.round(off[0]));
            o.addProperty("pitch_offset", Math.round(off[1]));
            o.addProperty("ticks_ago", now - h.tick());
            out.add(o);
        }
        return out;
    }

    /** Цвет сущности на картинке — по роду. */
    public static int colour(Entity e) {
        if (e instanceof net.minecraft.world.entity.player.Player) return 0xE8473C;
        if (e instanceof Enemy) return 0xB02CC9;
        if (e instanceof LivingEntity) return 0xF2C230;
        if (e instanceof net.minecraft.world.entity.item.ItemEntity) return 0xFFFFFF;
        if (e instanceof net.minecraft.world.entity.projectile.Projectile) return 0xFF8C1A;
        return 0x2EC4E6;
    }
}
