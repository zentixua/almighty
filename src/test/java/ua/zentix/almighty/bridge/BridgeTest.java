package ua.zentix.almighty.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bridge over real loopback HTTP: per-agent tokens, caller identity, origin rule, failed-auth lockout, separate pools
 * for long polls and calls, request parsing, method errors. Unicode payloads exercise UTF-8 round trips.
 */
class BridgeTest {
    private Bridge bridge;
    private final AtomicLong clock = new AtomicLong(1_000_000);
    /** Released at test end: "hold" calls block a long-poll thread until then. */
    private final CountDownLatch release = new CountDownLatch(1);
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeEach
    void start() throws Exception {
        Map<String, Method> methods = Map.of(
                "echo", a -> CompletableFuture.completedFuture(a.raw()),
                "need", a -> CompletableFuture.completedFuture(new JsonPrimitive(a.integer("n", 1, 10))),
                "boom", a -> CompletableFuture.failedFuture(new IllegalStateException("broken")),
                "late", a -> CompletableFuture.failedFuture(RpcException.conflict("busy")),
                "throws", a -> {
                    throw new IllegalArgumentException("immediate");
                },
                "whoami", a -> CompletableFuture.completedFuture(new JsonPrimitive(a.caller().name())),
                "hold", a -> {
                    CompletableFuture<com.google.gson.JsonElement> f = new CompletableFuture<>();
                    Thread.ofVirtual().start(() -> {
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        f.complete(new JsonPrimitive("released"));
                    });
                    return f;
                });
        Agents agents = Agents.of(Map.of("gm", "secret-abc", "builder", "secret-builder"));
        bridge = new Bridge(new InetSocketAddress("127.0.0.1", 0), agents, new Dispatch(methods), Set.of("hold"),
                Set.of("https://allowed.example"), clock::get);
        bridge.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        bridge.stop();
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        return post(token, body, null);
    }

