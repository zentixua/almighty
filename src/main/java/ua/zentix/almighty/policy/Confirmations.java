package ua.zentix.almighty.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.Dispatch;
import ua.zentix.almighty.bridge.RpcException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Human confirmation, done by the server (plan 4.8). Two kinds:
 * <ul>
 * <li><b>Requester</b>: a player's request that is irreversible for others (kills, items, effects, inventory
 * clears, bans …) or listed in {@code confirm_first} waits until that same player confirms the exact call;</li>
 * <li><b>Admin</b>: a request above the player's rights, of a kind the owner listed in {@code ask_admin}, waits for
 * an admin who holds the right. Rate-limited per player (1 per 10 minutes) and overall (20 per hour), merged with an
 * identical waiting request, and announced in one quiet digest line, never one ping per request.</li>
 * </ul>
 * The prompt is rendered by the server from the parsed call; the nonce in it is never shown to the agent, which only
 * gets an id to poll ({@code pending}). Thread-safe (bridge threads create, the server thread confirms).
 */
public final class Confirmations {
    public static final long REQUESTER_TTL_MS = 120_000;
    public static final long ADMIN_TTL_MS = 30 * 60_000;
    static final long PER_PLAYER_MS = 10 * 60_000;
    static final int PER_HOUR = 20;
    static final int MAX = 1000;

    public enum Kind { REQUESTER, ADMIN }

    public enum State { WAITING, ALLOWED, DENIED, EXPIRED, DONE, FAILED }

