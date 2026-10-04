package ua.zentix.airstrikegm.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrikegm.GmServer;
import ua.zentix.airstrikegm.bridge.Args;
import ua.zentix.airstrikegm.bridge.RpcException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Взгляд на сервер без картинок: темп тика, игроки, сущности. Только то, что уже в памяти: сущности — из загруженных
 * секций ({@code getEntities} по рамке), чанки не грузятся. Поток сервера.
 */
public final class Observe {
    static final int MAX_ENTITIES = 200;
    static final int MAX_BOX = 2048;

    private Observe() {}

    public static JsonElement status(GmServer gm) {
        MinecraftServer server = gm.server();
        JsonObject out = new JsonObject();
        out.addProperty("boot", gm.feed().boot());
        out.addProperty("version", server.getServerVersion());
        out.addProperty("tick", server.getTickCount());
        double mspt = server.getAverageTickTimeNanos() / 1e6;
        long max = 0;
        for (long t : server.getTickTimesNanos()) max = Math.max(max, t);
        float rate = server.tickRateManager().tickrate();
        out.addProperty("tick_rate", rate);
        out.addProperty("frozen", server.tickRateManager().isFrozen());
        out.addProperty("mspt", round(mspt, 2));
        out.addProperty("mspt_max", round(max / 1e6, 2));
        out.addProperty("tps", round(Math.min(rate, 1000.0 / Math.max(mspt, 1e-3)), 2));
        JsonArray players = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) players.add(p.getGameProfile().getName());
        out.add("players", players);
        JsonArray levels = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            JsonObject l = new JsonObject();
            l.addProperty("dimension", Dims.id(level));
            l.addProperty("chunks", level.getChunkSource().getLoadedChunksCount());
            Map<String, Integer> types = new HashMap<>();
            int entities = 0;
            for (Entity e : level.getAllEntities()) {
                entities++;
                types.merge(EntityType.getKey(e.getType()).toString(), 1, Integer::sum);
            }
            l.addProperty("entities", entities);
            l.add("top_entities", top(types, 10));
            l.addProperty("day_time", level.getDayTime());
            l.addProperty("raining", level.isRaining());
            l.addProperty("thundering", level.isThundering());
            levels.add(l);
        }
        out.add("levels", levels);
        JsonObject work = new JsonObject();
        work.addProperty("jobs", gm.jobs().running());
        work.addProperty("chunks_held", gm.areas().held());
        work.addProperty("chunk_tickets", gm.areas().ticketed());
        out.add("gm", work);
        return out;
    }

    public static JsonElement players(MinecraftServer server) {
        JsonArray out = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) out.add(brief(server, p));
        return out;
    }

    public static JsonElement player(MinecraftServer server, Args args) throws RpcException {
        String name = args.string("name");
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        if (p == null) throw RpcException.notFound("Нет в игре: " + name);
        return details(server, p);
    }

    /** Игрок подробно: кратко, опыт, хотбар, инвентарь, эффекты, точка возрождения, блок под взглядом. */
    public static JsonObject details(MinecraftServer server, ServerPlayer p) {
        JsonObject out = brief(server, p);
        out.addProperty("xp_level", p.experienceLevel);
        out.addProperty("selected_slot", p.getInventory().selected);
        JsonArray items = new JsonArray();
        Inventory inv = p.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            item.addProperty("count", stack.getCount());
            item.addProperty("name", stack.getHoverName().getString());
            if (stack.isDamageableItem()) item.addProperty("durability_left", stack.getMaxDamage() - stack.getDamageValue());
            items.add(item);
        }
        out.add("inventory", items);
        out.addProperty("inventory_slots", "0–8 хотбар, 9–35 рюкзак, 36–39 броня (ноги…голова), 40 вторая рука");
        JsonArray effects = new JsonArray();
        for (MobEffectInstance effect : p.getActiveEffects()) {
            JsonObject e = new JsonObject();
            e.addProperty("effect", effect.getEffect().getRegisteredName());
            e.addProperty("amplifier", effect.getAmplifier());
            e.addProperty("ticks", effect.getDuration());
            effects.add(e);
        }
        out.add("effects", effects);
        BlockPos respawn = p.getRespawnPosition();
        if (respawn != null) {
            JsonObject r = new JsonObject();
            r.addProperty("dimension", p.getRespawnDimension().location().toString());
            r.add("pos", pos(respawn));
            out.add("respawn", r);
        }
        // по готовым чанкам: только что перенесённый игрок (бот, /tp) ещё не держит чанки вокруг себя
        Vec3 eye = p.getEyePosition();
        Rays.Hit ray = Rays.clip(p.serverLevel(), eye, eye.add(p.getViewVector(1.0F).scale(20)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p);
        if (ray.hit()) {
            BlockHitResult block = ray.block();
            JsonObject look = new JsonObject();
            look.add("pos", pos(block.getBlockPos()));
            look.addProperty("block", BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(block.getBlockPos()).getBlock()).toString());
            look.addProperty("face", block.getDirection().getSerializedName());
            out.add("looking_at", look);
        }
        return out;
    }

    /**
     * Сущности в рамке: {@code from}/{@code to} ([x, y, z]) или {@code center} + {@code radius}; {@code type} — фильтр
     * по типу; ближние к центру первыми, не больше {@code limit}; счёт по типам — по всем.
     */
    public static JsonElement entities(MinecraftServer server, Args args) throws RpcException {
        ServerLevel level = Dims.level(server, args);
        AABB box;
        Vec3 centre;
        if (args.has("center")) {
            int[] c = args.ints("center", 3);
            int r = args.integer("radius", 64, 1, MAX_BOX / 2);
            centre = new Vec3(c[0] + 0.5, c[1] + 0.5, c[2] + 0.5);
            box = new AABB(centre, centre).inflate(r);
        } else {
            int[] a = args.ints("from", 3), b = args.ints("to", 3);
            box = new AABB(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                    Math.max(a[0], b[0]) + 1, Math.max(a[1], b[1]) + 1, Math.max(a[2], b[2]) + 1);
            if (box.getXsize() > MAX_BOX || box.getZsize() > MAX_BOX) throw RpcException.badRequest("Рамка не шире " + MAX_BOX + " блоков");
            centre = box.getCenter();
        }
        String type = args.string("type", null);
        int limit = args.integer("limit", 50, 0, MAX_ENTITIES);
        List<Entity> found = new ArrayList<>(level.getEntities((Entity) null, box,
                e -> type == null || EntityType.getKey(e.getType()).toString().equals(type) || EntityType.getKey(e.getType()).getPath().equals(type)));
        Map<String, Integer> types = new HashMap<>();
        for (Entity e : found) types.merge(EntityType.getKey(e.getType()).toString(), 1, Integer::sum);
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(centre)));
        JsonArray list = new JsonArray();
        for (Entity e : found.subList(0, Math.min(limit, found.size()))) list.add(entity(e));
        JsonObject out = new JsonObject();
        out.addProperty("total", found.size());
        out.add("by_type", top(types, 30));
        out.add("nearest", list);
        return out;
    }

    private static JsonObject brief(MinecraftServer server, ServerPlayer p) {
        JsonObject out = new JsonObject();
        out.addProperty("name", p.getGameProfile().getName());
        out.addProperty("uuid", p.getUUID().toString());
        out.addProperty("dimension", Dims.id(p.serverLevel()));
        out.add("pos", vec(p.position()));
        out.addProperty("yaw", round(p.getYRot(), 1));
        out.addProperty("pitch", round(p.getXRot(), 1));
        out.addProperty("health", round(p.getHealth(), 1));
        out.addProperty("food", p.getFoodData().getFoodLevel());
        out.addProperty("gamemode", p.gameMode.getGameModeForPlayer().getName());
        out.addProperty("op_level", server.getProfilePermissions(p.getGameProfile()));
        out.addProperty("ping_ms", p.connection.latency());
        if (p.isDeadOrDying()) out.addProperty("dead", true);
        if (p.getVehicle() != null) out.addProperty("vehicle", EntityType.getKey(p.getVehicle().getType()).toString());
        return out;
    }

    private static JsonObject entity(Entity e) {
        JsonObject out = new JsonObject();
        out.addProperty("type", EntityType.getKey(e.getType()).toString());
        if (e instanceof ServerPlayer || e.hasCustomName()) out.addProperty("name", e.getName().getString());
        out.addProperty("uuid", e.getUUID().toString());
        out.add("pos", vec(e.position()));
        if (e instanceof LivingEntity living) out.addProperty("health", round(living.getHealth(), 1));
        if (!e.getTags().isEmpty()) {
            JsonArray tags = new JsonArray();
            e.getTags().forEach(tags::add);
            out.add("tags", tags);
        }
        return out;
    }

    private static JsonObject top(Map<String, Integer> counts, int n) {
        JsonObject out = new JsonObject();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(n)
                .forEach(e -> out.addProperty(e.getKey(), e.getValue()));
        return out;
    }

    public static JsonArray pos(BlockPos p) {
        JsonArray out = new JsonArray();
        out.add(p.getX());
        out.add(p.getY());
        out.add(p.getZ());
        return out;
    }

    public static JsonArray vec(Vec3 v) {
        JsonArray out = new JsonArray();
        out.add(round(v.x, 1));
        out.add(round(v.y, 1));
        out.add(round(v.z, 1));
        return out;
    }

    public static double round(double v, int digits) {
        double k = Math.pow(10, digits);
        return Math.round(v * k) / k;
    }
}
