package ua.zentix.almighty.rules;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import ua.zentix.almighty.platform.GameEvents;
import ua.zentix.almighty.platform.GameEvents.Priority;
import org.slf4j.Logger;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.bot.Bot;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.feed.Feed;
import ua.zentix.almighty.script.Json;
import ua.zentix.almighty.script.ScriptApi;
import ua.zentix.almighty.script.Scripts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GM rules: reactions to any game-bus event directly on the server, in the same tick. Without a script, event
 * properties enter the feed ({@code emit}); with a script, Groovy receives {@code event}, {@code gm.emit} writes
 * to the feed, and {@code event.canceled = true} cancels cancelable events. A rule disables itself and emits
 * {@code rule.off} on: average time above {@code budget_ms} per tick over the last 20 ticks; one run longer than
 * {@link #runLimitMs} (aborted by the script deadline; the first such abort is only an error);
 * {@value #ERRORS_OFF} consecutive errors; more than {@value #EMITS_PER_SECOND} feed entries in 20 ticks;
 * an event off the server thread. Disabling stops bots controlled by its script ({@code bot.act},
 * {@code bot.send}): programs removed, keys released. Exclude the first run from timing: Groovy links calls then
 * (up to tens of ms). Time is wall-clock: a GC pause during a run counts, so average rather than judging one tick.
 * Everything runs on the server thread except parsing and compilation, which use the bridge thread
 * ({@link #parse}); the rest uses {@code GmServer.onMain}.
 */
public final class Rules {
    private static final Logger LOG = LogUtils.getLogger();
    static final int MAX_RULES = 100;
    public static final int ERRORS_OFF = 10;
    static final int EMITS_PER_SECOND = 200;
    /** Timing and feed-entry counting window, in ticks. */
    static final int WINDOW = 20;
    /** Maximum script text size (one-shot and rule). */
    public static final int MAX_SCRIPT = 64 * 1024;

    private final MinecraftServer server;
    private final GameEvents bus;
    private final Feed feed;
    private final Map<String, Object> state;
    private final Path store;
    private final Map<Long, Rule> rules = new LinkedHashMap<>();
    /** Saved rules that could not load at startup (scripts disabled, missing event): remain in the file until removed. */
    private final List<JsonObject> unloaded = new ArrayList<>();
    private long next = 1;
    private boolean loading;

    /** While true (the owner paused every agent), rules with a script do not run: the red button stops autopilots too. */
    private java.util.function.BooleanSupplier paused = () -> false;

    private Guard guard = Guard.OPEN;
    private java.util.function.LongSupplier clockMillis = System::currentTimeMillis;

    /** Who rules act for, asked at every fire (GmServer: the gate, zones and claims). */
    public void guard(Guard guard, java.util.function.LongSupplier clockMillis) {
        this.guard = guard;
        this.clockMillis = clockMillis;
    }

    public void paused(java.util.function.BooleanSupplier paused) {
        this.paused = paused;
    }

    /** Whether an agent is paused (one agent, or all): its rules do not run meanwhile. */
    private java.util.function.Predicate<String> agentPaused = agent -> false;

    public void agentPaused(java.util.function.Predicate<String> agentPaused) {
        this.agentPaused = agentPaused;
    }

    public Rules(MinecraftServer server, GameEvents bus, Feed feed, Map<String, Object> state, Path store) {
        this.server = server;
        this.bus = bus;
        this.feed = feed;
        this.state = state;
        this.store = store;
    }

    /** Parsed rule: original parameters (for persistence), event and script. */
    public record Spec(JsonObject params, GameEvents.Found event, String code, Scripts.Compiled script, String name, int every,
                       long limit, Priority priority, boolean canceled, double budgetMs, boolean persist,
                       Declarative.Decl decl, Declarative.Creator creator, long expires) {}

    /** TTL of a rule made below owner (plan 4.12): 7 days at most. */
    public static final long TTL_MAX_MS = 7L * 24 * 3_600_000;

    /** A rule the owner made (the console, scripts, an owner's request): no TTL unless asked. */
    public static Spec parse(Args a) throws RpcException {
        return parse(a, Declarative.Creator.OWNER, true, System.currentTimeMillis());
    }

    /** A saved rule: its creator and expiry come from the file (written by this server). */
    static Spec saved(JsonObject params, long now) throws RpcException {
        Args a = new Args(params);
        Declarative.Creator creator = Declarative.Creator.from(params.get("creator"));
        // Parsed as the owner would (no TTL computed, no TTL limit): the expiry is the file's, verbatim.
        Spec s = parse(a, creator, true, now);
        long expires = params.has("expires") ? params.get("expires").getAsLong() : 0;
        s.params().remove("expires");
        if (expires > 0) s.params().addProperty("expires", expires);
        return new Spec(s.params(), s.event(), s.code(), s.script(), s.name(), s.every(), s.limit(), s.priority(), s.canceled(),
                s.budgetMs(), s.persist(), s.decl(), creator, expires);
    }

    /**
     * Parse and compile on the bridge thread without accessing the world. {@code creator} and the expiry are set by
     * the server ({@code owner}: no TTL unless {@code ttl_hours}; below owner at most 7 days), never by parameters.
     */
    public static Spec parse(Args a, Declarative.Creator creator, boolean owner, long now) throws RpcException {
        JsonObject params = new JsonObject();
        for (String key : List.of("event", "script", "name", "every", "limit", "priority", "canceled", "budget_ms", "persist",
                "when", "then", "ttl_hours")) {
            if (a.has(key)) params.add(key, a.raw().get(key));
        }
        Declarative.Decl decl = Declarative.parse(a.raw().get("when"), a.raw().get("then"));
        if (decl != null && a.has("script")) throw RpcException.badRequest("A rule has either a script or when/then, not both");
        double ttlHours = a.number("ttl_hours", owner ? 0 : TTL_MAX_MS / 3_600_000.0, 0, owner ? 1e6 : TTL_MAX_MS / 3_600_000.0);
        if (!owner && ttlHours <= 0) ttlHours = TTL_MAX_MS / 3_600_000.0;
        long expires = ttlHours > 0 ? now + (long) (ttlHours * 3_600_000) : 0;
        if (creator.agent() != null) params.add("creator", creator.json());
        if (expires > 0) params.addProperty("expires", expires);
        GameEvents.Found event = ua.zentix.almighty.platform.Platform.get().events().resolve(a.string("event"));
        String name = a.string("name", null);
        if (name != null && (name.isBlank() || name.length() > 64)) throw RpcException.badRequest("name: 1 to 64 characters");
        int every = a.integer("every", 0, 0, 72_000);
        long limit = a.longValue("limit", 0, 0, 1_000_000_000L);
        Priority priority;
        try {
            priority = Priority.valueOf(a.string("priority", "lowest").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw RpcException.badRequest("priority: highest, high, normal, low or lowest");
        }
        boolean canceled = a.bool("canceled", false);
        // Below owner (plan 4.11) a rule holds the tick no longer than a script may: 20 x budget_ms within 50 ms.
        double budget = a.number("budget_ms", 5, 0.1, 1000);
        if (!owner) budget = Math.min(budget, BUDGET_BELOW_OWNER);
        boolean persist = a.bool("persist", false);
        String code = a.string("script", null);
        Scripts.Compiled script = null;
        if (code != null) {
            if (!GmConfig.SCRIPTS_ENABLED.get()) throw scriptsOff();
            if (code.length() > MAX_SCRIPT) throw RpcException.badRequest("script: at most " + MAX_SCRIPT / 1024 + " KB");
            try {
                script = Scripts.compile("rule", code, runLimitMs(budget));
            } catch (Scripts.CompileError e) {
                throw RpcException.badRequest("Rule script failed to compile: " + e.getMessage());
            }
        }
        return new Spec(params, event, code, script, name, every, limit, priority, canceled, budget, persist, decl, creator, expires);
    }

    /** The most {@code budget_ms} below owner: one run then stops at 50 ms, as a script does. */
    static final double BUDGET_BELOW_OWNER = 2.5;

    /** One-run deadline: the whole window's budget, at least 50 ms and at most 10 s. */
    static long runLimitMs(double budgetMs) {
        return Math.min(10_000, Math.max(50, (long) Math.ceil(budgetMs * WINDOW)));
    }

    public static RpcException scriptsOff() {
        return RpcException.unavailable("Scripts are disabled: scripts.enabled = false in config/almighty-common.toml");
    }

    /** Add a rule (server thread), as the owner. The same name replaces the previous rule. */
    public JsonObject add(Spec spec) throws RpcException {
        return add(spec, c -> true);
    }

    /** Add a rule; a same-named rule (or saved rule that did not load) is replaced only if {@code may} allows its creator. */
    public JsonObject add(Spec spec, java.util.function.Predicate<Declarative.Creator> may) throws RpcException {
        Rule old = spec.name() == null ? null : byName(spec.name());
        if (old != null && !may.test(old.spec.creator())
                || old == null && spec.name() != null && !may.test(Declarative.Creator.OWNER) && unloaded.stream().anyMatch(u -> spec.name().equals(savedName(u)))) {
            if (spec.script() != null) spec.script().close();
            throw new RpcException(403, "policy", "A rule named " + spec.name() + " was made for "
                    + (old != null ? old.spec.creator().describe() : "the owner") + ": choose another name");
        }
        if (old == null && rules.size() >= MAX_RULES) {
            if (spec.script() != null) spec.script().close();
            throw RpcException.conflict("Already " + MAX_RULES + " rules: remove unused ones (rule.remove)");
        }
        Rule rule = new Rule(next, spec);
        try {
            rule.listen();
        } catch (IllegalArgumentException e) {
            if (spec.script() != null) spec.script().close();
            throw RpcException.badRequest("The event bus does not accept " + spec.event().name() + ": " + e.getMessage());
        }
        next++;
        if (old != null) drop(old);
        boolean replacedSaved = spec.name() != null && unloaded.removeIf(u -> spec.name().equals(savedName(u)));
        rules.put(rule.id, rule);
        LOG.info("GM: rule #{} on {}{}", rule.id, spec.event().name(), spec.code() == null ? "" : ", script:\n" + spec.code());
        if (spec.persist() || old != null && old.spec.persist() || replacedSaved) save();
        return rule.describe();
    }

    /** Remove by id or name (server thread), as the owner. */
    public JsonObject remove(Args a) throws RpcException {
        return remove(a, c -> true);
    }

    /** Remove by id or name; {@code may} says whether the caller may remove a rule of that creator (bound to its creator). */
    public JsonObject remove(Args a, java.util.function.Predicate<Declarative.Creator> may) throws RpcException {
        Rule rule;
        String name = a.has("id") ? null : a.string("name", null);
        if (a.has("id")) rule = rules.get(a.longValue("id", 0, 1, Long.MAX_VALUE));
        else if (name != null) rule = byName(name);
        else throw RpcException.badRequest("rule.remove: id or name");
        if (rule == null && name != null && !may.test(Declarative.Creator.OWNER)
                && unloaded.stream().anyMatch(u -> name.equals(savedName(u)))) {
            throw new RpcException(403, "policy", "A saved rule that did not load is removed by the owner or an admin");
        }
        if (rule != null && !may.test(rule.spec.creator())) {
            throw new RpcException(403, "policy", "This rule was made for " + rule.spec.creator().describe() + ": only they, an admin or the owner remove it");
        }
        if (rule == null && name != null && unloaded.removeIf(u -> name.equals(savedName(u)))) {
            save();
            JsonObject out = new JsonObject();
            out.addProperty("removed", name);
            return out;
        }
        if (rule == null) throw RpcException.notFound("No such rule; the list is in rules");
        drop(rule);
        if (rule.spec.persist()) save();
        JsonObject out = new JsonObject();
        out.addProperty("removed", rule.id);
        return out;
    }

    public JsonArray describe() {
        JsonArray out = new JsonArray();
        for (Rule r : rules.values()) out.add(r.describe());
        for (JsonObject u : unloaded) {
            JsonObject d = new JsonObject();
            if (savedName(u) != null) d.addProperty("name", savedName(u));
            d.addProperty("state", "unloaded");
            d.add("error", u.get("error"));
            d.add("spec", u.get("spec"));
            out.add(d);
        }
        return out;
    }

    /** Saved rules for this world (server startup, server thread): failures go to feed and log; other rules still load. */
    public void load() {
        if (!Files.isRegularFile(store)) return;
        JsonArray saved;
        try {
            saved = JsonParser.parseString(Files.readString(store, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("rules");
            if (saved == null) throw new IllegalStateException("no rules list");
        } catch (IOException | RuntimeException e) {
            // The next save will rewrite the file: move the corrupt one aside.
            Path bad = store.resolveSibling(store.getFileName() + ".bad");
            LOG.error("GM rules could not be read from {}; the file was moved to {}", store, bad, e);
            try {
                Files.move(store, bad, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                LOG.error("Could not move {}", store, ex);
            }
            return;
        }
        loading = true;
        try {
            for (JsonElement e : saved) {
                try {
                    Spec spec = saved(e.getAsJsonObject(), clockMillis.getAsLong());
                    add(spec);
                } catch (RpcException | RuntimeException ex) {
                    LOG.warn("Saved rule did not load: {}: {}", e, ex.getMessage());
                    JsonObject d = new JsonObject();
                    d.add("spec", e);
                    d.addProperty("error", ex.getMessage());
                    feed.add("rule.error", d);
                    if (e.isJsonObject()) unloaded.add(d.deepCopy());
                }
            }
        } finally {
            loading = false;
        }
    }

    /** Server shutdown: remove rules from the bus (static, so it outlives singleplayer worlds). */
    public void stop() {
        for (Rule r : List.copyOf(rules.values())) r.unlisten();
        rules.clear();
    }

    private Rule byName(String name) {
        for (Rule r : rules.values()) {
            if (name.equals(r.spec.name())) return r;
        }
        return null;
    }

    private void drop(Rule rule) {
        rule.unlisten();
        rule.driven.clear();
        rules.remove(rule.id);
    }

    private static String savedName(JsonObject unloaded) {
        JsonElement name = unloaded.getAsJsonObject("spec").get("name");
        return name != null && name.isJsonPrimitive() ? name.getAsString() : null;
    }

    private void save() {
        if (loading) return;
        JsonArray list = new JsonArray();
        for (Rule r : rules.values()) {
            if (r.spec.persist()) list.add(r.spec.params());
        }
        for (JsonObject u : unloaded) list.add(u.get("spec"));
        JsonObject root = new JsonObject();
        root.add("rules", list);
        try {
            Files.createDirectories(store.getParent());
            Path tmp = store.resolveSibling(store.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("GM rules could not be saved to {}", store, e);
        }
    }

    /** One rule: subscription, counters, budget. Fields are server-thread only. */
    private final class Rule {
        final long id;
        final Spec spec;
        final ScriptApi api;
        Object listener;
        String off;
        long fired, emitted, errors;
        int errorsInRow;
        long lastRun = Long.MIN_VALUE;
        /** Counting-window starts (tick number; one window before zero until the first) and error feed reporting. */
        long timeTick = -WINDOW, timeNanos, emitTick = -WINDOW, lastErrorTick = -WINDOW;
        int windowEmits;
        boolean running, warm;
        volatile boolean wrongThread;
        /**
         * Bots controlled by the rule script ({@code act}, {@code send}): disabling the rule stops them.
         * Exhausting {@code limit} is planned completion: a started program continues and timed keys release themselves.
         */
        final Set<Bot> driven = Collections.newSetFromMap(new IdentityHashMap<>());

        Rule(long id, Spec spec) {
            this.id = id;
            this.spec = spec;
            this.api = new ScriptApi(server, true, this::emit);
        }

        void listen() {
            listener = bus.subscribe(spec.event(), spec.priority(), spec.canceled(), this::fire);
        }

        void unlisten() {
            if (listener != null) bus.unsubscribe(listener);
            listener = null;
            if (spec.script() != null) spec.script().close();
        }

        void fire(Object event) {
            if (off != null || running) return;
            // Paused: scripts stop, and so do rules made below the owner (they act for an agent or a player).
            if (paused.getAsBoolean() && (spec.script() != null || !ownerMade())) return;
            // One agent paused: its rules stop too (the console's and internal rules have no agent).
            if (spec.creator().agent() != null && agentPaused.test(spec.creator().agent())) return;
            if (!server.isSameThread()) {
                if (!wrongThread) {
                    wrongThread = true;
                    String thread = Thread.currentThread().getName();
                    server.execute(() -> turnOff("the event fired outside the server thread (" + thread + "); rules run only on the server thread"));
                }
                return;
            }
            long tick = server.getTickCount();
            if (spec.every() > 0 && lastRun != Long.MIN_VALUE && tick - lastRun < spec.every()) return;
            if (spec.expires() > 0 && clockMillis.getAsLong() >= spec.expires()) {
                end("expired (made for " + spec.creator().describe() + "; ttl_hours)");
                return;
            }
            if (tick - timeTick >= WINDOW) {
                timeTick = tick;
                timeNanos = 0;
            }
            // Everything from here counts toward the budget, a non-matching condition too.
            long start = System.nanoTime();
            // Bound to its creator (plan 4.12): the creator's rights, asked again at most once a second.
            if (!ownerMade()) {
                String lost = creatorLacks(tick);
                if (lost != null) {
                    turnOff(spec.creator().describe() + " may no longer have this rule: " + lost);
                    return;
                }
            }
            // Players' private messages (and /gm, /almighty) never reach a rule without a script: its output would
            // put their text in the feed, which the feed itself never shows (FeedListeners.privateCommand).
            if (spec.script() == null) {
                String command = bus.command(event);
                if (command != null && ua.zentix.almighty.feed.FeedListeners.privateCommand(command)) return;
            }
            JsonObject fields = null;
            if (spec.decl() != null && !spec.decl().when().isEmpty()) {
                fields = Json.fields(event);
                if (!Declarative.matches(spec.decl().when(), fields)) {
                    if (warm) timeNanos += System.nanoTime() - start;
                    warm = true;
                    if (timeNanos > spec.budgetMs() * 1e6 * WINDOW) {
                        turnOff(String.format(Locale.ROOT, "%.1f ms over %d ticks with a budget of %.1f ms per tick (budget_ms)", timeNanos / 1e6, WINDOW, spec.budgetMs()));
                    }
                    return;
                }
            }
            lastRun = tick;
            running = true;
            try {
                if (spec.decl() != null) {
                    act(event, fields != null ? fields : Json.fields(event), tick);
                } else if (spec.script() == null) {
                    emit(Json.fields(event));
                } else {
                    // Do not retain a departed bot (a new login is a different object) until the rule ends.
                    driven.removeIf(b -> !b.online());
                    Set<Bot> outer = Bot.driving(driven);
                    Scripts.Result r;
                    try {
                        r = Scripts.run(spec.script(), Map.of("event", event, "server", server, "gm", api, "state", state, "rule", id));
                    } finally {
                        Bot.driving(outer);
                        // If disabled mid-run (feed overflow), also stop bots the script began controlling afterward.
                        if (off != null) stopDriven(off);
                    }
                    if (r.ok()) errorsInRow = 0;
                    else error(r.message(), r.line(), r.timedOut(), tick);
                }
            } finally {
                running = false;
                if (warm) timeNanos += System.nanoTime() - start;
                warm = true;
                fired++;
            }
            if (off != null) return;
            if (timeNanos > spec.budgetMs() * 1e6 * WINDOW) {
                turnOff(String.format(Locale.ROOT, "%.1f ms over %d ticks with a budget of %.1f ms per tick (budget_ms)", timeNanos / 1e6, WINDOW, spec.budgetMs()));
            } else if (spec.limit() > 0 && fired >= spec.limit()) {
                end("limit = " + spec.limit() + " reached");
            }
        }

        long checkedTick = -WINDOW, checkedChanges = -1;
        String lacking;
        boolean ownerChecked, owner;

        /** Whether the rule's creator is the owner now (asked once a second with the rights). */
        private boolean ownerMade() {
            if (spec.creator().agent() == null) return true;
            long tick = server.getTickCount();
            if (!ownerChecked || tick - checkedTick >= WINDOW || checkedChanges != ua.zentix.almighty.policy.Policies.CHANGES.get()) creatorLacks(tick);
            return owner;
        }

        /**
         * The creator's rights for this rule, cached for one second or until the policy or a role changes (permission
         * lookups are not free per event; a permission plugin's change is seen within a second).
         */
        private String creatorLacks(long tick) {
            long changes = ua.zentix.almighty.policy.Policies.CHANGES.get();
            if (ownerChecked && tick - checkedTick < WINDOW && checkedChanges == changes) return lacking;
            checkedTick = tick;
            checkedChanges = changes;
            ownerChecked = true;
            owner = guard.owner(spec.creator());
            if (owner) return lacking = null;
            lacking = guard.lacks(spec.creator(), spec.script() != null ? ua.zentix.almighty.policy.Node.ENGINE : ua.zentix.almighty.policy.Node.RULES);
            if (lacking == null && spec.decl() != null && spec.decl().commands()) lacking = guard.lacks(spec.creator(), ua.zentix.almighty.policy.Node.COMMAND);
            return lacking;
        }

        /** A planned end (limit, expiry): removed from the list and the file. */
        private void end(String reason) {
            drop(this);
            JsonObject d = who();
            d.addProperty("reason", reason);
            feed.add("rule.off", d);
            if (spec.persist()) save();
        }

        /** Declarative actions, in order; a refused or failed action is a rule error (10 in a row turn it off). */
        private void act(Object event, JsonObject fields, long tick) {
            Declarative.Creator c = spec.creator();
            try {
                for (Declarative.Action a : spec.decl().then()) {
                    switch (a.kind()) {
                        case "cancel" -> {
                            if (!bus.cancellable(event)) throw new IllegalStateException(spec.event().name() + " cannot be canceled");
                            String why = guard.cancel(c, event);
                            if (why != null) throw new IllegalStateException("not canceled: " + why);
                            bus.cancel(event);
                        }
                        case "emit" -> emit(fields);
                        case "say" -> guard.say(c, Declarative.fill(a.text(), fields, false), a.to() == null ? null : Declarative.fill(a.to(), fields, true), a.style());
                        case "command" -> guard.command(c, event, Declarative.fill(a.text(), fields, true), spec.name() != null ? spec.name() : "#" + id);
                        default -> throw new IllegalStateException("unknown action " + a.kind());
                    }
                    if (off != null) return;
                }
                errorsInRow = 0;
            } catch (RpcException | Declarative.Unsafe | RuntimeException e) {
                error(e.getMessage(), 0, false, tick);
            }
        }

        private void error(String message, int line, boolean timedOut, long tick) {
            errors++;
            errorsInRow++;
            if (errorsInRow >= ERRORS_OFF) {
                turnOff(ERRORS_OFF + " errors in a row, last: " + message + (line > 0 ? " (line " + line + ")" : ""));
                return;
            }
            // The first run may spend its deadline linking Groovy calls: its abort is an error; the second disables the rule.
            if (timedOut && warm) {
                turnOff(message + " (budget_ms)");
                return;
            }
            // Report an error to the feed at most once per second: frequent events can produce hundreds.
            if (tick - lastErrorTick < WINDOW) return;
            lastErrorTick = tick;
            JsonObject d = who();
            d.addProperty("error", message);
            if (line > 0) d.addProperty("line", line);
            d.addProperty("errors", errors);
            feed.add("rule.error", d);
        }

        void emit(JsonElement data) {
            long tick = server.getTickCount();
            if (tick - emitTick >= WINDOW) {
                emitTick = tick;
                windowEmits = 0;
            }
            if (++windowEmits > EMITS_PER_SECOND) {
                if (off == null) turnOff("more than " + EMITS_PER_SECOND + " feed entries in " + WINDOW + " ticks: narrow the condition or set every");
                return;
            }
            emitted++;
            JsonObject d = who();
            d.add("data", data);
            feed.add("emit", d);
        }

        /** Disable: unsubscribe from the bus, keep in the list with a reason (visible in rules); a persisted rule loads on restart. */
        void turnOff(String reason) {
            if (off != null) return;
            off = reason;
            if (listener != null) bus.unsubscribe(listener);
            listener = null;
            LOG.warn("GM rule #{} turned off: {}", id, reason);
            JsonObject d = who();
            d.addProperty("reason", reason);
            JsonArray stopped = stopDriven(reason);
            if (!stopped.isEmpty()) d.add("bots_stopped", stopped);
            feed.add("rule.off", d);
        }

        /** Dead man's switch: autopilot bots release the last input (otherwise nose-down control sends an aircraft into the sea). */
        private JsonArray stopDriven(String reason) {
            JsonArray stopped = new JsonArray();
            for (Bot bot : driven) {
                if (!bot.online()) continue;
                bot.stop("rule " + (spec.name() != null ? "\"" + spec.name() + "\"" : "#" + id) + " turned off: " + reason);
                stopped.add(bot.name());
            }
            driven.clear();
            return stopped;
        }

        JsonObject who() {
            JsonObject d = new JsonObject();
            d.addProperty("rule", id);
            if (spec.name() != null) d.addProperty("name", spec.name());
            return d;
        }

        JsonObject describe() {
            JsonObject out = who();
            out.addProperty("event", spec.event().name());
            out.addProperty("mod", spec.event().mod());
            out.addProperty("state", off == null ? "on" : "off");
            if (off != null) out.addProperty("reason", off);
            out.addProperty("script", spec.code() != null);
            if (spec.decl() != null) {
                out.add("when", spec.params().get("when"));
                out.add("then", spec.params().get("then"));
            }
            if (spec.creator().agent() != null) out.addProperty("for", spec.creator().describe());
            if (spec.expires() > 0) out.addProperty("expires", java.time.Instant.ofEpochMilli(spec.expires()).toString());
            if (spec.every() > 0) out.addProperty("every", spec.every());
            if (spec.limit() > 0) out.addProperty("limit", spec.limit());
            out.addProperty("priority", spec.priority().name().toLowerCase(Locale.ROOT));
            if (spec.canceled()) out.addProperty("canceled", true);
            out.addProperty("budget_ms", spec.budgetMs());
            if (spec.persist()) out.addProperty("persist", true);
            out.addProperty("fired", fired);
            out.addProperty("emitted", emitted);
            out.addProperty("errors", errors);
            return out;
        }
    }
}
