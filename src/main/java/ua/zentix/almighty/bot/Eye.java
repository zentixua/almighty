package ua.zentix.almighty.bot;

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
import ua.zentix.almighty.world.Dims;
import ua.zentix.almighty.world.Observe;
import ua.zentix.almighty.world.Rays;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Vision without an image: eye position and direction, crosshair target (within hand reach and farther along
 * the ray), nearby entities with distance, direction from view, whether in frame or hidden by blocks, and
 * what the bot heard. Loaded data only: rays through ready chunks ({@link Rays}), entities from loaded sections.
 * Server thread.
 */
public final class Eye {
    public static final int MAX_ENTITIES = 40;

    private Eye() {}

    /**
     * Camera: eye point, rotation (yaw clockwise from above, 0 south; pitch positive downward), vertical field
     * of view and frame aspect ratio; basis is forward, right, up.
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

        /** Degrees right (+) and down (+) from the view direction to a point. */
        double[] offset(Vec3 at) {
            Vec3 d = at.subtract(eye);
            double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
            float yawTo = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
            float pitchTo = (float) -(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG);
            return new double[] {Mth.wrapDegrees(yawTo - yaw), pitchTo - pitch};
        }

        /** Point is in frame (in front of the eye and within the field of view). */
        boolean inView(Vec3 at) {
            Vec3 d = at.subtract(eye);
            double z = d.dot(forward);
            if (z <= 0.05) return false;
            return Math.abs(d.dot(right) / z) <= tanH() && Math.abs(d.dot(up) / z) <= tanV();
        }
    }

    /**
     * What the eye sees: {@code viewer} is its owner (null for a free camera), {@code bot} supplies its hearing
     * (null for none). {@code radius} selects entities in a sphere; {@code distance} is the far crosshair ray.
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
        out.addProperty("offsets", "yaw_offset: degrees right (+) or left (−) of the view direction; pitch_offset: degrees down (+) or up (−)");
        if (bot != null) out.add("sounds", sounds(level, cam, bot, soundTicks));
        return out;
    }

    private static String facing(float yaw, float pitch) {
        if (pitch > 60) return "down";
        if (pitch < -60) return "up";
        String[] sides = {"south", "southwest", "west", "northwest", "north", "northeast", "east", "southeast"};
        return sides[Math.floorMod(Math.round(Mth.wrapDegrees(yaw) / 45.0F), 8)];
    }

    /** First object on the view ray up to {@code distance}: block (including fluid) or entity. */
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
            o.addProperty("none", "chunk not loaded beyond " + Observe.round(ray.block().getLocation().distanceTo(cam.eye()), 0) + " blocks");
        } else {
            o.addProperty("none", "nothing within " + Math.round(distance) + " blocks");
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

    /** Whether part of the entity box is visible: center, top, bottom; at least one unobstructed. */
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

    /** Entity color in the image, by kind. */
    public static int colour(Entity e) {
        if (e instanceof net.minecraft.world.entity.player.Player) return 0xE8473C;
        if (e instanceof Enemy) return 0xB02CC9;
        if (e instanceof LivingEntity) return 0xF2C230;
        if (e instanceof net.minecraft.world.entity.item.ItemEntity) return 0xFFFFFF;
        if (e instanceof net.minecraft.world.entity.projectile.Projectile) return 0xFF8C1A;
        return 0x2EC4E6;
    }
}
