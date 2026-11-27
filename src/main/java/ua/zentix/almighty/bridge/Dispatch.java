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
 */
public final class Dispatch {
    /** A check on every call before it runs; throws to refuse. */
    @FunctionalInterface
    public interface Check {
        void check(Caller caller, String method, JsonObject params) throws RpcException;
    }

    static final long KEY_TTL_MS = 10 * 60_000;
    static final int MAX_KEYS = 1024;
    static final int MAX_KEY_LENGTH = 128;

    private final Map<String, Method> methods;
    private final List<Check> checks;
    private final LongSupplier clockMillis;
    private final Map<String, Remembered> keys = new LinkedHashMap<>();

    private record Remembered(String call, CompletableFuture<JsonElement> result, long expires) {}

    public Dispatch(Map<String, Method> methods) {
        this(methods, List.of());
    }

    public Dispatch(Map<String, Method> methods, List<Check> checks) {
        this(methods, checks, System::currentTimeMillis);
    }

    Dispatch(Map<String, Method> methods, List<Check> checks, LongSupplier clockMillis) {
        this.methods = Map.copyOf(methods);
        this.checks = List.copyOf(checks);
        this.clockMillis = clockMillis;
    }

    /** Call a method as {@code caller}; parse errors and refusals throw at once, server-thread errors complete the future. */
    public CompletableFuture<JsonElement> invoke(Caller caller, String name, JsonObject params) throws RpcException {
        return invoke(caller, name, params, null);
    }

    /** As {@link #invoke(Caller, String, JsonObject)}, with an idempotency key (null: none). */
    public CompletableFuture<JsonElement> invoke(Caller caller, String name, JsonObject params, String key) throws RpcException {
        Method method = methods.get(name);
        if (method == null) {
            throw RpcException.notFound("No method " + name + "; methods: " + String.join(", ", new TreeSet<>(methods.keySet())));
        }
        JsonObject p = params == null ? new JsonObject() : params;
        for (Check c : checks) c.check(caller, name, p);
        if (key == null) return method.call(new Args(params, caller));
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) throw RpcException.badRequest("key: 1 to " + MAX_KEY_LENGTH + " characters");
        String slot = caller.name() + "\u0000" + key;
        String call = name + "\u0000" + p;
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
            keys.put(slot, new Remembered(call, mine, now + KEY_TTL_MS));
        }
        try {
            method.call(new Args(params, caller)).whenComplete((v, e) -> {
                if (e != null) mine.completeExceptionally(e);
                else mine.complete(v);
            });
        } catch (RpcException | RuntimeException e) {
            // Refused before it ran: forget the key, so a corrected retry with it may run.
            synchronized (keys) {
                keys.remove(slot);
            }
            throw e;
        }
        return mine.thenApply(JsonElement::deepCopy);
    }

    public Set<String> names() {
        return methods.keySet();
    }
}
