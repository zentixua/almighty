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

    /**
     * Commands a player's request may run without confirming (plan 4.2): their effects can be undone (blocks, once
     * the change journal records them) or touch nobody. Everything else — kills, items, effects, bans, data,
     * summons, functions, schedules, any mod command — waits for the requester, unless it is {@link #SELF_ONLY} and
     * aimed at the requester alone. An allowlist, so commands nobody thought of are confirmed, not let through.
     */
    static final Set<String> SAFE = Set.of("time", "weather", "say", "me", "msg", "tell", "w", "teammsg", "tm", "list",
            "locate", "playsound", "stopsound", "particle", "help", "trigger", "seed", "setblock", "fill", "clone", "place",
            "fillbiome");
    /** Commands that need no confirmation when they act only on the requester ({@code @s}, their name or UUID). */
    static final Set<String> SELF_ONLY = Set.of("tp", "teleport", "give", "effect", "clear", "gamemode", "xp", "experience",
            "enchant", "kill", "spawnpoint", "title", "ride", "attribute", "item", "advancement", "recipe");
    static final int PER_REQUESTER = 3;
    static final int PER_AGENT = 20;

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
        /** The parameters as decided on (without the ticket), for merging identical admin requests. */
        final JsonObject shown;
        /** Admin kind: who allowed it; the call then runs with their rights, as the requester (one call). */
        public volatile UUID decider;
        public volatile String deciderName;
        final long expires;
        volatile State state = State.WAITING;
        volatile JsonElement result;
        volatile String decidedBy;
        volatile int count = 1;
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
            this.shown = params.deepCopy();
            this.shown.remove("for");
            this.expires = expires;
        }

        /** How many identical requests this one stands for (admin kind). */
        public int count() {
            return count;
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

    /** The commands a call runs; function lines with their macro arguments filled in (what really runs). */
    public static List<String> commands(String method, JsonObject p) {
        List<String> out = new ArrayList<>();
        JsonElement e = p.get(method.equals("command") ? "commands" : "lines");
        if (e == null) return out;
        JsonObject args = method.equals("function") && p.get("args") instanceof JsonObject o ? o : new JsonObject();
        List<String> raw = new ArrayList<>();
        if (e.isJsonPrimitive()) raw.add(e.getAsString());
        else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) raw.add(x.getAsString());
        if (method.equals("function")) raw = DenyList.logicalLines(raw);
        for (String c : raw) {
            // A macro line ($…) runs with its arguments filled in and without its $.
            out.add(method.equals("function") && c.strip().startsWith("$") ? DenyList.expand(c.strip().substring(1), args) : c);
        }
        return out;
    }

    private static List<String> segments(String c) {
        return DenyList.segments(c);
    }

    private static String root(String seg) {
        String r = seg.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = r.indexOf(':');
        return colon >= 0 ? r.substring(colon + 1) : r;
    }

    /**
     * Whether a call needs its requester's confirmation: any command not in {@link #SAFE}, unless it is
     * {@link #SELF_ONLY} and every target is the requester; or a kind in {@code confirm_first}.
     */
    public static boolean needsRequester(String method, JsonObject p, String requesterName, Set<String> confirmFirst) {
        return needsRequester(method, p, requesterName, null, confirmFirst);
    }

    public static boolean needsRequester(String method, JsonObject p, String requesterName, UUID requester, Set<String> confirmFirst) {
        for (String k : kinds(method, p)) if (confirmFirst.contains(k)) return true;
        if (!method.equals("command") && !method.equals("function")) return false;
        for (String c : commands(method, p)) {
            boolean selfOnly = false;
            for (String seg : segments(c)) {
                String root = root(seg);
                if (root.equals("execute") || root.equals("return")) continue;
                if (SELF_ONLY.contains(root)) selfOnly = true;
                else if (!SAFE.contains(root)) return true;
            }
            // execute as/at <targets> runs even a safe command once per target, where they stand (lava under each of
            // @a): with execute, the targets must be the requester too (review of the rights fixes).
            boolean executes = segments(c).stream().anyMatch(seg -> root(seg).equals("execute"));
            if ((selfOnly || executes) && !onlySelf(c, requesterName, requester)) return true;
        }
        return false;
    }

    /**
     * Scenario P10: a call that is one teleport between the requester and one other player ({@code tp Bob},
     * {@code tp @s Bob}, {@code tp Me Bob}, {@code tp Bob Me}): that other player's name, who confirms it (being
     * visited or moved is theirs to allow); null for anything else (the requester confirms as before).
     */
    public static String teleportPartner(String method, JsonObject p, String requesterName) {
        if (!method.equals("command") && !method.equals("function")) return null;
        List<String> commands = commands(method, p);
        if (commands.size() != 1) return null;
        List<String> segs = segments(commands.get(0));
        if (segs.size() != 1) return null;
        String[] t = segs.get(0).strip().split("\\s+");
        String root = root(segs.get(0));
        if (!root.equals("tp") && !root.equals("teleport")) return null;
        java.util.function.Predicate<String> self = w -> w.equals("@s") || w.equalsIgnoreCase(requesterName);
        java.util.function.Predicate<String> name = w -> w.matches("[.*]?[A-Za-z0-9_]{1,16}") && !self.test(w) && looksLikePlayer(w);
        if (t.length == 2 && name.test(t[1])) return t[1];
        if (t.length == 3 && self.test(t[1]) && name.test(t[2])) return t[2];
        if (t.length == 3 && name.test(t[1]) && self.test(t[2])) return t[1];
        return null;
    }

    /** Every target in the command is the requester: no selector but {@code @s}, no other name or UUID, no {@code execute on}. */
    private static boolean onlySelf(String c, String requesterName, UUID requester) {
        String text = c.strip().replaceFirst("^[/$]+", "");
        // A quoted name targets a player just as well ("kill \"Friend\""): any quoted word that is not the requester.
        var quoted = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)'").matcher(text);
        while (quoted.find()) {
            String q = quoted.group(1) != null ? quoted.group(1) : quoted.group(2);
            if (q.matches("[.*]?[A-Za-z0-9_]{1,16}") && !q.equalsIgnoreCase(requesterName) && looksLikePlayer(q)) return false;
        }
        var m = WORD.matcher(text);
        String prev = "";
        while (m.find()) {
            // Quotes taken off: the game reads a quoted name or UUID as the same target.
            String w = m.group().replaceAll("^[\"']+|[\"',]+$", "");
            if (w.startsWith("@") && !w.equals("@s") && !w.startsWith("@s[")) return false;
            if (prev.equalsIgnoreCase("on")) return false;
            // UUIDs as the game reads them: groups may drop leading zeros (0-0-0-0-1).
            if (w.matches("[0-9a-fA-F]+(-[0-9a-fA-F]+){4}")) {
                UUID id;
                try {
                    id = UUID.fromString(w);
                } catch (IllegalArgumentException e) {
                    return false;
                }
                if (!id.equals(requester)) return false;
            }
            // Names: offline and Floodgate names can be 1 or 2 characters, Floodgate ones start with a dot or star
            // (.Steve is another player than Steve: compared whole).
            String name = w.startsWith(".") || w.startsWith("*") ? w.substring(1) : w;
            if (name.matches("[A-Za-z0-9_]{1,16}") && !w.equalsIgnoreCase(requesterName) && looksLikePlayer(w)) return false;
            prev = w;
        }
        return true;
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
        String text;
        if (method.equals("command") || method.equals("function")) {
            text = String.join(" ; ", commands(method, p).stream().map(c -> c.startsWith("/") ? c : "/" + c).toList());
        } else {
            JsonObject shown = p.deepCopy();
            shown.remove("for");
            text = method + " " + Dispatch.clipped(shown, MAX_SHOWN + 1);
        }
        // Shown verbatim, never styled by the text itself.
        return text.replace("§", "").replace('\n', ' ').replace('\r', ' ');
    }

    /** A call too long to show in full is refused rather than shown clipped (the decider must see all of it). */
    static final int MAX_SHOWN = 1000;

    /** Wait for the requester: 202 {@code pending} with the id (never the nonce). */
    public synchronized Pending ask(Kind kind, Caller caller, String method, JsonObject params, UUID confirmer, Node node,
                                    UUID requester, String requesterName) throws RpcException {
        return ask(kind, caller, method, params, confirmer, node, requester, requesterName, null);
    }

    /**
     * {@code display}: what the decider sees instead of the rendered call, for a call too large to read in chat
     * (a whole policy file: its summary); the exact parameters still run, and identical asks still merge.
     */
    public synchronized Pending ask(Kind kind, Caller caller, String method, JsonObject params, UUID confirmer, Node node,
                                    UUID requester, String requesterName, String display) throws RpcException {
        long now = clockMillis.getAsLong();
        expire(now);
        String text = display != null ? display : render(method, params);
        if (text.length() > MAX_SHOWN) throw RpcException.badRequest("This call is too long to show a person for confirmation; split it");
        JsonObject shown = params.deepCopy();
        shown.remove("for");
        if (kind == Kind.REQUESTER) {
            int mine = 0, agent = 0;
            for (Pending p : byId.values()) {
                if (p.state != State.WAITING) continue;
                if (p.kind == Kind.REQUESTER && java.util.Objects.equals(p.requester, requester)) mine++;
                if (p.caller.name().equals(caller.name())) agent++;
            }
            if (mine >= PER_REQUESTER) throw new RpcException(429, "ask_limit", requesterName + " already has " + mine + " questions waiting");
            if (agent >= PER_AGENT) throw new RpcException(429, "ask_limit", "Too many questions waiting for players");
        }
        if (kind == Kind.ADMIN) {
            for (Pending p : byId.values()) {
                if (p.kind == Kind.ADMIN && p.state == State.WAITING && p.shown.equals(shown) && p.method.equals(method)
                        && p.caller.name().equals(caller.name())) {
                    p.count++;
                    return p;
                }
            }
            // The agent's own ask (no requester) is limited per agent, as a player's per player.
            UUID asker = requester != null ? requester
                    : UUID.nameUUIDFromBytes(("agent:" + caller.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Long last = lastAsk.get(asker);
            if (last != null && now - last < PER_PLAYER_MS) {
                throw new RpcException(429, "ask_limit", (requester != null ? "This player" : "This agent")
                        + " asked the admins less than 10 minutes ago; try again later");
            }
            while (!asks.isEmpty() && now - asks.peekFirst() >= 3_600_000) asks.pollFirst();
            if (asks.size() >= PER_HOUR) throw new RpcException(429, "ask_limit", "The admins are busy (too many requests this hour)");
            lastAsk.put(asker, now);
            asks.addLast(now);
        }
        if (byId.size() >= MAX) byId.values().removeIf(p -> p.state != State.WAITING);
        if (byId.size() >= MAX) throw new RpcException(429, "ask_limit", "Too many questions waiting");
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
        if (p.kind == Kind.REQUESTER && (byUuid == null || !byUuid.equals(p.confirmer))) return p.confirmer != null && !p.confirmer.equals(p.requester) ? "only the player it concerns can answer this" : "only " + p.requesterName + " can answer this";
        if (byUuid != null) {
            if (p.kind == Kind.ADMIN && !(Identity.verified(byUuid) && holds.test(p.node))) return "you do not hold " + p.node.key();
        }
        p.decidedBy = byName;
        if (p.kind == Kind.ADMIN) {
            p.decider = byUuid;
            p.deciderName = byName;
        }
        p.state = allow ? State.ALLOWED : State.DENIED;
        if (!allow) p.done.complete(p.describe());
        return null;
    }

    /**
     * Run an allowed call again, past this check; {@code params} may carry the decider's ticket (admin kind).
     * {@code console}: the console decided an admin request, so the call runs in-process, as the console would.
     */
    public static CompletableFuture<JsonElement> run(Pending p, Dispatch dispatch, JsonObject params, boolean console) {
        Pending outer = RUNNING.get();
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
            if (outer == null) RUNNING.remove();
            else RUNNING.set(outer);
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