    /** Roots whose effects on others cannot be undone (blocks can; entities, items and players cannot). */
    static final Set<String> IRREVERSIBLE = Set.of("kill", "clear", "give", "effect", "enchant", "experience", "xp", "ban",
            "ban-ip", "kick", "op", "deop", "pardon", "pardon-ip", "whitelist", "damage", "item", "loot", "attribute",
            "gamemode", "tp", "teleport", "spreadplayers", "spawnpoint", "ride", "advancement", "recipe", "team");

    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class Pending {
        public final String id;
        final String nonce;
        public final Kind kind;
        public final Caller caller;
        public final String method;
        public final JsonObject params;
        /** The exact call as shown to the person who decides. */
        public final String text;
        /** Requester kind: the only player who may decide. */
        public final UUID confirmer;
        /** Admin kind: the right the deciding admin must hold. */
        public final Node node;
        public final UUID requester;
        public final String requesterName;
        final long expires;
        volatile State state = State.WAITING;
        volatile JsonElement result;
        volatile String decidedBy;
        int count = 1;
        final CompletableFuture<JsonObject> done = new CompletableFuture<>();

        Pending(Kind kind, Caller caller, String method, JsonObject params, String text, UUID confirmer, Node node,
                UUID requester, String requesterName, long expires) {
            this.id = hex(6);
            this.nonce = hex(12);
            this.kind = kind;
            this.caller = caller;
            this.method = method;
            this.params = params.deepCopy();
            this.text = text;
            this.confirmer = confirmer;
            this.node = node;
            this.requester = requester;
            this.requesterName = requesterName;
            this.expires = expires;
        }

        public String nonce() {
            return nonce;
        }

        public State state() {
            return state;
        }

        public JsonObject describe() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("kind", kind.name().toLowerCase(Locale.ROOT));
            o.addProperty("state", state.name().toLowerCase(Locale.ROOT));
            o.addProperty("call", text);
            o.addProperty("requester", requesterName);
            if (count > 1) o.addProperty("count", count);
            if (decidedBy != null) o.addProperty("by", decidedBy);
            if (result != null) o.add("result", result);
            return o;
        }
    }

    private final LongSupplier clockMillis;
    private final Map<String, Pending> byId = new LinkedHashMap<>();
    private final Map<UUID, Long> lastAsk = new LinkedHashMap<>();
    private final Deque<Long> asks = new ArrayDeque<>();
    /** Set while a confirmed call runs again: the confirmation check lets it through. */
    private static final ThreadLocal<Pending> RUNNING = new ThreadLocal<>();

    public Confirmations(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** The confirmed call running on this thread, if any. */
    public static Pending running() {
        return RUNNING.get();
    }

    /** Kinds of a call: the command names it runs (execute chains included), else the method. */
    public static Set<String> kinds(String method, JsonObject p) {
        Set<String> out = new LinkedHashSet<>();
        if (method.equals("command") || method.equals("function")) {
            for (String c : commands(method, p)) for (String seg : segments(c)) out.add(root(seg));
        } else {
            out.add(method);
        }
        return out;
    }

    static List<String> commands(String method, JsonObject p) {
        List<String> out = new ArrayList<>();
        JsonElement e = p.get(method.equals("command") ? "commands" : "lines");
        if (e == null) return out;
        if (e.isJsonPrimitive()) out.add(e.getAsString());
        else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
        return out;
    }

    private static List<String> segments(String c) {
        List<String> out = new ArrayList<>();
        String s = c.strip().replaceFirst("^[/$]+", "");
        out.add(s);
        if (s.toLowerCase(Locale.ROOT).startsWith("execute")) {
            String[] parts = s.split("\\brun\\s+");
            for (int i = 1; i < parts.length; i++) out.add(parts[i].strip());
        }
        return out;
    }

    private static String root(String seg) {
        String r = seg.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = r.indexOf(':');
        return colon >= 0 ? r.substring(colon + 1) : r;
    }

    /**
     * Whether a call needs its requester's confirmation: an irreversible command aimed at anyone but the requester
     * (a selector other than {@code @s}, or another name), or a kind in {@code confirm_first}.
     */
    public static boolean needsRequester(String method, JsonObject p, String requesterName, Set<String> confirmFirst) {
        for (String k : kinds(method, p)) if (confirmFirst.contains(k)) return true;
        if (!method.equals("command") && !method.equals("function")) return false;
        for (String c : commands(method, p)) {
            boolean irreversible = false;
            for (String seg : segments(c)) if (IRREVERSIBLE.contains(root(seg))) irreversible = true;
            if (!irreversible) continue;
            // Targets anywhere in the command count: "execute as @a run clear" clears everyone's inventory.
            var m = WORD.matcher(c.strip().replaceFirst("^[/$]+", ""));
            m.find();
            while (m.find()) {
                String w = m.group();
                if (w.startsWith("@") && !w.equals("@s") && !w.startsWith("@s[")) return true;
                if (w.matches("[A-Za-z0-9_]{3,16}") && !w.equalsIgnoreCase(requesterName) && looksLikePlayer(w)) return true;
            }
            // No target at all (kill, clear): aims at the source, i.e. the requester themself.
        }
        return false;
    }

    /** Names are told from keywords by the server ({@link #players}); without one any name counts as another player. */
    private static volatile java.util.function.Predicate<String> players = w -> true;

    public static void players(java.util.function.Predicate<String> isPlayerName) {
        players = isPlayerName;
    }

    private static boolean looksLikePlayer(String w) {
        return players.test(w);
    }

    /** The exact call as the decider sees it: commands verbatim, other calls as method and clipped parameters. */
    public static String render(String method, JsonObject p) {
        if (method.equals("command") || method.equals("function")) {
            return String.join(" ; ", commands(method, p).stream().map(c -> c.startsWith("/") ? c : "/" + c).toList());
        }
        JsonObject shown = p.deepCopy();
        shown.remove("for");
        return method + " " + Dispatch.clipped(shown, 300);
    }

    /** Wait for the requester: 202 {@code pending} with the id (never the nonce). */
    public synchronized Pending ask(Kind kind, Caller caller, String method, JsonObject params, UUID confirmer, Node node,
                                    UUID requester, String requesterName) throws RpcException {
        long now = clockMillis.getAsLong();
        expire(now);
        String text = render(method, params);
        if (kind == Kind.ADMIN) {
            for (Pending p : byId.values()) {
                if (p.kind == Kind.ADMIN && p.state == State.WAITING && p.text.equals(text) && p.caller.name().equals(caller.name())) {
                    p.count++;
                    return p;
                }
            }
            Long last = lastAsk.get(requester);
            if (last != null && now - last < PER_PLAYER_MS) {
                throw new RpcException(429, "ask_limit", "This player asked the admins less than 10 minutes ago; try again later");
            }
            while (!asks.isEmpty() && now - asks.peekFirst() >= 3_600_000) asks.pollFirst();
            if (asks.size() >= PER_HOUR) throw new RpcException(429, "ask_limit", "The admins are busy (too many requests this hour)");
            lastAsk.put(requester, now);
            asks.addLast(now);
        }
        if (byId.size() >= MAX) byId.values().removeIf(p -> p.state != State.WAITING);
        Pending p = new Pending(kind, caller, method, params, text, confirmer, node, requester, requesterName,
                now + (kind == Kind.ADMIN ? ADMIN_TTL_MS : REQUESTER_TTL_MS));
        byId.put(p.id, p);
        return p;
    }

    public synchronized Pending get(String id) {
        return byId.get(id);
    }

    public synchronized Pending byNonce(String nonce) {
        for (Pending p : byId.values()) if (p.nonce.equals(nonce)) return p;
        return null;
    }

    /** Waiting admin requests (for the digest and {@code /almighty inbox}). */
    public synchronized List<Pending> waitingForAdmins() {
        expire(clockMillis.getAsLong());
        List<Pending> out = new ArrayList<>();
        for (Pending p : byId.values()) if (p.kind == Kind.ADMIN && p.state == State.WAITING) out.add(p);
        return out;
    }

    /** Mark expired requests; returns them (to tell the requesters and the agent). */
    public synchronized List<Pending> expire(long now) {
        List<Pending> out = new ArrayList<>();
        for (Pending p : byId.values()) {
            if (p.state == State.WAITING && now >= p.expires) {
                p.state = State.EXPIRED;
                p.done.complete(p.describe());
                out.add(p);
            }
        }
        return out;
    }

    /**
     * A decision: {@code byUuid} null for the console. Returns null if accepted, else why not. The first valid answer
     * decides (plan 4.8); the caller then runs the call ({@link #run}).
     */
    public synchronized String decide(Pending p, UUID byUuid, String byName, boolean allow, java.util.function.Predicate<Node> holds) {
        if (p.state != State.WAITING) return "already " + p.state.name().toLowerCase(Locale.ROOT);
        if (clockMillis.getAsLong() >= p.expires) {
            p.state = State.EXPIRED;
            p.done.complete(p.describe());
            return "expired";
        }
        // A requester question is that player's alone (it runs as them); the console may answer admin requests.
        if (p.kind == Kind.REQUESTER && (byUuid == null || !byUuid.equals(p.confirmer))) return "only " + p.requesterName + " can answer this";
        if (byUuid != null) {
            if (p.kind == Kind.ADMIN && !(Identity.verified(byUuid) && holds.test(p.node))) return "you do not hold " + p.node.key();
        }
        p.decidedBy = byName;
        p.state = allow ? State.ALLOWED : State.DENIED;
        if (!allow) p.done.complete(p.describe());
        return null;
    }

    /**
     * Run an allowed call again, past this check; {@code params} may carry the decider's ticket (admin kind).
     * {@code console}: the console decided an admin request, so the call runs in-process, as the console would.
     */
    public static CompletableFuture<JsonElement> run(Pending p, Dispatch dispatch, JsonObject params, boolean console) {
        RUNNING.set(p);
        try {
            Caller as = console ? Caller.INTERNAL : p.caller.withRequester(null);
            return dispatch.invoke(as, p.method, params).whenComplete((v, e) -> {
                p.result = e == null ? v : new JsonPrimitive(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                p.state = e == null ? State.DONE : State.FAILED;
                p.done.complete(p.describe());
            });
        } catch (RpcException e) {
            p.result = new JsonPrimitive(e.getMessage());
            p.state = State.FAILED;
            p.done.complete(p.describe());
            return CompletableFuture.failedFuture(e);
        } finally {
            RUNNING.remove();
        }
    }

    /** 202 for the agent: the call waits for a person; poll {@code pending {id}}. */
    public static RpcException waiting(Pending p, String who) {
        RpcException e = new RpcException(202, "pending", "Waiting for " + who + " to confirm in game: " + p.text
                + " (poll pending {id: \"" + p.id + "\"})");
        e.detail("id", p.id);
        JsonArray kinds = new JsonArray();
        kinds.add(p.kind.name().toLowerCase(Locale.ROOT));
        e.detail("kind", kinds.get(0));
        return e;
    }
}
