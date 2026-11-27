package ua.zentix.almighty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import ua.zentix.almighty.bot.Bots;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Bridge;
import ua.zentix.almighty.bridge.Dispatch;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.audit.Audit;
import ua.zentix.almighty.audit.Pause;
import ua.zentix.almighty.feed.Feed;
import ua.zentix.almighty.feed.Inbox;
import ua.zentix.almighty.mcp.McpEndpoint;
import ua.zentix.almighty.policy.Gate;
import ua.zentix.almighty.policy.Nodes;
import ua.zentix.almighty.policy.Policies;
import ua.zentix.almighty.policy.Policy;
import ua.zentix.almighty.policy.Tickets;
import ua.zentix.almighty.policy.DenyList;
import ua.zentix.almighty.rules.Rules;
import ua.zentix.almighty.work.Job;
import ua.zentix.almighty.work.Jobs;
import ua.zentix.almighty.work.LagGuard;
import ua.zentix.almighty.world.Areas;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    /** {@code config/almighty} of the running server (policy, agents). */
    private static Path configDir;

    private final MinecraftServer server;
    private final Feed feed = new Feed();
    private final Jobs jobs;
    private final Areas areas;
    /** Shared by all scripts and rules until server shutdown (the {@code state} variable); server thread only. */
    private final Map<String, Object> state = new LinkedHashMap<>();
    private final Rules rules;
    private final Bots bots;
    private final Dispatch dispatch;
    private final Tickets tickets = new Tickets(System::currentTimeMillis);
    private final Inbox inbox;
    private final Pause pause = new Pause();
    private final Policies policies;
    private final Gate gate;
    private static final java.util.Set<String> PAUSE_ALLOWS = java.util.Set.of("cancel", "area.release", "rule.remove", "bot.remove");
    private final Audit audit;
    private Bridge bridge;
    private long lastTick;
    /** World calls from bridge threads, run at tick end under the GM budget ({@link #runWorldCalls}). */
    private final Queue<Runnable> worldCalls = new ConcurrentLinkedQueue<>();
    private final LagGuard lagGuard = new LagGuard();
    private volatile boolean stopped;
    /** A drain of world calls is queued with the server's task queue (runs between ticks). */
    private final java.util.concurrent.atomic.AtomicBoolean drainQueued = new java.util.concurrent.atomic.AtomicBoolean();
    /** World-call time spent since this tick started (server thread only). */
    private long usedThisTick;

    private GmServer(MinecraftServer server) {
        this.server = server;
        this.jobs = new Jobs(feed, () -> clock.getAsLong());
        this.areas = new Areas(GmConfig.MAX_CHUNKS.get());
        this.rules = new Rules(server, NeoForge.EVENT_BUS, feed, state,
                server.getWorldPath(LevelResource.ROOT).resolve("almighty").resolve("rules.json"));
        this.bots = new Bots(this);
        // GameTest keeps the audit in memory: no files in the test run directory.
        this.audit = new Audit(server instanceof net.minecraft.gametest.framework.GameTestServer ? null
                : server.getServerDirectory().resolve("logs").resolve("almighty"),
                GmConfig.AUDIT_DAYS.get());
        jobs.paused(pause::paused);
        rules.paused(pause::all);
        this.inbox = new Inbox(server, feed, tickets, () -> bridge == null ? null : bridge.agents());
        this.policies = new Policies(GmServer.configDir == null ? null : GmServer.configDir.resolve("policy.toml"));
        this.gate = new Gate(policies, tickets, Nodes::holds, name -> {
            net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayerByName(name);
            return p != null && bots.of(p) != null;
        });
        this.dispatch = new Dispatch(Api.methods(this), List.of(
                (caller, method, params) -> DenyList.check(method, params),
                (caller, method, params) -> {
                    // Cancelling, releasing and removing only reduce effects: allowed, so a paused agent can clean up.
                    if (!Api.readOnly(method, params) && !PAUSE_ALLOWS.contains(method) && pause.paused(caller.name())) {
                        throw new RpcException(423, "paused", "Paused by the owner (/almighty resume): only reading is allowed");
                    }
                },
                gate),
                Api::readOnly);
        dispatch.listen((caller, method, params, text, result, error, nanos) ->
                audit.ended(caller, method, params, text, result, error, nanos, Api.readOnly(method, params)));
    }

    /** This server's game master; null if the server has not started yet or has already stopped. */
    public static GmServer of(MinecraftServer server) {
        GmServer gm = current;
        return gm != null && gm.server == server ? gm : null;
    }

    static GmServer start(MinecraftServer server, Path configDir) {
        // An existing install (its token predates this start) keeps the behaviour it had: preset private.
        boolean existing = java.nio.file.Files.exists(configDir.resolve("token"));
        GmServer.configDir = configDir;
        GmServer gm = new GmServer(server);
        current = gm;
        gm.policies.load(existing ? Policy.Preset.PRIVATE : Policy.Preset.PRIVATE);
        Nodes.start(server, gm.policies::current);
        gm.rules.load();
        if (server.isDedicatedServer() && GmConfig.BRIDGE_ENABLED.get()) {
            try {
                InetSocketAddress address = new InetSocketAddress(GmConfig.BRIDGE_HOST.get(), GmConfig.BRIDGE_PORT.get());
                Agents agents = Agents.load(configDir);
                gm.bridge = new Bridge(address, agents, gm.dispatch, Api.LONG, new HashSet<>(GmConfig.BRIDGE_ALLOWED_ORIGINS.get()));
                gm.bridge.context("/mcp", new McpEndpoint(gm.bridge, Api.LONG, Almighty.version(), null));
                gm.bridge.start();
                LOG.info("Almighty bridge listening on {}; agents {} (token of {} in {})", gm.bridge.address(), agents.names(),
                        Agents.DEFAULT, configDir.resolve("token").toAbsolutePath());
            } catch (IOException | RuntimeException e) {
                LOG.error("Almighty bridge failed to start", e);
                gm.bridge = null;
            }
        }
        return gm;
    }

    /** Tick start: a pause longer than {@code lag_ms} since the previous tick emits a {@code lag} event. */
    void tickStart() {
        usedThisTick = 0;
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

    /**
     * Tick end: bots (where the server ticks player connections), world calls and jobs within one shared budget
     * (the minimum while the lag guard holds), then chunk expiry and release.
     */
    void tickEnd() {
        bots.tick();
        if (lagGuard.update(server.getAverageTickTimeNanos() / 1e6, server.tickRateManager().millisecondsPerTick(),
                System.currentTimeMillis())) {
            JsonObject d = new JsonObject();
            d.addProperty("on", lagGuard.lagging());
            d.addProperty("mspt", Math.round(server.getAverageTickTimeNanos() / 1e4) / 100.0);
            feed.add("lag_guard", d);
            LOG.info("Almighty: lag guard {} (average tick {} ms)", lagGuard.lagging() ? "on: GM work at its minimum" : "off",
                    d.get("mspt"));
        }
        long budget = budget();
        runWorldCalls(Math.max(0, budget - usedThisTick), true);
        long jobsStart = System.nanoTime();
        jobs.tick(Math.max(0, budget - usedThisTick));
        // Jobs count too: calls answered between ticks get only what this tick left.
        usedThisTick += System.nanoTime() - jobsStart;
        areas.tick(server.getTickCount());
        inbox.tick(server.getTickCount());
    }

    /** The GM budget per tick; a quarter of it while the lag guard holds, so builds and views slow down but go on. */
    private long budget() {
        return lagGuard.lagging() ? GmConfig.workNanosPerTick() / 4 : GmConfig.workNanosPerTick();
    }

    /**
     * Run queued world calls while {@code nanos} last; {@code atLeastOne} at tick end, so the queue always progresses.
     * Time used counts against this tick's GM budget.
     */
    private void runWorldCalls(long nanos, boolean atLeastOne) {
        if (worldCalls.isEmpty() || (!atLeastOne && nanos <= 0)) return;
        long start = System.nanoTime();
        Runnable call;
        while ((call = worldCalls.poll()) != null) {
            call.run();
            if (System.nanoTime() - start >= nanos) break;
        }
        usedThisTick += System.nanoTime() - start;
    }

    /** Between ticks: answer queued calls at once while this tick's budget lasts; the rest wait for tick end. */
    private void drainBetweenTicks() {
        drainQueued.set(false);
        runWorldCalls(budget() - usedThisTick, false);
    }

    /** Whether the lag guard holds GM work at its minimum. */
    public boolean lagging() {
        return lagGuard.lagging();
    }

    /**
     * Shutdown: close the bridge, log out bots (saved as players), remove rules from the bus, cancel jobs and remove
     * all tickets immediately (before Incoming's {@code StopDrain}).
     */
    void stop() {
        stopped = true;
        if (bridge != null) bridge.stop();
        bridge = null;
        for (Runnable call; (call = worldCalls.poll()) != null; ) call.run();
        bots.stop();
        rules.stop();
        jobs.cancelAll();
        audit.stop();
        areas.releaseAll();
    }

    static void stopped(MinecraftServer server) {
        Nodes.stop(server);
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

    public Policies policies() {
        return policies;
    }

    public Gate gate() {
        return gate;
    }

    public Audit audit() {
        return audit;
    }

    public Pause pause() {
        return pause;
    }

    /** Pause one agent (or, null, every agent): its calls with effects are refused, its jobs freeze, its bots stop. */
    public int pause(String agent) {
        pause.pause(agent);
        int stopped = 0;
        for (ua.zentix.almighty.bot.Bot bot : bots.all()) {
            if (agent == null || bot.controller().equals(agent)) {
                bot.stop("paused by the owner");
                stopped++;
            }
        }
        return stopped;
    }

    public Inbox inbox() {
        return inbox;
    }

    /** Request tickets minted for addressed messages. */
    public Tickets tickets() {
        return tickets;
    }

    /** The method table behind every transport (the single choke point for checks). */
    public Dispatch dispatch() {
        return dispatch;
    }

    /** The running bridge; null on integrated servers, in GameTest or when disabled. */
    public Bridge bridge() {
        return bridge;
    }

    /** The {@code state} variable for scripts and rules. */
    public Map<String, Object> state() {
        return state;
    }

    /**
     * Call on the server thread; errors complete the future exceptionally. From another thread the call is queued and
     * runs between ticks while this tick's GM budget (shared with jobs) lasts, else at tick end (at least one call per
     * tick); on the server thread itself (GameTest, rules, commands) it runs at once.
     */
    public <T> CompletableFuture<T> onMain(MainCall<T> call) {
        CompletableFuture<T> out = new CompletableFuture<>();
        // The caller travels with the call: jobs and bots started on the server thread know which agent acts.
        ua.zentix.almighty.bridge.Caller caller = Dispatch.current();
        Runnable run = () -> {
            if (stopped) {
                out.completeExceptionally(RpcException.unavailable("The server is stopping"));
                return;
            }
            try {
                // Completed inside the caller's scope: continuations that run here (thenCompose on the server thread)
                // still act as this caller, so a job they submit is tagged with the right agent.
                Dispatch.as(caller, () -> out.complete(call.run()));
            } catch (VirtualMachineError e) {
                if (!(e instanceof StackOverflowError)) throw e;
                out.completeExceptionally(e);
            } catch (Throwable e) {
                // Never into the tick loop: server.execute used to log such throws; a queued call must not crash the tick.
                out.completeExceptionally(e);
            }
        };
        if (server.isSameThread()) run.run();
        else if (stopped) out.completeExceptionally(RpcException.unavailable("The server is stopping"));
        else {
            worldCalls.add(run);
            // Stopped meanwhile: the drain in stop() may have missed this call; it fails fast when run here.
            if (stopped) {
                for (Runnable r; (r = worldCalls.poll()) != null; ) r.run();
            } else if (drainQueued.compareAndSet(false, true)) {
                server.execute(this::drainBetweenTicks);
            }
        }
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
