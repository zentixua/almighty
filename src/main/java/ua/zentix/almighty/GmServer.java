package ua.zentix.almighty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import ua.zentix.almighty.bot.Bots;
import ua.zentix.almighty.bridge.Bridge;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.bridge.Token;
import ua.zentix.almighty.feed.Feed;
import ua.zentix.almighty.rules.Rules;
import ua.zentix.almighty.work.Job;
import ua.zentix.almighty.work.Jobs;
import ua.zentix.almighty.world.Areas;

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
 * The game master for one server run: feed, job queue, chunks and bridge. Lives from {@code ServerStartedEvent} to
 * {@code ServerStoppedEvent}; each singleplayer world gets its own instance.
 */
public final class GmServer {
    private static final Logger LOG = LogUtils.getLogger();
    private static volatile GmServer current;

    /** GameTest jobs measure time with their own clock: ticks run without pauses, so wall-clock time is unsuitable. */
    public static LongSupplier clock = System::nanoTime;

    private final MinecraftServer server;
    private final Feed feed = new Feed();
    private final Jobs jobs;
    private final Areas areas;
    /** Shared by all scripts and rules until server shutdown (the {@code state} variable); server thread only. */
    private final Map<String, Object> state = new LinkedHashMap<>();
    private final Rules rules;
    private final Bots bots;
    private Bridge bridge;
    private long lastTick;

    private GmServer(MinecraftServer server) {
        this.server = server;
        this.jobs = new Jobs(feed, () -> clock.getAsLong());
        this.areas = new Areas(GmConfig.MAX_CHUNKS.get());
        this.rules = new Rules(server, NeoForge.EVENT_BUS, feed, state,
                server.getWorldPath(LevelResource.ROOT).resolve("almighty").resolve("rules.json"));
        this.bots = new Bots(this);
    }

    /** This server's game master; null if the server has not started yet or has already stopped. */
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
                LOG.info("Almighty bridge listening on {}; token in {}", gm.bridge.address(), tokenFile.toAbsolutePath());
            } catch (IOException | RuntimeException e) {
                LOG.error("Almighty bridge failed to start", e);
                gm.bridge = null;
            }
        }
        return gm;
    }

    /** Tick start: a pause longer than {@code lag_ms} since the previous tick emits a {@code lag} event. */
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

    /** Tick end: bots (where the server ticks player connections), jobs within budget, then chunk expiry and release. */
    void tickEnd() {
        bots.tick();
        jobs.tick(GmConfig.workNanosPerTick());
        areas.tick(server.getTickCount());
    }

    /**
     * Shutdown: close the bridge, log out bots (saved as players), remove rules from the bus, cancel jobs and remove
     * all tickets immediately (before Incoming's {@code StopDrain}).
     */
    void stop() {
        if (bridge != null) bridge.stop();
        bridge = null;
        bots.stop();
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

    public Bots bots() {
        return bots;
    }

    /** The {@code state} variable for scripts and rules. */
    public Map<String, Object> state() {
        return state;
    }

    /** Call on the server thread between ticks; errors complete the future exceptionally. */
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
     * Submit a job from the server thread and wait up to {@code waitSeconds} for completion; if unfinished, return
     * its current description (the job continues and remains visible in {@code jobs}).
     */
    public CompletableFuture<JsonElement> submit(MainCall<? extends Job> factory, int waitSeconds) {
        return onMain(() -> jobs.submit(factory.run())).thenCompose(job -> await(job, waitSeconds));
    }

    /** Wait up to {@code waitSeconds} for job completion. */
    public CompletableFuture<JsonElement> await(Job job, int waitSeconds) {
        CompletableFuture<JsonElement> out = new CompletableFuture<>();
        // Copy: the job future's description is shared by all waiters, while a snapshot appends an image to the response.
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
