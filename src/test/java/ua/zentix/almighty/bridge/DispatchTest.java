package ua.zentix.almighty.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The choke point: checks run before every method; idempotency keys make a retry return the first result. */
class DispatchTest {
    private final AtomicInteger runs = new AtomicInteger();
    private final AtomicLong clock = new AtomicLong(0);
    private final Agents agents = Agents.of(Map.of("gm", "a".repeat(32), "builder", "b".repeat(32)));
    private final Caller gm = new Caller(agents.get("gm"), "127.0.0.1");
    private final Caller builder = new Caller(agents.get("builder"), "127.0.0.1");
    private final Dispatch dispatch = new Dispatch(Map.of(
            "count", a -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())),
            "pending", a -> new CompletableFuture<>(),
            "look", a -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())),
            "flaky", a -> CompletableFuture.failedFuture(new RpcException(504, "timeout", "late " + runs.incrementAndGet()))),
            List.of((caller, method, params) -> {
                if (params.has("forbidden")) throw new RpcException(403, "denied", "no");
            }), (m, p) -> m.equals("look"), clock::get);

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void checksRunFirst() {
        RpcException e = assertThrows(RpcException.class, () -> dispatch.invoke(gm, "count", json("{\"forbidden\":1}")));
        assertEquals(403, e.status());
        assertEquals(0, runs.get(), "a refused call never runs");
    }

    @Test
    void retryWithKeyRunsOnce() throws Exception {
        assertEquals(1, dispatch.invoke(gm, "count", json("{\"x\":1}"), "k1").get().getAsInt());
        assertEquals(1, dispatch.invoke(gm, "count", json("{\"x\":1}"), "k1").get().getAsInt(), "same result, not run again");
        assertEquals(1, runs.get());
        assertEquals(2, dispatch.invoke(builder, "count", json("{\"x\":1}"), "k1").get().getAsInt(), "keys are per agent");
        assertEquals(3, dispatch.invoke(gm, "count", json("{\"x\":1}")).get().getAsInt(), "no key: runs every time");
        RpcException e = assertThrows(RpcException.class, () -> dispatch.invoke(gm, "count", json("{\"x\":2}"), "k1"));
        assertEquals(409, e.status(), "same key, different call");
        clock.addAndGet(Dispatch.KEY_TTL_MS + 1);
        assertEquals(4, dispatch.invoke(gm, "count", json("{\"x\":1}"), "k1").get().getAsInt(), "expired keys are forgotten");
    }

    @Test
    void retryJoinsARunningCall() throws Exception {
        CompletableFuture<?> first = dispatch.invoke(gm, "pending", null, "slow");
        CompletableFuture<?> retry = dispatch.invoke(gm, "pending", null, "slow");
        assertEquals(false, first.isDone());
        assertEquals(false, retry.isDone(), "the retry waits for the first call instead of starting another");
    }

    @Test
    void refusedCallDoesNotKeepItsKey() throws Exception {
        assertThrows(RpcException.class, () -> dispatch.invoke(gm, "count", json("{\"forbidden\":1}"), "k2"));
        assertEquals(1, dispatch.invoke(gm, "count", json("{\"ok\":1}"), "k2").get().getAsInt());
    }

    @Test
    void readOnlyCallsIgnoreKeys() throws Exception {
        assertEquals(1, dispatch.invoke(gm, "look", null, "v").get().getAsInt());
        assertEquals(2, dispatch.invoke(gm, "look", null, "v").get().getAsInt(), "a view runs again; its image is never kept");
    }

    @Test
    void failedCallForgetsItsKey() {
        assertThrows(Exception.class, () -> dispatch.invoke(gm, "flaky", null, "f").get());
        assertThrows(Exception.class, () -> dispatch.invoke(gm, "flaky", null, "f").get());
        assertEquals(2, runs.get(), "a retry after a failure runs afresh");
    }

    @Test
    void boundedKeys() throws Exception {
        for (int i = 0; i < Dispatch.MAX_KEYS + 10; i++) dispatch.invoke(gm, "count", null, "k" + i);
        assertEquals(Dispatch.MAX_KEYS + 10, runs.get());
    }

    /** Stability audit M1: one agent's many keys never push out another agent's retry protection. */
    @Test
    void keysAreKeptPerAgent() throws Exception {
        dispatch.invoke(builder, "count", null, "mine").get();
        for (int i = 0; i < Dispatch.MAX_KEYS + 10; i++) dispatch.invoke(gm, "count", null, "k" + i);
        int before = runs.get();
        dispatch.invoke(builder, "count", null, "mine").get();
        assertEquals(before, runs.get(), "the builder's retry still gets its first result");
    }
}
