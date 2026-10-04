package ua.zentix.airstrikegm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import ua.zentix.airstrikegm.bridge.Bridge;
import ua.zentix.airstrikegm.bridge.RpcException;
import ua.zentix.airstrikegm.bridge.Token;
import ua.zentix.airstrikegm.feed.Feed;
import ua.zentix.airstrikegm.rules.Rules;
import ua.zentix.airstrikegm.work.Job;
import ua.zentix.airstrikegm.work.Jobs;
import ua.zentix.airstrikegm.world.Areas;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Ведущий на одном запуске сервера: лента, очередь работ, чанки, мост. Живёт от {@code ServerStartedEvent} до
 * {@code ServerStoppedEvent}; в одиночной игре каждый мир получает свой.
 */
public final class GmServer {
    private static final Logger LOG = LogUtils.getLogger();
    private static volatile GmServer current;

    /** Работы в GameTest меряют время своими часами: тик идёт без пауз, настенное время там не годится. */
    public static LongSupplier clock = System::nanoTime;

    private final MinecraftServer server;
    private final Feed feed = new Feed();
    private final Jobs jobs;
    private final Areas areas;
    /** Общее для всех скриптов и правил до остановки сервера (переменная {@code state}); только поток сервера. */
    private final Map<String, Object> state = new LinkedHashMap<>();
    private final Rules rules;
    private Bridge bridge;
    private long lastTick;

    private GmServer(MinecraftServer server) {
        this.server = server;
        this.jobs = new Jobs(feed, () -> clock.getAsLong());
        this.areas = new Areas(GmConfig.MAX_CHUNKS.get());
        this.rules = new Rules(server, NeoForge.EVENT_BUS, feed, state,
                server.getWorldPath(LevelResource.ROOT).resolve("airstrike_gm").resolve("rules.json"));
    }

    /** Ведущий этого сервера; null — сервер ещё не запустился или уже остановлен. */
    public static GmServer of(MinecraftServer server) {
        GmServer gm = current;
        return gm != null && gm.server == server ? gm : null;
    }

    static GmServer start(MinecraftServer server, Path tokenFile) {
        GmServer gm = new GmServer(server);
        current = gm;
        gm.rules.load();
        if (server.isDedicatedServer() && GmConfig.BRIDGE_ENABLED.get()) {
            try {
                InetSocketAddress address = new InetSocketAddress(GmConfig.BRIDGE_HOST.get(), GmConfig.BRIDGE_PORT.get());
                gm.bridge = new Bridge(address, Token.load(tokenFile), Api.methods(gm));
                gm.bridge.start();
                LOG.info("Мост ведущего слушает {}; токен — в {}", gm.bridge.address(), tokenFile.toAbsolutePath());
            } catch (IOException | RuntimeException e) {
                LOG.error("Мост ведущего не запустился", e);
                gm.bridge = null;
            }
        }
        return gm;
    }

    /** Начало тика: пауза дольше {@code lag_ms} с прошлого тика — событие {@code lag}. */
    void tickStart() {
        long now = System.nanoTime();
        if (lastTick != 0) {
            long ms = (now - lastTick) / 1_000_000L;
            if (ms > GmConfig.LAG_MS.get()) {
                JsonObject d = new JsonObject();
                d.addProperty("ms", ms);
                feed.add("lag", d);
            }
        }
        lastTick = now;
    }

    /** Конец тика: работы под бюджетом, затем сроки и отпуск чанков. */
    void tickEnd() {
        jobs.tick(GmConfig.workNanosPerTick());
        areas.tick(server.getTickCount());
    }

    /** Остановка: мост закрыт, правила сняты с шины, работы отменены, тикеты сняты сразу (до {@code StopDrain} Airstrike). */
    void stop() {
        if (bridge != null) bridge.stop();
        bridge = null;
        rules.stop();
        jobs.cancelAll();
        areas.releaseAll();
    }

    static void stopped(MinecraftServer server) {
        if (current != null && current.server == server) current = null;
    }

    public MinecraftServer server() {
        return server;
    }

    public Feed feed() {
        return feed;
    }

    public Jobs jobs() {
        return jobs;
    }

    public Areas areas() {
        return areas;
    }

    public Rules rules() {
        return rules;
    }

    /** Переменная {@code state} скриптов и правил. */
    public Map<String, Object> state() {
        return state;
    }

    /** Вызов в потоке сервера между тиками; ошибка — исключением в будущем. */
    public <T> CompletableFuture<T> onMain(MainCall<T> call) {
        CompletableFuture<T> out = new CompletableFuture<>();
        server.execute(() -> {
            try {
                out.complete(call.run());
            } catch (RpcException | RuntimeException e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    /**
     * Поставить работу из потока сервера и ждать её конца не дольше {@code waitSeconds}; не кончилась — описание с
     * текущим состоянием (работа идёт дальше, её видно в {@code jobs}).
     */
    public CompletableFuture<JsonElement> submit(MainCall<? extends Job> factory, int waitSeconds) {
        return onMain(() -> jobs.submit(factory.run())).thenCompose(job -> await(job, waitSeconds));
    }

    /** Ждать конца работы не дольше {@code waitSeconds}. */
    public CompletableFuture<JsonElement> await(Job job, int waitSeconds) {
        CompletableFuture<JsonElement> out = new CompletableFuture<>();
        // копия: описание в будущем работы общее для всех ждущих, а снимок дописывает в ответ картинку
        job.done().thenAccept(d -> out.complete(d.deepCopy()));
        if (!job.done().isDone()) {
            CompletableFuture.delayedExecutor(waitSeconds, TimeUnit.SECONDS).execute(() -> {
                if (out.isDone()) return;
                onMain(job::describe).whenComplete((d, e) -> {
                    if (e != null) out.completeExceptionally(e instanceof CompletionException c ? c.getCause() : e);
                    else out.complete(d);
                });
            });
        }
        return out;
    }

    @FunctionalInterface
    public interface MainCall<T> {
        T run() throws RpcException;
    }
}
