package ua.zentix.airstrikegm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import ua.zentix.airstrikegm.act.Chat;
import ua.zentix.airstrikegm.act.CommandRunner;
import ua.zentix.airstrikegm.bridge.Args;
import ua.zentix.airstrikegm.bridge.Method;
import ua.zentix.airstrikegm.bridge.RpcException;
import ua.zentix.airstrikegm.building.BuildJob;
import ua.zentix.airstrikegm.building.BuildPlan;
import ua.zentix.airstrikegm.view.BlockText;
import ua.zentix.airstrikegm.view.LookView;
import ua.zentix.airstrikegm.view.MapView;
import ua.zentix.airstrikegm.view.View;
import ua.zentix.airstrikegm.work.Job;
import ua.zentix.airstrikegm.world.Areas;
import ua.zentix.airstrikegm.world.Dims;
import ua.zentix.airstrikegm.world.Observe;
import ua.zentix.airstrikegm.world.PrepareJob;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Методы моста. Параметры разбираются в потоке моста; мир читается и меняется в потоке сервера ({@link GmServer#onMain})
 * или работой под бюджетом тика. Методы с {@code wait} ждут конца работы не дольше стольких секунд и иначе отдают её
 * текущее описание: дальше — {@code job}.
 */
public final class Api {
    static final int MAX_WAIT = 50;
    /** Сколько тиков постройка ждёт загрузки своих чанков: 2 минуты при 20 тиках в секунду. */
    static final long CHUNK_WAIT_TICKS = 2400;

    private Api() {}

    public static Map<String, Method> methods(GmServer gm) {
        MinecraftServer server = gm.server();
        Map<String, Method> m = new LinkedHashMap<>();
        m.put("status", a -> gm.onMain(() -> Observe.status(gm)));
        m.put("players", a -> gm.onMain(() -> Observe.players(server)));
        m.put("player", a -> gm.onMain(() -> Observe.player(server, a)));
        m.put("entities", a -> gm.onMain(() -> Observe.entities(server, a)));
        m.put("command", a -> gm.onMain(() -> CommandRunner.commands(server, a)));
        m.put("function", a -> gm.onMain(() -> CommandRunner.function(server, a)));
        m.put("say", a -> gm.onMain(() -> Chat.say(server, a)));
        m.put("blocks", a -> gm.onMain(() -> BlockText.read(Dims.level(server, a), a.ints("from", 3), a.ints("to", 3), a.bool("properties", true))));
        m.put("map", a -> view(gm, a, () -> map(server, a)));
        m.put("look", a -> view(gm, a, () -> new LookView(Dims.level(server, a), a.ints("from", 3), a.ints("to", 3), LookView.Look.parse(a.string("look")))));
        m.put("build", a -> build(gm, a));
        m.put("undo", a -> {
            long id = a.integer("job", 1, Integer.MAX_VALUE);
            return gm.submit(() -> {
                Job of = gm.jobs().get(id);
                if (!(of instanceof BuildJob build)) throw RpcException.notFound("Нет постройки №" + id + " (в истории — последние 50 работ)");
                return BuildJob.undo(build, CHUNK_WAIT_TICKS);
            }, waitSeconds(a));
        });
        m.put("jobs", a -> gm.onMain(() -> gm.jobs().describe()));
        m.put("job", a -> {
            long id = a.integer("id", 1, Integer.MAX_VALUE);
            int wait = a.integer("wait", 0, 0, MAX_WAIT);
            return gm.onMain(() -> {
                Job job = gm.jobs().get(id);
                if (job == null) throw RpcException.notFound("Нет работы №" + id + " (в истории — последние 50 работ)");
                return job;
            }).thenCompose(job -> gm.await(job, wait).thenApplyAsync(result ->
                    job instanceof View view ? view.attach(result.getAsJsonObject()) : result));
        });
        m.put("cancel", a -> {
            long id = a.integer("id", 1, Integer.MAX_VALUE);
            return gm.onMain(() -> {
                Job job = gm.jobs().get(id);
                if (job == null) throw RpcException.notFound("Нет работы №" + id);
                if (!gm.jobs().cancel(id)) throw RpcException.conflict("Работа №" + id + " уже кончилась: " + job.state().id());
                return job.describe();
            });
        });
        m.put("area.prepare", a -> {
            int[] from = a.ints("from", 2), to = a.ints("to", 2);
            int ttl = a.integer("ttl_seconds", 300, 1, 3600);
            int wait = a.integer("wait", 0, 0, MAX_WAIT);
            return gm.onMain(() -> {
                Areas.Lease lease = gm.areas().acquire(Dims.level(server, a), "area", from[0], from[1], to[0], to[1], ttl * 20L);
                lease.owner("area №" + lease.id());
                return lease;
            }).thenCompose(lease -> wait == 0
                    ? gm.onMain(lease::describe)
                    : gm.submit(() -> new PrepareJob(lease), wait).thenCompose(r -> gm.onMain(lease::describe)));
        });
        m.put("area.release", a -> {
            long id = a.integer("id", 1, Integer.MAX_VALUE);
            return gm.onMain(() -> {
                Areas.Lease lease = gm.areas().get(id);
                if (lease == null) throw RpcException.notFound("Нет района №" + id + " (его срок мог выйти)");
                lease.release();
                JsonObject out = new JsonObject();
                out.addProperty("released", id);
                return out;
            });
        });
        m.put("areas", a -> gm.onMain(() -> gm.areas().describe()));
        m.put("events", a -> {
            long after = a.longValue("after", 0, 0, Long.MAX_VALUE);
            int wait = a.integer("wait", 0, 0, 25);
            int limit = a.integer("limit", 200, 1, 1000);
            try {
                return CompletableFuture.completedFuture(gm.feed().read(after, limit, wait * 1000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RpcException.unavailable("Мост останавливается");
            }
        });
        return m;
    }

    private static int waitSeconds(Args a) throws RpcException {
        return a.integer("wait", 30, 0, MAX_WAIT);
    }

    /** Снимок работой; картинка кодируется в PNG вне потока сервера. */
    private static CompletableFuture<JsonElement> view(GmServer gm, Args a, GmServer.MainCall<View> factory) throws RpcException {
        int wait = waitSeconds(a);
        CompletableFuture<View> started = gm.onMain(() -> gm.jobs().submit(factory.run()));
        return started.thenCompose(view -> gm.await(view, wait).thenApplyAsync(result -> view.attach(result.getAsJsonObject())));
    }

    /** {@code center} [x, z] и {@code size} (по умолчанию 128) или {@code from}/{@code to} [x, z]. */
    private static MapView map(MinecraftServer server, Args a) throws RpcException {
        ServerLevel level = Dims.level(server, a);
        int x0, z0, sx, sz;
        if (a.has("center")) {
            int[] c = a.ints("center", 2);
            int size = a.integer("size", 128, 1, MapView.MAX_SIDE);
            x0 = c[0] - size / 2;
            z0 = c[1] - size / 2;
            sx = sz = size;
        } else {
            int[] from = a.ints("from", 2), to = a.ints("to", 2);
            x0 = Math.min(from[0], to[0]);
            z0 = Math.min(from[1], to[1]);
            sx = Math.abs(from[0] - to[0]) + 1;
            sz = Math.abs(from[1] - to[1]) + 1;
            if (sx > MapView.MAX_SIDE || sz > MapView.MAX_SIDE) throw RpcException.badRequest("Карта — не больше " + MapView.MAX_SIDE + " блоков по стороне");
        }
        Integer below = a.has("below") ? a.integer("below", -2048, 4096) : null;
        List<MapView.Mark> marks = new ArrayList<>();
        if (a.has("marks")) {
            for (JsonElement e : a.array("marks")) {
                if (!e.isJsonObject()) throw RpcException.badRequest("marks: объекты {label, x, z}");
                Args mark = new Args(e.getAsJsonObject());
                marks.add(new MapView.Mark(mark.string("label", "?"), mark.integer("x", -30_000_000, 30_000_000), mark.integer("z", -30_000_000, 30_000_000)));
            }
        }
        return new MapView(level, x0, z0, sx, sz, below, marks);
    }

    /**
     * Постройка: план разбирается в потоке моста (реестры после запуска только читаются), чанки берутся и работа
     * ставится — в потоке сервера.
     */
    private static CompletableFuture<JsonElement> build(GmServer gm, Args a) throws RpcException {
        MinecraftServer server = gm.server();
        ServerLevel level = Dims.level(server, a);
        HolderLookup<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
        BuildPlan<BlockInput> plan = BuildPlan.parse(a.array("ops"), s -> {
            try {
                BlockStateParser.BlockResult r = BlockStateParser.parseForBlock(blocks, s, true);
                return new BlockInput(r.blockState(), r.properties().keySet(), r.nbt());
            } catch (CommandSyntaxException e) {
                throw RpcException.badRequest("блок «" + s + "»: " + e.getMessage());
            }
        }, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1, GmConfig.BUILD_MAX_BLOCKS.get());
        return gm.submit(() -> BuildJob.build(level, gm.areas(), plan, CHUNK_WAIT_TICKS), waitSeconds(a));
    }
}