    private HttpRequest request(String token, String body, String origin) {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.address().getPort() + "/rpc"))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) r.header("Authorization", "Bearer " + token);
        if (origin != null) r.header("Origin", origin);
        return r.build();
    }

    private HttpResponse<String> post(String token, String body, String origin) throws Exception {
        return http.send(request(token, body, origin), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    @Test
    void echoWithToken() throws Exception {
        HttpResponse<String> r = post("secret-abc", "{\"method\":\"echo\",\"params\":{\"слово\":\"привет\"}}");
        assertEquals(200, r.statusCode());
        JsonObject body = json(r);
        assertTrue(body.get("ok").getAsBoolean());
        assertEquals("привет", body.getAsJsonObject("result").get("слово").getAsString());
    }

    @Test
    void wrongOrMissingTokenRejected() throws Exception {
        assertEquals(401, post(null, "{\"method\":\"echo\"}").statusCode());
        assertEquals(401, post("secret-abd", "{\"method\":\"echo\"}").statusCode());
        assertEquals("unauthorized", json(post("x", "{}")).getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void getRejected() throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.address().getPort() + "/rpc"))
                .header("Authorization", "Bearer secret-abc").GET().build();
        assertEquals(405, http.send(r, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void badRequests() throws Exception {
        assertEquals(400, post("secret-abc", "не json").statusCode());
        assertEquals(400, post("secret-abc", "[1, 2]").statusCode());
        assertEquals(400, post("secret-abc", "{\"params\":{}}").statusCode());
        assertEquals(400, post("secret-abc", "{\"method\":\"echo\",\"params\":[1]}").statusCode());
        HttpResponse<String> missing = post("secret-abc", "{\"method\":\"нет\"}");
        assertEquals(404, missing.statusCode());
        assertTrue(json(missing).getAsJsonObject("error").get("message").getAsString().contains("echo"), "method list");
        HttpResponse<String> range = post("secret-abc", "{\"method\":\"need\",\"params\":{\"n\":11}}");
        assertEquals(400, range.statusCode());
        assertFalse(json(range).get("ok").getAsBoolean());
    }

    @Test
    void methodErrors() throws Exception {
        assertEquals(500, post("secret-abc", "{\"method\":\"boom\"}").statusCode());
        assertEquals(500, post("secret-abc", "{\"method\":\"throws\"}").statusCode());
        HttpResponse<String> late = post("secret-abc", "{\"method\":\"late\"}");
        assertEquals(409, late.statusCode());
        assertEquals("busy", json(late).getAsJsonObject("error").get("message").getAsString());
    }

    @Test
    void eachAgentIsItsOwnCaller() throws Exception {
        assertEquals("gm", json(post("secret-abc", "{\"method\":\"whoami\"}")).get("result").getAsString());
        assertEquals("builder", json(post("secret-builder", "{\"method\":\"whoami\"}")).get("result").getAsString());
        assertEquals("gm", json(post("secret-abc", "{\"method\":\"whoami\",\"params\":{\"agent\":\"builder\"}}"))
                .get("result").getAsString(), "parameters cannot change who is calling");
        assertTrue(bridge.agents().get("builder").lastSeenMillis() > 0, "presence recorded");
    }

    @Test
    void browserOriginsRefusedUnlessAllowed() throws Exception {
        HttpResponse<String> r = post("secret-abc", "{\"method\":\"echo\"}", "http://evil.example");
        assertEquals(403, r.statusCode());
        assertEquals("forbidden_origin", json(r).getAsJsonObject("error").get("code").getAsString());
        assertEquals(403, post("secret-abc", "{\"method\":\"echo\"}", "null").statusCode());
        assertEquals(200, post("secret-abc", "{\"method\":\"echo\"}", "https://allowed.example").statusCode());
        assertEquals(200, post("secret-abc", "{\"method\":\"echo\"}").statusCode(), "no Origin: native client");
    }

    @Test
    void failedAuthenticationLocksTheAddressOut() throws Exception {
        for (int i = 0; i < AuthGuard.FREE_FAILURES; i++) assertEquals(401, post("guess-" + i, "{\"method\":\"echo\"}").statusCode());
        HttpResponse<String> locked = post("secret-right-guess", "{\"method\":\"echo\"}");
        assertEquals(429, locked.statusCode(), "guessing waits out the lockout");
        assertEquals("locked_out", json(locked).getAsJsonObject("error").get("code").getAsString());
        assertEquals(200, post("secret-abc", "{\"method\":\"echo\"}").statusCode(),
                "a valid token still passes: a stale client on the same address cannot lock the live agents out");
        for (int i = 0; i < AuthGuard.FREE_FAILURES; i++) post("guess-" + i, "{\"method\":\"echo\"}");
        clock.addAndGet(AuthGuard.FIRST_LOCK_MS);
        assertEquals(401, post("guess-again", "{\"method\":\"echo\"}").statusCode(), "lockout over");
    }

    @Test
    void longPollsDoNotStarveCalls() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> held = new ArrayList<>();
        for (int i = 0; i < Bridge.POLL_THREADS; i++) {
            held.add(http.sendAsync(request("secret-abc", "{\"method\":\"hold\"}", null), HttpResponse.BodyHandlers.ofString()));
        }
        // Every long-poll thread is busy: a call still answers at once.
        long start = System.nanoTime();
        assertEquals(200, post("secret-abc", "{\"method\":\"echo\"}").statusCode());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        release.countDown();
        for (CompletableFuture<HttpResponse<String>> f : held) assertEquals(200, f.get(30, TimeUnit.SECONDS).statusCode());
    }

    @Test
    void fullPoolAnswersBusy() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> held = new ArrayList<>();
        for (int i = 0; i < Bridge.POLL_THREADS + Bridge.QUEUE + 8; i++) {
            held.add(http.sendAsync(request("secret-abc", "{\"method\":\"hold\"}", null), HttpResponse.BodyHandlers.ofString()));
        }
        boolean busy = false;
        for (int i = 0; i < 200 && !busy; i++) {
            Thread.sleep(25);
            for (CompletableFuture<HttpResponse<String>> f : held) {
                if (f.isDone() && f.get().statusCode() == 503) busy = true;
            }
        }
        java.util.Map<Integer, Integer> seen = new java.util.TreeMap<>();
        for (CompletableFuture<HttpResponse<String>> f : held) if (f.isDone()) seen.merge(f.get().statusCode(), 1, Integer::sum);
        assertTrue(busy, "overflow is refused with 503, not queued without bound; done: " + seen);
        release.countDown();
        for (CompletableFuture<HttpResponse<String>> f : held) {
            int status = f.get(30, TimeUnit.SECONDS).statusCode();
            assertTrue(status == 200 || status == 503, "status " + status);
        }
    }

    /** Stability M8: stop() runs on the server thread at shutdown; queued long polls are answered, never run there. */
    @Test
    void stopAnswersQueuedRequestsWithoutRunningThem() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> held = new ArrayList<>();
        for (int i = 0; i < Bridge.POLL_THREADS + 4; i++) {
            held.add(http.sendAsync(request("secret-abc", "{\"method\":\"hold\"}", null), HttpResponse.BodyHandlers.ofString()));
        }
        for (int i = 0; i < 400 && bridge.queued(true) < 4; i++) Thread.sleep(10);
        assertEquals(4, bridge.queued(true), "four holds wait in the queue");
        long start = System.nanoTime();
        bridge.stop();
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2), "stop does not wait for queued polls");
        int stopping = 0;
        for (CompletableFuture<HttpResponse<String>> f : held) {
            try {
                HttpResponse<String> r = f.get(5, TimeUnit.SECONDS);
                if (r.statusCode() == 503 && "stopping".equals(json(r).getAsJsonObject("error").get("code").getAsString())) stopping++;
            } catch (java.util.concurrent.ExecutionException e) {
                // A running hold's connection closed by the stop.
            }
        }
        assertEquals(4, stopping, "each queued request is answered 503 stopping");
    }

    /** Plan 9.1, F1: a valid token from an address outside the agent's allow_from is refused (and counts as a failure). */
    @Test
    void allowFromRefusesOtherAddresses() throws Exception {
        ua.zentix.almighty.policy.Policy p = ua.zentix.almighty.policy.Policy.parse("[agents.builder]\nallow_from = [\"10.0.0.0/8\"]\n");
        ua.zentix.almighty.policy.Nodes.start(null, () -> p);
        try {
            HttpResponse<String> r = post("secret-builder", "{\"method\":\"whoami\"}");
            assertEquals(403, r.statusCode());
            assertEquals("not_allowed_from", json(r).getAsJsonObject("error").get("code").getAsString());
            assertEquals(200, post("secret-abc", "{\"method\":\"whoami\"}").statusCode(), "another agent without allow_from");
        } finally {
            ua.zentix.almighty.policy.Nodes.start(null, ua.zentix.almighty.policy.Policy::defaults);
        }
    }
}
