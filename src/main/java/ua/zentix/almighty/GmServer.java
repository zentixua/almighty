package ua.zentix.almighty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
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
import ua.zentix.almighty.platform.Mc;
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
import java.util.ArrayList;
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
    private final ua.zentix.almighty.bridge.CallRate callRate = new ua.zentix.almighty.bridge.CallRate(System::currentTimeMillis);
    private final ua.zentix.almighty.policy.Roles roles;
    private final ua.zentix.almighty.world.Claims claims = ua.zentix.almighty.compat.Compat.claims();
    private final Journal journal;
    private final Policies policies;
    private final Gate gate;
    private final Decisions decisions;
    private ua.zentix.almighty.policy.ConfigFiles files;
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
        this.rules = new Rules(server, ua.zentix.almighty.platform.Platform.get().events(), feed, state,
                server.getWorldPath(LevelResource.ROOT).resolve("almighty").resolve("rules.json"));
        this.bots = new Bots(this);
        this.journal = new Journal(server.getWorldPath(LevelResource.ROOT).resolve("almighty").resolve("changes"));
        // GameTest keeps the audit in memory: no files in the test run directory.
        this.audit = new Audit(server instanceof net.minecraft.gametest.framework.GameTestServer ? null
                : server.getServerDirectory().resolve("logs").resolve("almighty"),
                GmConfig.AUDIT_DAYS.get());
        jobs.paused(pause::paused);
        rules.paused(pause::all);
        // One agent paused: all its rules stop; pause of all keeps the owner's (rules.paused above).
        rules.agentPaused(pause::pausedAlone);
        this.inbox = new Inbox(server, feed, tickets, () -> bridge == null ? null : bridge.agents());
        // A kept message follows the agent players are routed to now (lead's N6).
        tickets.handover((t, agent) -> inbox.handsOver(t.agent(), agent, t.player()));
        this.roles = new ua.zentix.almighty.policy.Roles(GmServer.configDir == null ? null : GmServer.configDir.resolve("roles.json"),
                System::currentTimeMillis);
        Nodes.granted(roles::of);
        this.policies = new Policies(GmServer.configDir == null ? null : GmServer.configDir.resolve("policy.toml"));
        audit.zone(() -> policies.current().timezone());
        // Bot names from a thread-safe set the bots keep (the gate runs on bridge threads).
        this.gate = new Gate(policies, tickets, Nodes::holds, bots::isBotName);
        this.decisions = new Decisions(server, new Confirmations(System::currentTimeMillis), tickets, feed);
        gate.confirmations(decisions.confirmations(), decisions::prompt);
        gate.adminCheck((caller, method, params, rights) -> {
            try {
                return onMain(() -> adminAsks(rights, method, params, commandAs(rights))).get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        // Lead's F1: whether a request's commands parse at the requester's level, checked where they would run.
        gate.levelCheck((caller, method, params, rights) -> {
            CompletableFuture<Integer> needed = onMain(() -> {
                ua.zentix.almighty.act.CommandRunner.As as = commandAs(rights);
                ua.zentix.almighty.bridge.Args args = new ua.zentix.almighty.bridge.Args(params, caller);
                int max = 0;
                for (String c : Confirmations.commands(method, params)) max = Math.max(max, ua.zentix.almighty.act.CommandRunner.levelNeeded(server, args, as, c));
                return max;
            });
            try {
                return needed.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                // The requester went offline, or the like: the confirmation path reports it as before.
                return 0;
            } catch (java.util.concurrent.TimeoutException e) {
                return 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        });
        Confirmations.players(name -> Nodes.online(name));
        this.dispatch = new Dispatch(Api.methods(this), List.of(
                (caller, method, params) -> {
                    if (caller.agent() != null) callRate.charge(caller.name(), policies.current().limits().callsPerMinute());
                },
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
        decisions.owners(() -> policies.current().owners());
        rules.guard(ruleGuard(), System::currentTimeMillis);
        this.files = new ua.zentix.almighty.policy.ConfigFiles(GmServer.configDir == null ? server.getServerDirectory().resolve("config")
                .resolve("almighty") : GmServer.configDir, policies, gate, ua.zentix.almighty.policy.ConfigFiles.digest(decisions));
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
            // A reply is its own answer (lead's F2): no "Done for you: say" under it.
            if (method.equals("say")) return;
            // Commands report per command; a receipt only for what actually happened.
            if (ua.zentix.almighty.policy.Decisions.failed(result)) return;
            // What the player reads: the agent's summary beside the server's own facts; the exact call on hover.
            String exact = Confirmations.render(method, params);
            String summary = ua.zentix.almighty.policy.Summary.of(params);
            long recorded = change;
            server.execute(() -> {
                net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(caller.requester());
                // [Undo] only for a change that recorded something (a change with no writes is gone by now).
                Long undoable = recorded > 0 && journal.describe(recorded) != null ? recorded : null;
                if (p == null) return;
                // @s of a request is the requester (it runs as their own source).
                var facts = ua.zentix.almighty.policy.Facts.of(method, params, Mc.name(p.getGameProfile()));
                p.sendSystemMessage(ua.zentix.almighty.policy.Prompts.receipt(summary, facts, exact, undoable, ua.zentix.almighty.feed.GmMessages.language(p)));
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
        // GameTest sets its own policies per test and starts from today's behaviour.
        boolean existing = java.nio.file.Files.exists(configDir.resolve("token")) || server instanceof net.minecraft.gametest.framework.GameTestServer;
        GmServer.configDir = configDir;
        GmServer gm = new GmServer(server);
        current = gm;
        // Decision D1: a fresh install starts as a friends' server; an existing one keeps today's behaviour (private).
        gm.policies.load(existing ? Policy.Preset.PRIVATE : Policy.Preset.FRIENDS);
        Nodes.start(server, gm.policies::current);
        Nodes.refresh(server);
        ua.zentix.almighty.policy.Identity.onlineMode(server.usesAuthentication());
        gm.rules.load();
        // GameTest never runs the bridge (Fabric API's test server calls itself dedicated).
        boolean gametest = server instanceof net.minecraft.gametest.framework.GameTestServer;
        boolean integrated = !server.isDedicatedServer();
        if (gametest) {
            LOG.info("Almighty bridge: off on a GameTest server");
        } else if (integrated && GmConfig.BRIDGE_INTEGRATED.get() && !loopback(GmConfig.BRIDGE_HOST.get())) {
            LOG.error("Almighty bridge: bridge.integrated works only on loopback; bridge.host is {}", GmConfig.BRIDGE_HOST.get());
        } else if ((!integrated || GmConfig.BRIDGE_INTEGRATED.get()) && GmConfig.BRIDGE_ENABLED.get()) {
            try {
                InetSocketAddress address = new InetSocketAddress(GmConfig.BRIDGE_HOST.get(), GmConfig.BRIDGE_PORT.get());
                Agents agents = Agents.load(configDir);
                javax.net.ssl.SSLContext tls = tls(server);
                String refusal = ua.zentix.almighty.bridge.Tls.plainRefusal(GmConfig.BRIDGE_HOST.get(), tls != null,
                        GmConfig.BRIDGE_PLAIN_OFF_LOOPBACK.get());
                if (refusal != null) {
                    LOG.error("Almighty bridge not started: {}", refusal);
                    return gm;
                }
                if (tls == null && !loopback(GmConfig.BRIDGE_HOST.get())) {
                    LOG.warn("Almighty bridge listens on {} without TLS (bridge.allow_plain_http_off_loopback): tokens and calls cross"
                            + " the network in plain text, so keep that address reachable only from this machine", GmConfig.BRIDGE_HOST.get());
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
        closeRuleChanges(false);
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

    /** A revert splits into at most this many pieces of consecutive writes (exact reverse order); more: by region. */
    static final int REVERT_PIECES = 64;

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
        closeRuleChanges(true);
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

    public ua.zentix.almighty.world.Claims claims() {
        return claims;
    }

    public ua.zentix.almighty.policy.Roles roles() {
        return roles;
    }

    public ua.zentix.almighty.policy.Quotas quotas() {
        return quotas;
    }

    public ua.zentix.almighty.policy.ConfigFiles files() {
        return files;
    }

    public Decisions decisions() {
        return decisions;
    }

    /**
     * Declarative rules act for their creator (plan 4.12), asked again at every fire: rules need almighty.rules now;
     * a cancel only where the creator may build (zones, claims; an event without a place: only the owner); say and
     * commands with the creator's own rights, commands at the creator's level and through zones.
     */
    private ua.zentix.almighty.rules.Guard ruleGuard() {
        return new ua.zentix.almighty.rules.Guard() {
            public boolean owner(ua.zentix.almighty.rules.Declarative.Creator c) {
                return c.agent() == null || gate.role(c.agent(), c.player()) == ua.zentix.almighty.policy.Role.OWNER;
            }

            public String lacks(ua.zentix.almighty.rules.Declarative.Creator c, ua.zentix.almighty.policy.Node node) {
                return gate.holds(c.agent(), c.player(), node) ? null : "no " + node.key() + " any more";
            }

            /** The event's place: a block event's position, else its entity's; null for an event without one. */
            private Object[] place(Object e) {
                ua.zentix.almighty.platform.GameEvents.Place at = ua.zentix.almighty.platform.Platform.get().events().place(e);
                return at == null ? null : new Object[]{at.level(), at.pos()};
            }

            public String cancel(ua.zentix.almighty.rules.Declarative.Creator c, Object e) {
                if (owner(c)) return null;
                Object[] at = place(e);
                if (at == null) return "this event has no place in the world; server-wide cancels need the owner";
                if (!gate.holds(c.agent(), c.player(), ua.zentix.almighty.policy.Node.BUILD)) return c.describe() + " may not build";
                net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) at[0];
                net.minecraft.core.BlockPos pos = (net.minecraft.core.BlockPos) at[1];
                int[] p = {pos.getX(), pos.getY(), pos.getZ()};
                String zone = gate.zoneDenies(gate.role(c.agent(), c.player()), ua.zentix.almighty.policy.Node.BUILD,
                        ua.zentix.almighty.world.Dims.id(level), p, p);
                if (zone != null) return zone;
                return claims.refusal(c.player(), level, p[0], p[2], p[0], p[2]);
            }

            public void say(ua.zentix.almighty.rules.Declarative.Creator c, String text, String to, String style) throws RpcException {
                // Only a chat line to the creator themself is a reply; anyone else, or a title, speaks to all.
                boolean all = ua.zentix.almighty.rules.Declarative.speaksToAll(to, style, c);
                if (!owner(c)) {
                    if (ua.zentix.almighty.policy.Forgery.prefix(text)) throw new RpcException(403, "policy", "Rule text may not carry " + ua.zentix.almighty.act.Chat.RESERVED);
                    ua.zentix.almighty.policy.Node node = all ? ua.zentix.almighty.policy.Node.SPEAK_ALL : ua.zentix.almighty.policy.Node.SPEAK_REPLY;
                    if (!gate.holds(c.agent(), c.player(), node)) throw new RpcException(403, "policy", c.describe() + " may not " + node.description());
                }
                JsonObject p = new JsonObject();
                p.addProperty("text", text);
                p.addProperty("style", style);
                if (to != null) p.addProperty("to", to);
                ua.zentix.almighty.act.Chat.say(server, new ua.zentix.almighty.bridge.Args(p));
            }

            public void command(ua.zentix.almighty.rules.Declarative.Creator c, Object e, String command, String rule) throws RpcException {
                Object[] at = place(e);
                if (at == null) {
                    // A player event's commands run where the player stands (checked there); only a cancel ignores it.
                    ua.zentix.almighty.platform.GameEvents.Place actor = ua.zentix.almighty.platform.Platform.get().events().actor(e);
                    if (actor != null) at = new Object[]{actor.level(), actor.pos()};
                }
                net.minecraft.server.level.ServerLevel level = at == null ? server.overworld() : (net.minecraft.server.level.ServerLevel) at[0];
                net.minecraft.world.phys.Vec3 pos = net.minecraft.world.phys.Vec3.atBottomCenterOf(at == null ? Mc.spawnPos(level) : (net.minecraft.core.BlockPos) at[1]);
                ua.zentix.almighty.act.CommandRunner.As as;
                ua.zentix.almighty.policy.Role role = c.agent() == null ? ua.zentix.almighty.policy.Role.OWNER : gate.role(c.agent(), c.player());
                if (role == ua.zentix.almighty.policy.Role.OWNER) {
                    as = c.player() != null && server.getPlayerList().getPlayer(c.player()) == null ? ua.zentix.almighty.act.CommandRunner.As.GM
                            : c.player() == null ? ua.zentix.almighty.act.CommandRunner.As.GM
                            : new ua.zentix.almighty.act.CommandRunner.As(4, server.getPlayerList().getPlayer(c.player()));
                } else if (c.player() != null) {
                    // The creator's own source, at their level under the agent's ceiling.
                    net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(c.player());
                    if (p == null) throw RpcException.conflict(c.name() + " is offline: the rule's command runs as them");
                    int lvl = Math.max(Mc.opLevel(server, p.getGameProfile()), Nodes.role(p.getUUID()).commandLevel());
                    as = new ua.zentix.almighty.act.CommandRunner.As(Math.min(lvl, policies.current().ceiling(c.agent()).commandLevel()), p);
                } else {
                    as = new ua.zentix.almighty.act.CommandRunner.As(role.commandLevel(), null);
                }
                if (role != ua.zentix.almighty.policy.Role.OWNER) {
                    // A rule's fill runs in one go each fire, as a command call's: the same volume bound.
                    ua.zentix.almighty.act.CommandVolume.check(java.util.List.of(command), false);
                    // The filled command, not only the template: plain-name fills could still complete a forged prefix.
                    String forged = ua.zentix.almighty.policy.Forgery.refusal(command);
                    if (forged != null) throw new RpcException(403, "policy", forged);
                    // The filled command, not only the template (rights audit M3): "kill {player}" passes at add time,
                    // but at a fire it names someone; anything irreversible for another than the creator is refused.
                    JsonObject one = new JsonObject();
                    com.google.gson.JsonArray list = new com.google.gson.JsonArray();
                    list.add(command);
                    one.add("commands", list);
                    if (ua.zentix.almighty.policy.Confirmations.needsRequester("command", one, c.name() == null ? "" : c.name(), c.player(),
                            policies.current().safety().confirmFirst())) {
                        throw new RpcException(403, "policy", "This rule's command would act on someone other than its creator, who must"
                                + " confirm that themself: " + command);
                    }
                    net.minecraft.commands.CommandSourceStack source = ua.zentix.almighty.act.CommandRunner.sourceFor(server, level, pos, as);
                    ua.zentix.almighty.policy.CommandZones.check(server.getCommands().getDispatcher(), source, command, (node, dim, lo, hi) -> {
                        String d = gate.zoneDenies(role, node, dim, lo, hi);
                        if (d != null) throw new RpcException(403, "policy", d);
                        // A rule's block edits go only where its creator may build (claims), as its cancels do.
                        if (node == ua.zentix.almighty.policy.Node.BUILD && claims.active()) {
                            String refusal = claims.refusal(c.player(), source.getLevel(), lo[0], lo[2], hi[0], hi[2]);
                            if (refusal != null) throw new RpcException(403, "policy", "Not run: " + refusal);
                        }
                    });
                }
                // Stability M9: the rule's block writes are journaled like a call's, so `changes` lists them and
                // `undo {change}` reverts them. A change collects one rule's writes for up to a minute (a rule may
                // fire every tick; one change per fire would push agents' own changes out of the index).
                final ua.zentix.almighty.act.CommandRunner.As runAs = as;
                Journal.with(ruleChange(c, rule), () -> {
                    ua.zentix.almighty.act.CommandRunner.runAs(server, level, pos, java.util.List.of(command), runAs);
                    return null;
                });
            }
        };
    }

    /**
     * {@code role {player, role, duration? | until?}} (plan 4.10, O17): give a player a role of the ladder (or
     * {@code clear}) in {@code config/almighty/roles.json}, as {@code /almighty role} does. Friction follows origin:
     * in-process (the console) as the owner; for a request, with that player's rights (admins and owners only, and
     * only roles below their own); the agent's own initiative applies at once only when it restricts (owners online
     * are told), and a grant waits for the owner's approval (a holder of {@code almighty.engine}). Server thread.
     */
    public JsonObject role(ua.zentix.almighty.bridge.Args a, com.mojang.authlib.GameProfile looked) throws RpcException {
        String name = a.string("player");
        String roleName = a.string("role").toLowerCase(java.util.Locale.ROOT);
        ua.zentix.almighty.policy.Role role = roleName.equals("clear") ? null : ua.zentix.almighty.policy.Role.parse(roleName);
        if (role == null && !roleName.equals("clear")) throw RpcException.badRequest("role: guest, member, builder, admin, or clear");
        if (a.has("duration") && a.has("until")) throw RpcException.badRequest("duration or until, not both");
        long until = 0;
        if (a.has("duration")) {
            java.time.Duration d = ua.zentix.almighty.policy.Roles.parse(a.string("duration"));
            if (d == null) throw RpcException.badRequest("duration such as 30m, 3h, 2d");
            until = System.currentTimeMillis() + d.toMillis();
        } else if (a.has("until")) {
            until = ua.zentix.almighty.policy.RoleCalls.until(a.string("until"), policies.current().timezone(), java.time.Instant.now());
        }
        net.minecraft.server.level.ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        // The cache lookup (which may ask Mojang) ran on the bridge thread: `looked`.
        com.mojang.authlib.GameProfile profile = online != null ? online.getGameProfile() : looked;
        if (profile == null) throw RpcException.notFound("No player " + name + " (they must have joined this server)");
        java.util.UUID id = Mc.id(profile);
        ua.zentix.almighty.policy.Roles.Grant before = roles.grant(id);
        ua.zentix.almighty.policy.Role granter;
        String by;
        Confirmations.Pending approved = Confirmations.running();
        boolean ownerApproved = approved != null && approved.kind == Confirmations.Kind.ADMIN && approved.method.equals("role")
                && approved.node == ua.zentix.almighty.policy.Node.ENGINE && approved.decider != null;
        // An approval is bound to the player it was shown for, not to whoever holds that name now.
        if (ownerApproved && (!a.raw().has(ROLE_PLAYER) || !a.raw().get(ROLE_PLAYER).getAsString().equals(id.toString()))) {
            throw RpcException.conflict(name + " is not the player this was proposed for: not applied");
        }
        if (a.caller().agent() == null || ownerApproved) {
            granter = ua.zentix.almighty.policy.Role.OWNER;
            by = ownerApproved ? approved.deciderName + " (approved)" : a.caller().name();
        } else if (a.caller().requester() != null) {
            Gate.Rights r = gate.rights(a.caller(), a.raw());
            granter = r.effective();
            by = r.ticket().name() + " via " + a.caller().name();
            if (!granter.atLeast(ua.zentix.almighty.policy.Role.ADMIN)) throw new RpcException(403, "policy", "Only admins and owners give roles");
        } else {
            by = a.caller().name() + " (on its own)";
            if (ua.zentix.almighty.policy.RoleCalls.grants(before, role, until)) {
                // A grant on the agent's own initiative: one owner's approval, as for a granting policy edit.
                String text = "role: the agent proposes " + Mc.name(profile) + " → " + roleName
                        + (until == 0 ? "" : " until " + java.time.Instant.ofEpochMilli(until).atZone(policies.current().timezone())
                        .toLocalDateTime().withSecond(0).withNano(0));
                JsonObject proposal = a.raw().deepCopy();
                proposal.addProperty(ROLE_PLAYER, id.toString());
                Confirmations.Pending pending = decisions.confirmations().ask(Confirmations.Kind.ADMIN, a.caller(), "role", proposal,
                        null, ua.zentix.almighty.policy.Node.ENGINE, null, "the agent", text);
                if (pending.count() == 1) decisions.prompt(pending);
                throw Confirmations.waiting(pending, "the owner (it grants a role)");
            }
            granter = ua.zentix.almighty.policy.Role.OWNER;
        }
        String why = roles.setUntil(id, Mc.name(profile), role, until, granter, by);
        if (why != null) throw new RpcException(403, "policy", why);
        Nodes.refresh(server);
        if (a.caller().agent() != null && a.caller().requester() == null && !ownerApproved) {
            decisions.tellOwners("The agent " + a.caller().name() + " changed " + Mc.name(profile) + "'s role on its own (only restricting): "
                    + roleName + ". Change back: /almighty role " + Mc.name(profile) + " " + (before == null ? "clear" : before.role().id()));
        }
        JsonObject out = new JsonObject();
        out.addProperty("player", Mc.name(profile));
        out.addProperty("uuid", id.toString());
        out.addProperty("role", roleName);
        if (until != 0) out.addProperty("until", java.time.Instant.ofEpochMilli(until).atZone(policies.current().timezone()).toOffsetDateTime().toString());
        JsonObject undo = new JsonObject();
        undo.addProperty("player", Mc.name(profile));
        undo.addProperty("role", before == null ? "clear" : before.role().id());
        if (before != null && before.until() != 0) {
            out.addProperty("previous_until", java.time.Instant.ofEpochMilli(before.until()).atZone(policies.current().timezone()).toOffsetDateTime().toString());
        }
        out.addProperty("previous", before == null ? "none" : before.role().id());
        out.add("undo", undo);
        return out;
    }

    /** The UUID a role proposal was made for (set by the server at ask time, checked at the approved replay). */
    static final String ROLE_PLAYER = "_role_player";

    /** How long one journal change collects a rule's writes before the next fire opens a new one. */
    static final long RULE_CHANGE_TICKS = 1200;
    /** Open journal changes of declarative rules, by creator and rule; closed after {@link #RULE_CHANGE_TICKS}. */
    private final java.util.Map<String, Journal.Change> ruleChanges = new java.util.HashMap<>();
    private final java.util.Map<String, Long> ruleChangeOpened = new java.util.HashMap<>();

    /** The open change for this rule's writes (server thread), opening one if none is open. */
    private Journal.Change ruleChange(ua.zentix.almighty.rules.Declarative.Creator c, String rule) {
        String key = (c.agent() == null ? "" : c.agent()) + "/" + (c.player() == null ? "" : c.player()) + "/" + rule;
        Journal.Change open = ruleChanges.get(key);
        if (open != null) return open;
        Journal.Change change = journal.begin(c.agent() == null ? ua.zentix.almighty.bridge.Caller.INTERNAL.name() : c.agent(),
                "rule " + rule, c.player());
        ruleChanges.put(key, change);
        ruleChangeOpened.put(key, (long) server.getTickCount());
        return change;
    }

    /** Close rule changes older than {@link #RULE_CHANGE_TICKS} (every change when {@code all}), server thread. */
    private void closeRuleChanges(boolean all) {
        if (ruleChanges.isEmpty()) return;
        long now = server.getTickCount();
        for (var it = ruleChanges.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!all && now - ruleChangeOpened.get(entry.getKey()) < RULE_CHANGE_TICKS) continue;
            entry.getValue().release();
            ruleChangeOpened.remove(entry.getKey());
            it.remove();
        }
    }

    /** Close every open rule change now, so it can be reverted at once (GameTest, server stop). */
    public void closeRuleChanges() {
        closeRuleChanges(true);
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
            int level = Math.max(admin == null ? 0 : Mc.opLevel(server, admin.getGameProfile()), Nodes.role(approved.decider).commandLevel());
            return new ua.zentix.almighty.act.CommandRunner.As(Math.min(level, r.ceiling().commandLevel()), asker);
        }
        net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(r.ticket().player());
        if (p == null) throw RpcException.conflict(r.ticket().name() + " is offline: a request runs as its player, who must be online");
        int level = Math.max(Mc.opLevel(server, p.getGameProfile()), Nodes.role(p.getUUID()).commandLevel());
        return new ua.zentix.almighty.act.CommandRunner.As(Math.min(level, r.ceiling().commandLevel()), p);
    }

    /**
     * Zones for commands (plan 4.7), server thread, before anything runs: below owner, each command (or function
     * line) is checked where it runs and, while any zone denies build or command, at every coordinate it names
     * ({@link ua.zentix.almighty.policy.CommandZones}).
     */
    public void commandZone(ua.zentix.almighty.bridge.Args a, ua.zentix.almighty.act.CommandRunner.As as, boolean function) throws RpcException {
        if (a.caller().agent() == null || gate.rights(a.caller(), a.raw()).effective() == ua.zentix.almighty.policy.Role.OWNER) return;
        net.minecraft.commands.CommandSourceStack source = ua.zentix.almighty.act.CommandRunner.sourceFor(server, a, as);
        // Claims too (rights audit M6): a command's block edits below owner go only where the requester may build
        // (unasked: unclaimed only), as a build's do.
        boolean claimed = claims.active();
        java.util.UUID requester = a.caller().requester();
        ua.zentix.almighty.policy.CommandZones.Check check = (node, dim, lo, hi) -> {
            gate.zone(a.caller(), a.raw(), node, dim, lo, hi);
            if (claimed && node == ua.zentix.almighty.policy.Node.BUILD) {
                String refusal = claims.refusal(requester, source.getLevel(), lo[0], lo[2], hi[0], hi[2]);
                if (refusal != null) throw new RpcException(403, "policy", "Not run: " + refusal);
            }
        };
        java.util.Collection<ua.zentix.almighty.policy.Policy.Zone> zones = policies.current().zones().values();
        if (!ua.zentix.almighty.policy.Policy.Zone.guards(zones) && !claimed) {
            // Nothing guards places: only the source position matters (a zone may still deny other nodes there).
            net.minecraft.world.phys.Vec3 v = source.getPosition();
            int[] p = {(int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z)};
            check.zone(ua.zentix.almighty.policy.Node.COMMAND, ua.zentix.almighty.world.Dims.id(source.getLevel()), p, p);
            return;
        }
        var dispatcher = server.getCommands().getDispatcher();
        // Each command of a request starts from where the requester is when it runs, but all are checked from where
        // they are now: after a command that moves them, relative places are refused (send them in a later call).
        boolean moved = false;
        for (String line : a.strings(function ? "lines" : "commands")) {
            String low = line.strip().replaceFirst("^/", "").toLowerCase(java.util.Locale.ROOT);
            if (!function && moved && (low.contains("~") || low.contains("^"))) {
                throw new RpcException(403, "policy", "Not run: a command with relative places after one that moves you, while zones or"
                        + " claims protect places; send it in a separate call");
            }
            if (!function && low.matches("(minecraft:)?(tp|teleport|spreadplayers|ride|spectate)\\b.*")) moved = true;
            String t = line.strip();
            if (function && (t.isEmpty() || t.startsWith("#"))) continue;
            if (function) {
                String why = ua.zentix.almighty.policy.CommandZones.unchecked(t);
                if (why != null) throw new RpcException(403, "policy", "Not run: " + why + ", and zones or claims protect places on this server");
            }
            ua.zentix.almighty.policy.CommandZones.check(dispatcher, source, t, check);
        }
    }

    /**
     * {@code ask_admin} for commands (server thread): a request whose command is listed there and which the player's
     * own source cannot run (vanilla refuses to parse it at their level) waits for an admin holding
     * {@code almighty.approve}, instead of failing. Everything else runs, or fails, as the player.
     */
    public void askAdmin(ua.zentix.almighty.bridge.Caller caller, Gate.Rights r, String method, JsonObject params,
                         ua.zentix.almighty.act.CommandRunner.As as) throws RpcException {
        if (Confirmations.running() != null) return;
        if (!adminAsks(r, method, params, as)) {
            // The gate skipped the requester's question for an admin's; never run it with neither.
            if (params.has(Gate.ADMIN_ONLY)) throw RpcException.conflict("Not run: this request no longer goes to an admin; ask again");
            return;
        }
        Confirmations.Pending p = decisions.confirmations().ask(Confirmations.Kind.ADMIN, caller, method, params, null,
                ua.zentix.almighty.policy.Node.APPROVE, r.ticket().player(), r.ticket().name());
        if (p.count() == 1) decisions.prompt(p);
        throw Confirmations.waiting(p, "an admin");
    }

    /** Whether one of a request's commands is listed in {@code ask_admin} and the player's own source cannot run it (server thread). */
    boolean adminAsks(Gate.Rights r, String method, JsonObject params, ua.zentix.almighty.act.CommandRunner.As as) {
        if (r == null || r.ticket() == null || as.player() == null) return false;
        java.util.Set<String> listed = policies.current().safety().askAdmin();
        if (listed.isEmpty()) return false;
        var dispatcher = server.getCommands().getDispatcher();
        net.minecraft.commands.CommandSourceStack mine = Mc.withLevel(as.player().createCommandSourceStack(), as.level());
        for (String c : Confirmations.commands(method, params)) {
            String text = c.strip().replaceFirst("^[/$]+", "");
            boolean asked = false;
            for (String k : Confirmations.kinds("command", commandParams(text))) if (listed.contains(k)) asked = true;
            if (!asked) continue;
            var parsed = dispatcher.parse(text, mine);
            if (parsed.getReader().canRead() || parsed.getContext().getCommand() == null) return true;
        }
        return false;
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

    /** Changes whose revert is running: a second revert of one is refused until the first ends. */
    private final java.util.Set<Long> reverting = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Revert journal change {@code id} (conflict-aware): one job per dimension it touched. {@code caller} null is the
     * owner (console); otherwise another agent's or requester's change needs {@code undo.any}, and reverting a revert
     * (a redo) needs the owner. Called on the bridge thread (reads the change file), jobs submitted on the server thread.
     */
    public CompletableFuture<JsonElement> revert(long id, ua.zentix.almighty.bridge.Caller caller, JsonObject params, int waitSeconds)
            throws RpcException {
        JsonObject meta = journal.describe(id);
        if (meta == null) throw RpcException.notFound("No change " + id + " (old changes are dropped beyond 4 million positions)");
        // Undone by an owner's /almighty undo, outside any call, the change id is 0 (lead's N8): no "by change 0".
        if (meta.has("undone_by")) {
            long by = meta.get("undone_by").getAsLong();
            throw RpcException.conflict("Change " + id + " was already undone" + (by > 0 ? " by change " + by : ""));
        }
        if (meta.has("open")) {
            throw RpcException.conflict(meta.get("method").getAsString().startsWith("rule ")
                    ? "Change " + id + " still collects its rule's writes (up to a minute after it opened); retry then"
                    : "Change " + id + " is still running (a job of it has not finished): cancel it first");
        }
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
        // Claimed before the file is read (final audit: parallel undos of one large change each read it whole).
        if (!reverting.add(id)) throw RpcException.conflict("Change " + id + " is being undone already");
        final Journal.Records rec;
        final List<Object[]> pieces;
        try {
            Journal.Records r;
            try {
                var blocks = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
                r = journal.read(id, tag -> net.minecraft.nbt.NbtUtils.readBlockState(blocks, tag));
            } catch (java.io.IOException e) {
                throw RpcException.notFound("Change " + id + " cannot be read: " + e.getMessage());
            }
            if (r.size() == 0) throw RpcException.conflict("Change " + id + " changed no blocks");
            rec = r;

            // Pieces of consecutive writes, each within one dimension and 128×128 blocks: a change spread over the map is
            // reverted piece by piece, each leasing only its own chunks; the pieces run one after another from the
            // last writes back, so the whole change is undone in exact reverse write order (a block beside a piece edge,
            // such as redstone wire, may rewrite itself when its neighbour changes).
            List<List<Integer>> groups = new ArrayList<>();
            List<Integer> piece = null;
            int minX = 0, maxX = 0, minZ = 0, maxZ = 0, pieceDim = -1;
            for (int i = 0; i < rec.size(); i++) {
                long pp = rec.pos()[i];
                int x = net.minecraft.core.BlockPos.getX(pp), z = net.minecraft.core.BlockPos.getZ(pp);
                boolean fits = piece != null && rec.dim()[i] == pieceDim && Math.max(maxX, x) - Math.min(minX, x) < 128
                        && Math.max(maxZ, z) - Math.min(minZ, z) < 128;
                if (!fits) {
                    piece = new ArrayList<>();
                    groups.add(piece);
                    pieceDim = rec.dim()[i];
                    minX = maxX = x;
                    minZ = maxZ = z;
                }
                piece.add(i);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            if (groups.size() > REVERT_PIECES) {
                // Writes alternating between far places (thousands of runs): bound the pieces by area instead, one per
                // dimension and 128×128-block region, newest last write first. Exact order then holds within a region.
                Map<String, List<Integer>> byRegion = new LinkedHashMap<>();
                for (int i = 0; i < rec.size(); i++) {
                    long pp = rec.pos()[i];
                    String k = rec.dim()[i] + "/" + (net.minecraft.core.BlockPos.getX(pp) >> 7) + "/" + (net.minecraft.core.BlockPos.getZ(pp) >> 7);
                    byRegion.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
                }
                groups = new ArrayList<>(byRegion.values());
                groups.sort(java.util.Comparator.comparingInt(g -> g.get(g.size() - 1)));
            }
            java.util.Collections.reverse(groups);
            // The pieces' arrays too, here (a bridge thread, or a background one for /almighty undo), not on the server
            // thread: a 2-million-position change is grouped without holding a tick (stability audit M2).
            pieces = new ArrayList<>();
            for (List<Integer> idx : groups) {
                int n = idx.size();
                long[] pos = new long[n];
                net.minecraft.world.level.block.state.BlockState[] before = new net.minecraft.world.level.block.state.BlockState[n], after =
                        new net.minecraft.world.level.block.state.BlockState[n];
                net.minecraft.nbt.CompoundTag[] tags = new net.minecraft.nbt.CompoundTag[n];
                for (int k = 0; k < n; k++) {
                    int i = idx.get(k);
                    pos[k] = rec.pos()[i];
                    before[k] = rec.before()[i];
                    after[k] = rec.after()[i];
                    tags[k] = rec.tag()[i];
                }
                pieces.add(new Object[] {rec.dims().get(rec.dim()[idx.get(0)]), pos, before, after, tags, n});
            }
        } catch (RpcException | RuntimeException | Error e) {
            reverting.remove(id);
            throw e;
        }
        // Checked again now that this revert holds the id: another may have finished while the file was read.
        JsonObject again = journal.describe(id);
        if (again == null || again.has("undone_by")) {
            reverting.remove(id);
            throw RpcException.conflict("Change " + id + " was already undone");
        }
        CompletableFuture<JsonObject> result = onMain(() -> {
            List<Job> started = new ArrayList<>();
            try {
                for (Object[] part : pieces) {
                    String dim = (String) part[0];
                    var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                            net.minecraft.resources.ResourceLocation.parse(dim));
                    net.minecraft.server.level.ServerLevel level = server.getLevel(key);
                    if (level == null) throw RpcException.notFound("Change " + id + " touched dimension " + dim + ", which is not loaded");
                    ua.zentix.almighty.building.BuildJob job = ua.zentix.almighty.building.BuildJob.revert(level, areas, id, (long[]) part[1],
                            (net.minecraft.world.level.block.state.BlockState[]) part[2], (net.minecraft.world.level.block.state.BlockState[]) part[3],
                            (net.minecraft.nbt.CompoundTag[]) part[4], (int) part[5], 2400);
                    if (!started.isEmpty()) job.after(started.get(started.size() - 1));
                    started.add(jobs().submit(job));
                }
            } catch (RpcException | RuntimeException e) {
                // All or nothing: a piece that cannot start (no chunk budget) cancels the pieces already queued.
                for (Job j : started) jobs().cancel(j.id());
                throw e;
            }
            Journal.Change now = Journal.current();
            long by = now == null ? 0 : now.id;
            // Marked undone only when every piece has finished; a failed piece leaves the change revertible again.
            CompletableFuture.allOf(started.stream().map(Job::done).toArray(CompletableFuture[]::new)).thenRun(() -> server.execute(() -> {
                if (started.stream().allMatch(j -> j.state() == Job.State.DONE)) journal.undone(id, by);
                reverting.remove(id);
            }));
            JsonArray jobs = new JsonArray();
            for (Job j : started) jobs.add(j.id());
            JsonObject out = new JsonObject();
            out.addProperty("change", id);
            out.add("jobs", jobs);
            out.addProperty("positions", rec.size());
            out.addProperty("note", "positions changed since are left and counted as conflicts in each job; entities and inventories are not undone");
            return out;
        });
        // A revert that never started its pieces (refused) is no longer running.
        result.whenComplete((o, e) -> {
            if (e != null) reverting.remove(id);
        });
        return result.thenCompose(o -> {
            JsonArray jobs = o.getAsJsonArray("jobs");
            if (jobs.isEmpty() || waitSeconds == 0) return CompletableFuture.completedFuture((JsonElement) o);
            return onMain(() -> jobs().get(jobs.get(0).getAsLong())).thenCompose(j -> await(j, waitSeconds)).thenApply(d -> {
                o.add("job", d);
                return (JsonElement) o;
            });
        });
    }

    /** Whether an undo of this change is running now (its pieces are queued or working). */
    public boolean reverting(long id) {
        return reverting.contains(id);
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
        return onMain(() -> {
            jobs.admit();
            return jobs.submit(factory.run());
        }).thenCompose(job -> await(job, waitSeconds));
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
