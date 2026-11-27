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
import ua.zentix.almighty.audit.Journal;
import ua.zentix.almighty.audit.Pause;
import ua.zentix.almighty.feed.Feed;
import ua.zentix.almighty.feed.Inbox;
import ua.zentix.almighty.mcp.Mcp;
import ua.zentix.almighty.policy.Confirmations;
import ua.zentix.almighty.policy.Decisions;
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
import com.google.gson.JsonArray;
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
    private final ua.zentix.almighty.policy.Quotas quotas = new ua.zentix.almighty.policy.Quotas(System::currentTimeMillis);
    private final Journal journal;
    private final Policies policies;
    private final Gate gate;
    private final Decisions decisions;
    private static final java.util.Set<String> PAUSE_ALLOWS = java.util.Set.of("cancel", "area.release", "rule.remove", "bot.remove");
    private final Audit audit;
    private Bridge bridge;
    private Mcp mcp;
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
        this.journal = new Journal(server.getWorldPath(LevelResource.ROOT).resolve("almighty").resolve("changes"));
        // GameTest keeps the audit in memory: no files in the test run directory.
        this.audit = new Audit(server instanceof net.minecraft.gametest.framework.GameTestServer ? null
                : server.getServerDirectory().resolve("logs").resolve("almighty"),
                GmConfig.AUDIT_DAYS.get());
        jobs.paused(pause::paused);
        rules.paused(pause::all);
        this.inbox = new Inbox(server, feed, tickets, () -> bridge == null ? null : bridge.agents());
        this.policies = new Policies(GmServer.configDir == null ? null : GmServer.configDir.resolve("policy.toml"));
        // Bot names from a thread-safe set the bots keep (the gate runs on bridge threads).
        this.gate = new Gate(policies, tickets, Nodes::holds, bots::isBotName);
        this.decisions = new Decisions(server, new Confirmations(System::currentTimeMillis), tickets, feed);
        gate.confirmations(decisions.confirmations(), decisions::prompt);
        Confirmations.players(name -> Nodes.online(name));
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
        decisions.dispatch(dispatch);
        dispatch.requester((caller, params) -> {
            try {
                return params.get("for").isJsonPrimitive() ? tickets.check(params.get("for").getAsString(), caller.name()).player() : null;
            } catch (RpcException e) {
                return null;
            }
        });
        dispatch.listen((caller, method, params, text, result, error, nanos, change) ->
                audit.ended(caller, method, params, text, result, error, nanos, Api.readOnly(method, params), change));
        // The change journal: every call with effects records its block writes, and its jobs continue the change.
        dispatch.around(new Dispatch.Around() {
            @Override
            public Object begin(ua.zentix.almighty.bridge.Caller caller, String method, JsonObject params) {
                return journal.begin(caller.name(), method, caller.requester());
            }

            @Override
            public <T, E extends Exception> T within(Object token, Dispatch.ThrowingSupplier<T, E> work) throws E {
                return Journal.with((Journal.Change) token, work);
            }

            @Override
            public void end(Object token) {
                Journal.Change c = (Journal.Change) token;
                // Released on the server thread: the change's file is written there.
                if (server.isSameThread()) c.release();
                else server.execute(c::release);
            }

            @Override
            public long id(Object token) {
                return ((Journal.Change) token).id;
            }
        });
        // Receipts: a player whose request changed something hears it at once, with undo for builds (plan 4.2).
        dispatch.listen((caller, method, params, text, result, error, nanos, change) -> {
            if (caller.requester() == null) return;
            if (error instanceof RpcException r && r.status() == 403 && r.code().equals("policy")) {
                server.execute(() -> inbox.refused(caller.requester()));
                return;
            }
            if (error != null || Api.readOnly(method, params)) return;
            // Commands report per command; a receipt only for what actually happened.
            if (result instanceof com.google.gson.JsonArray results) {
                for (var r : results) if (r instanceof JsonObject o && o.has("ok") && !o.get("ok").getAsBoolean()) return;
            }
            Long job = result instanceof JsonObject o && o.has("kind") && o.get("id") instanceof com.google.gson.JsonPrimitive id
                    && id.isNumber() && o.get("kind").getAsString().equals("build") ? id.getAsLong() : null;
            String what = method.equals("command") || method.equals("function") ? Confirmations.render(method, params)
                    : job != null ? "build #" + job : method;
            Long undoable = change > 0 ? change : null;
            server.execute(() -> {
                net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(caller.requester());
                if (p != null) p.sendSystemMessage(ua.zentix.almighty.policy.Prompts.receipt(what, undoable));
            });
        });
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
        Nodes.refresh(server);
        ua.zentix.almighty.policy.Identity.onlineMode(server.usesAuthentication());
        gm.rules.load();
        boolean integrated = !server.isDedicatedServer();
        if (integrated && GmConfig.BRIDGE_INTEGRATED.get() && !loopback(GmConfig.BRIDGE_HOST.get())) {
            LOG.error("Almighty bridge: bridge.integrated works only on loopback; bridge.host is {}", GmConfig.BRIDGE_HOST.get());
        } else if ((!integrated || GmConfig.BRIDGE_INTEGRATED.get()) && GmConfig.BRIDGE_ENABLED.get()) {
            try {
                InetSocketAddress address = new InetSocketAddress(GmConfig.BRIDGE_HOST.get(), GmConfig.BRIDGE_PORT.get());
                Agents agents = Agents.load(configDir);
                javax.net.ssl.SSLContext tls = tls(server);
                if (tls == null && !loopback(GmConfig.BRIDGE_HOST.get())) {
                    LOG.warn("Almighty bridge listens on {} without TLS: tokens and calls cross the network in plain text. Keep that "
                            + "address reachable only from this machine or a private network, or set bridge.tls_cert and bridge.tls_key",
                            GmConfig.BRIDGE_HOST.get());
                }
                gm.bridge = new Bridge(address, agents, gm.dispatch, Api.LONG, new HashSet<>(GmConfig.BRIDGE_ALLOWED_ORIGINS.get()), tls);
                gm.mcp = Mcp.mount(server, gm.bridge, configDir, GmConfig.BRIDGE_HOST.get(), GmConfig.BRIDGE_PUBLIC_URL.get(),
                        new HashSet<>(GmConfig.BRIDGE_ALLOWED_ORIGINS.get()));
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

    /** The owner's certificate when both TLS files are set; null for plain HTTP. A broken setting stops the bridge. */
    private static javax.net.ssl.SSLContext tls(MinecraftServer server) throws IOException {
        String cert = GmConfig.BRIDGE_TLS_CERT.get().strip(), key = GmConfig.BRIDGE_TLS_KEY.get().strip();
        if (cert.isEmpty() && key.isEmpty()) return null;
        if (cert.isEmpty() || key.isEmpty()) throw new IOException("bridge.tls_cert and bridge.tls_key go together");
        Path dir = server.getServerDirectory();
        try {
            return ua.zentix.almighty.bridge.Tls.context(dir.resolve(cert), dir.resolve(key));
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("TLS: " + e.getMessage(), e);
        }
    }

    static boolean loopback(String host) {
        return ua.zentix.almighty.mcp.signin.SignIn.loopback(host);
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
        journal.flush();
        inbox.tick(server.getTickCount());
        decisions.tick(server.getTickCount());
        if (server.getTickCount() % 20 == 0) Nodes.refresh(server);
        if (server.getTickCount() % 72_000 == 0) quotas.prune();
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
        journal.stop();
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

    public ua.zentix.almighty.policy.Quotas quotas() {
        return quotas;
    }

    public Decisions decisions() {
        return decisions;
    }

    /**
     * Who a command runs as (plan 4.3): in-process at level 4; unasked at the trust floor's level; for a request, as
     * that player (their entity, at the higher of their op level and their role's level, capped by the ceiling).
     */
    public ua.zentix.almighty.act.CommandRunner.As commandAs(Gate.Rights r) throws RpcException {
        if (r == null) return ua.zentix.almighty.act.CommandRunner.As.GM;
        if (r.ticket() == null) return new ua.zentix.almighty.act.CommandRunner.As(r.floor().commandLevel(), null);
        Confirmations.Pending approved = Confirmations.running();
        if (approved != null && approved.kind == Confirmations.Kind.ADMIN && approved.decider != null) {
            // An admin approved this one command: it runs as the player who asked, with the admin's level.
            net.minecraft.server.level.ServerPlayer asker = server.getPlayerList().getPlayer(approved.requester);
            net.minecraft.server.level.ServerPlayer admin = server.getPlayerList().getPlayer(approved.decider);
            if (asker == null) throw RpcException.conflict(approved.requesterName + " is offline");
            int level = Math.max(admin == null ? 0 : server.getProfilePermissions(admin.getGameProfile()), Nodes.role(approved.decider).commandLevel());
            return new ua.zentix.almighty.act.CommandRunner.As(Math.min(level, r.ceiling().commandLevel()), asker);
        }
        net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(r.ticket().player());
        if (p == null) throw RpcException.conflict(r.ticket().name() + " is offline: a request runs as its player, who must be online");
        int level = Math.max(server.getProfilePermissions(p.getGameProfile()), Nodes.role(p.getUUID()).commandLevel());
        return new ua.zentix.almighty.act.CommandRunner.As(Math.min(level, r.ceiling().commandLevel()), p);
    }

    /**
     * {@code ask_admin} for commands (server thread): a request whose command is listed there and which the player's
     * own source cannot run (vanilla refuses to parse it at their level) waits for an admin holding
     * {@code almighty.approve}, instead of failing. Everything else runs, or fails, as the player.
     */
    public void askAdmin(ua.zentix.almighty.bridge.Caller caller, Gate.Rights r, String method, JsonObject params,
                         ua.zentix.almighty.act.CommandRunner.As as) throws RpcException {
        if (r == null || r.ticket() == null || as.player() == null || Confirmations.running() != null) return;
        java.util.Set<String> listed = policies.current().safety().askAdmin();
        if (listed.isEmpty()) return;
        var dispatcher = server.getCommands().getDispatcher();
        net.minecraft.commands.CommandSourceStack mine = as.player().createCommandSourceStack().withPermission(as.level());
        for (String c : Confirmations.commands(method, params)) {
            String text = c.strip().replaceFirst("^[/$]+", "");
            boolean asked = false;
            for (String k : Confirmations.kinds("command", commandParams(text))) if (listed.contains(k)) asked = true;
            if (!asked) continue;
            var parsed = dispatcher.parse(text, mine);
            if (parsed.getReader().canRead() || parsed.getContext().getCommand() == null) {
                Confirmations.Pending p = decisions.confirmations().ask(Confirmations.Kind.ADMIN, caller, method, params, null,
                        ua.zentix.almighty.policy.Node.APPROVE, r.ticket().player(), r.ticket().name());
                if (p.count() == 1) decisions.prompt(p);
                throw Confirmations.waiting(p, "an admin");
            }
        }
    }

    private static JsonObject commandParams(String command) {
        JsonObject o = new JsonObject();
        o.addProperty("commands", command);
        return o;
    }

    public Policies policies() {
        return policies;
    }

    public Gate gate() {
        return gate;
    }

    /**
     * Revert journal change {@code id} (conflict-aware): one job per dimension it touched. {@code caller} null is the
     * owner (console); otherwise another agent's or requester's change needs {@code undo.any}, and reverting a revert
     * (a redo) needs the owner. Called on the bridge thread (reads the change file), jobs submitted on the server thread.
     */
    public CompletableFuture<JsonElement> revert(long id, ua.zentix.almighty.bridge.Caller caller, JsonObject params, int waitSeconds)
            throws RpcException {
        JsonObject meta = journal.describe(id);
        if (meta == null) throw RpcException.notFound("No change " + id + " (old changes are dropped beyond 4 million positions)");
        if (meta.has("undone_by")) throw RpcException.conflict("Change " + id + " was already undone by change " + meta.get("undone_by").getAsLong());
        if (caller != null && caller.agent() != null) {
            String agent = meta.get("agent").getAsString();
            String requester = meta.has("requester") ? meta.get("requester").getAsString() : null;
            String mine = caller.requester() == null ? null : caller.requester().toString();
            if (!agent.equals(caller.name()) || !java.util.Objects.equals(requester, mine)) {
                gate.require(caller, params, ua.zentix.almighty.policy.Node.UNDO_ANY, "change " + id + " was made for someone else");
            }
            if (meta.get("method").getAsString().equals("undo") && gate.rights(caller, params).effective() != ua.zentix.almighty.policy.Role.OWNER) {
                throw new RpcException(403, "policy", "Reverting an undo (a redo) needs the owner");
            }
        }
        Journal.Records r;
        try {
            var blocks = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
            r = journal.read(id, tag -> net.minecraft.nbt.NbtUtils.readBlockState(blocks, tag));
        } catch (java.io.IOException e) {
            throw RpcException.notFound("Change " + id + " cannot be read: " + e.getMessage());
        }
        if (r.size() == 0) throw RpcException.conflict("Change " + id + " changed no blocks");
        Journal.Records rec = r;
        return onMain(() -> {
            JsonArray jobs = new JsonArray();
            for (int d = 0; d < rec.dims().size(); d++) {
                int n = 0;
                for (int i = 0; i < rec.size(); i++) if (rec.dim()[i] == d) n++;
                long[] pos = new long[n];
                net.minecraft.world.level.block.state.BlockState[] before = new net.minecraft.world.level.block.state.BlockState[n], after =
                        new net.minecraft.world.level.block.state.BlockState[n];
                net.minecraft.nbt.CompoundTag[] tags = new net.minecraft.nbt.CompoundTag[n];
                int k = 0;
                for (int i = 0; i < rec.size(); i++) {
                    if (rec.dim()[i] != d) continue;
                    pos[k] = rec.pos()[i];
                    before[k] = rec.before()[i];
                    after[k] = rec.after()[i];
                    tags[k] = rec.tag()[i];
                    k++;
                }
                var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(rec.dims().get(d)));
                net.minecraft.server.level.ServerLevel level = server.getLevel(key);
                if (level == null) continue;
                Job job = jobs().submit(ua.zentix.almighty.building.BuildJob.revert(level, areas, id, pos, before, after, tags, n, 2400));
                jobs.add(job.id());
            }
            Journal.Change now = Journal.current();
            journal.undone(id, now == null ? 0 : now.id);
            JsonObject out = new JsonObject();
            out.addProperty("change", id);
            out.add("jobs", jobs);
            out.addProperty("positions", rec.size());
            out.addProperty("note", "positions changed since are left and counted as conflicts in each job; entities and inventories are not undone");
            return out;
        }).thenCompose(o -> {
            JsonArray jobs = o.getAsJsonArray("jobs");
            if (jobs.isEmpty() || waitSeconds == 0) return CompletableFuture.completedFuture((JsonElement) o);
            return onMain(() -> jobs().get(jobs.get(0).getAsLong())).thenCompose(j -> await(j, waitSeconds)).thenApply(d -> {
                o.add("job", d);
                return (JsonElement) o;
            });
        });
    }

    public Journal journal() {
        return journal;
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

    /** MCP and sign-in on the bridge port; null when the bridge is not running. */
    public Mcp mcp() {
        return mcp;
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
        // The change journal travels too: writes this call makes on the server thread belong to its change.
        Journal.Change change = Journal.current();
        Runnable run = () -> {
            if (stopped) {
                out.completeExceptionally(RpcException.unavailable("The server is stopping"));
                return;
            }
            try {
                // Completed inside the caller's scope: continuations that run here (thenCompose on the server thread)
                // still act as this caller, so a job they submit is tagged with the right agent.
                Dispatch.as(caller, () -> Journal.with(change, () -> out.complete(call.run())));
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
