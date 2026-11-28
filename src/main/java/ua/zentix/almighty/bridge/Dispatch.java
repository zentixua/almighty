package ua.zentix.almighty.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The one choke point between transports and methods: {@code /rpc}, the MCP endpoint and in-process callers all
 * call {@link #invoke}. Checks that apply to every call (the deny list, later the gate and audit) run here in order,
 * so no transport can bypass them.
 *
 * <p>Idempotency: a call may carry a key; a repeat of the same key by the same agent within
 * {@value #KEY_TTL_MS} ms gets the first call's result (waiting for it if it is still running) instead of running
 * again — a retry after a 504 does not run a command twice. The same key with a different call is refused (409).
 * Keys matter only for calls with effects: read-only methods simply run again (their results, images included, are
 * never kept), and a call that failed forgets its key, so a retry runs it afresh.
 */
public final class Dispatch {
    /** Told about every call once it ends (audit): result or error, and how long it took. */
    @FunctionalInterface
    public interface Listener {
        /**
         * {@code paramsText}: the parameters as JSON, clipped to {@value #MAX_PARAMS_TEXT} characters (made on the
         * calling thread); {@code change}: the change journal's id for this call's writes (0: none).
         */
        void ended(Caller caller, String method, JsonObject params, String paramsText, JsonElement result, Throwable error,
                   long nanos, long change);
    }

    /** Wraps every call with effects (the change journal): open before it runs, run inside, close when it ends. */
    public interface Around {
        Object begin(Caller caller, String method, JsonObject params);

        <T, E extends Exception> T within(Object token, ThrowingSupplier<T, E> work) throws E;

        void end(Object token);

        long id(Object token);
    }

    private volatile Around around;

    public void around(Around a) {
        this.around = a;
    }

    /** The caller whose call is running on this thread: set while a method parses here, carried to the server thread
     * by {@code GmServer.onMain}, so jobs, bots and the change journal know which agent acts. */
    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    public static Caller current() {
        Caller c = CURRENT.get();
        return c == null ? Caller.INTERNAL : c;
    }

    /** Run {@code work} as {@code caller}, restoring the previous caller after. */
    public static <T, E extends Exception> T as(Caller caller, ThrowingSupplier<T, E> work) throws E {
        Caller previous = CURRENT.get();
        CURRENT.set(caller);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }

    /** Who asked, from the call's ticket ({@code for}); null if none or not valid (the gate then refuses it). */
    @FunctionalInterface
    public interface Requester {
        java.util.UUID of(Caller caller, JsonObject params);
    }

    private volatile Requester requester = (c, p) -> null;

    public void requester(Requester r) {
        this.requester = r;
    }

    /** A check on every call before it runs; throws to refuse. */
    @FunctionalInterface
    public interface Check {
        void check(Caller caller, String method, JsonObject params) throws RpcException;
    }

    static final long KEY_TTL_MS = 10 * 60_000;
    public static final int MAX_PARAMS_TEXT = 4096;
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    /** JSON text of {@code e}, stopping after {@code max} characters: a 16 MB build costs no more than 4 KB here. */
    public static String clipped(JsonElement e, int max) {
        StringBuilder out = new StringBuilder();
        Appendable bounded = new Appendable() {
            private Appendable add(CharSequence s) {
                if (out.length() + s.length() > max) {
                    out.append(s, 0, Math.max(0, max - out.length()));
                    throw new Full();
                }
                out.append(s);
                return this;
            }

            @Override
            public Appendable append(CharSequence s) {
                return add(s == null ? "null" : s);
            }

            @Override
            public Appendable append(CharSequence s, int start, int end) {
                return add((s == null ? "null" : s).subSequence(start, end));
            }

            @Override
            public Appendable append(char c) {
                return add(String.valueOf(c));
            }
        };
        try {
            GSON.toJson(e, bounded);
            return out.toString();
        } catch (Full | com.google.gson.JsonIOException full) {
            return out + "…";
        }
    }

    private static final class Full extends RuntimeException {
        Full() {
            super(null, null, false, false);
        }
    }
    static final int MAX_KEYS = 1024;
    static final int MAX_KEYS_PER_AGENT = 256;

    private static String digest(String text) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    static final int MAX_KEY_LENGTH = 128;

    private final Map<String, Method> methods;
    private final List<Check> checks;
    private final java.util.function.BiPredicate<String, JsonObject> readOnly;
    private final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final LongSupplier clockMillis;
    private final Map<String, Remembered> keys = new LinkedHashMap<>();

    private record Remembered(String call, CompletableFuture<JsonElement> result, long expires) {}

    public Dispatch(Map<String, Method> methods) {
        this(methods, List.of(), (m, p) -> false);
    }

    /** {@code readOnly}: whether a call (method and parameters) has no effects; idempotency keys are ignored for it. */
    public Dispatch(Map<String, Method> methods, List<Check> checks, java.util.function.BiPredicate<String, JsonObject> readOnly) {
        this(methods, checks, readOnly, System::currentTimeMillis);
    }

    Dispatch(Map<String, Method> methods, List<Check> checks, java.util.function.BiPredicate<String, JsonObject> readOnly,
             LongSupplier clockMillis) {
        this.methods = Map.copyOf(methods);
        this.checks = List.copyOf(checks);
        this.readOnly = readOnly;
        this.clockMillis = clockMillis;
    }

    /** Call a method as {@code caller}; parse errors and refusals throw at once, server-thread errors complete the future. */
    public CompletableFuture<JsonElement> invoke(Caller caller, String name, JsonObject params) throws RpcException {
        return invoke(caller, name, params, null);
    }

    public void listen(Listener l) {
        listeners.add(l);
    }

    public boolean readOnly(String method, JsonObject params) {
        return readOnly.test(method, params);
    }

    /** As {@link #invoke(Caller, String, JsonObject)}, with an idempotency key (null: none). */
    public CompletableFuture<JsonElement> invoke(Caller agentCaller, String name, JsonObject params, String key) throws RpcException {
        long start = System.nanoTime();
        JsonObject p = params == null ? new JsonObject() : params;
        Caller caller = agentCaller.agent() != null && agentCaller.requester() == null && p.has("for")
                ? agentCaller.withRequester(requester.of(agentCaller, p)) : agentCaller;
        // Made here (a bridge thread): the call may end on the server thread, which should not serialize a big plan.
        String text = listeners.isEmpty() ? "" : clipped(p, MAX_PARAMS_TEXT);
        Around a = around;
        Object token = a != null && !readOnly.test(name, p) ? a.begin(caller, name, p) : null;
        long change = token == null ? 0 : a.id(token);
        CompletableFuture<JsonElement> out;
        try {
            out = as(caller, () -> token == null ? run(caller, name, params, key) : a.within(token, () -> run(caller, name, params, key)));
        } catch (RpcException | RuntimeException e) {
            if (token != null) a.end(token);
            tell(caller, name, p, text, null, e, start, change);
            throw e;
        }
        out.whenComplete((v, e) -> {
            if (token != null) a.end(token);
            tell(caller, name, p, text, v, e, start, change);
        });
        return out;
    }

    private void tell(Caller caller, String name, JsonObject p, String text, JsonElement result, Throwable error, long start, long change) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException c && c.getCause() != null ? c.getCause() : error;
        for (Listener l : listeners) {
            try {
                l.ended(caller, name, p, text, result, cause, System.nanoTime() - start, change);
            } catch (RuntimeException ignored) {
                // A listener never breaks a call.
            }
        }
    }

    private CompletableFuture<JsonElement> run(Caller caller, String name, JsonObject params, String key) throws RpcException {
        Method method = methods.get(name);
        if (method == null) {
            throw RpcException.notFound("No method " + name + "; methods: " + String.join(", ", new TreeSet<>(methods.keySet())));
        }
        JsonObject p = params == null ? new JsonObject() : params;
        for (Check c : checks) c.check(caller, name, p);
        if (key != null && (key.isEmpty() || key.length() > MAX_KEY_LENGTH)) {
            throw RpcException.badRequest("key: 1 to " + MAX_KEY_LENGTH + " characters");
        }
        if (key == null || readOnly.test(name, p)) return method.call(new Args(p, caller));
        String slot = caller.name() + "\u0000" + key;
        // A digest, not the call itself (stability audit M1): 1024 remembered 16 MB builds would fill the heap.
        // The agent's summary is words about the call, not the call: a retry may word it differently.
        JsonObject same = p;
        if (p.has("summary")) {
            same = p.deepCopy();
            same.remove("summary");
        }
        String call = digest(name + "\u0000" + same);
        CompletableFuture<JsonElement> mine = new CompletableFuture<>();
        synchronized (keys) {
            long now = clockMillis.getAsLong();
            keys.values().removeIf(r -> r.expires < now);
            Remembered seen = keys.get(slot);
            if (seen != null) {
                if (!seen.call.equals(call)) throw RpcException.conflict("key " + key + " was already used for a different call");
                return seen.result.thenApply(JsonElement::deepCopy);
            }
            while (keys.size() >= MAX_KEYS) keys.remove(keys.keySet().iterator().next());
            // Each agent keeps at most its share: one agent's keys never push out another's retry protection.
            String prefix = caller.name() + "\u0000";
            int own = 0;
            for (String k : keys.keySet()) if (k.startsWith(prefix)) own++;
            if (own >= MAX_KEYS_PER_AGENT) {
                for (var it = keys.keySet().iterator(); it.hasNext(); ) {
                    if (it.next().startsWith(prefix)) {
                        it.remove();
                        break;
                    }
                }
            }
            keys.put(slot, new Remembered(call, mine, now + KEY_TTL_MS));
        }
        try {
            method.call(new Args(p, caller)).whenComplete((v, e) -> {
                if (e != null) {
                    synchronized (keys) {
                        keys.remove(slot);
                    }
                    mine.completeExceptionally(e);
                } else {
                    mine.complete(v);
                }
            });
        } catch (RpcException | RuntimeException e) {
            // Refused before it ran: forget the key, so a corrected retry with it may run.
            synchronized (keys) {
                keys.remove(slot);
            }
            throw e;
        }
        // The first caller gets the result itself; only repeats get copies (above).
        return mine;
    }

    public Set<String> names() {
        return methods.keySet();
    }
}
