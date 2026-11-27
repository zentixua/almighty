package ua.zentix.almighty.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * HTTP bridge: {@code POST /rpc} with body {@code {"method": "...", "params": {...}, "key": "..."}} (key: optional
 * idempotency key, {@link Dispatch}) and header
 * {@code Authorization: Bearer <token>} (one token per agent, {@link Agents}); response {@code {"ok": true, "result": ...}}
 * or {@code {"ok": false, "error": {"code": ..., "message": ...}}}. Uses Java's own HTTP server ({@code jdk.httpserver}).
 *
 * <p>Threads: a small accept pool checks the request (origin, lockout, token, body, JSON) and hands it to one of two
 * bounded pools, so long polls (feed, job waits) never starve world calls: {@value #CALL_THREADS} threads for calls,
 * {@value #POLL_THREADS} for the methods named long when built. A full queue answers 503 at once instead of piling up.
 * Bridge threads do not access the world; methods do so through the server thread.
 *
 * <p>Five failed authentications in a minute lock an address out (429) for 5 s, doubling to 5 min; a valid token
 * always passes, so the lockout cannot be used against agents sharing the address.
 *
 * <p>Requests carrying an {@code Origin} header (browsers) are refused unless the origin is allowed: a web page cannot
 * drive the bridge through the owner's browser (DNS rebinding). Native clients send no {@code Origin}.
 */
public final class Bridge {
    private static final Logger LOG = LogUtils.getLogger();
    /** Maximum request body: a layered build plan for hundreds of thousands of positions takes a few MB. */
    private static final int MAX_BODY = 16 << 20;
    /**
     * How long the bridge thread waits for a method response. Methods themselves wait less (feed: up to 25 s,
     * build and snapshot: up to 50 s), so this limit is reached only when the server thread stalls.
     */
    private static final long TIMEOUT_SECONDS = 60;
    static final int ACCEPT_THREADS = 4;
    static final int CALL_THREADS = 8;
    static final int POLL_THREADS = 16;
    static final int QUEUE = 64;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final HttpServer http;
    private final boolean tls;
    private final ExecutorService accept;
    private final ThreadPoolExecutor calls;
    private final ThreadPoolExecutor polls;
    private final Agents agents;
    private final Dispatch dispatch;
    private final Set<String> longMethods;
    private final Set<String> allowedOrigins;
    private final AuthGuard guard;

    public Bridge(InetSocketAddress address, Agents agents, Dispatch dispatch, Set<String> longMethods, Set<String> allowedOrigins)
            throws IOException {
        this(address, agents, dispatch, longMethods, allowedOrigins, System::currentTimeMillis);
    }

    Bridge(InetSocketAddress address, Agents agents, Dispatch dispatch, Set<String> longMethods, Set<String> allowedOrigins,
           LongSupplier clockMillis) throws IOException {
        this(address, agents, dispatch, longMethods, allowedOrigins, clockMillis, null);
    }

    /** With {@code tls} (from {@link Tls#context}) the bridge speaks HTTPS only; null: plain HTTP, as before. */
    public Bridge(InetSocketAddress address, Agents agents, Dispatch dispatch, Set<String> longMethods, Set<String> allowedOrigins,
                  javax.net.ssl.SSLContext tls) throws IOException {
        this(address, agents, dispatch, longMethods, allowedOrigins, System::currentTimeMillis, tls);
    }

    Bridge(InetSocketAddress address, Agents agents, Dispatch dispatch, Set<String> longMethods, Set<String> allowedOrigins,
           LongSupplier clockMillis, javax.net.ssl.SSLContext tls) throws IOException {
        this.agents = agents;
        this.dispatch = dispatch;
        this.longMethods = Set.copyOf(longMethods);
        this.allowedOrigins = Set.copyOf(allowedOrigins);
        this.guard = new AuthGuard(clockMillis);
        if (tls == null) {
            this.http = HttpServer.create(address, 64);
        } else {
            com.sun.net.httpserver.HttpsServer https = com.sun.net.httpserver.HttpsServer.create(address, 64);
            https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls));
            this.http = https;
        }
        this.tls = tls != null;
        this.accept = pool("accept", ACCEPT_THREADS, Integer.MAX_VALUE);
        this.calls = pool("call", CALL_THREADS, QUEUE);
        this.polls = pool("poll", POLL_THREADS, QUEUE);
        http.setExecutor(accept);
        http.createContext("/rpc", this::handle);
    }

    private static ThreadPoolExecutor pool(String kind, int threads, int queue) {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, queue == Integer.MAX_VALUE
                ? new java.util.concurrent.LinkedBlockingQueue<>() : new ArrayBlockingQueue<>(queue), r -> {
            Thread t = new Thread(r, "almighty bridge " + kind + " " + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        http.start();
    }

    /** Stop: reject new requests and disconnect clients waiting for a response. */
    public void stop() {
        http.stop(0);
        accept.shutdownNow();
        // Queued requests never started: run them (they fail fast, the bridge is stopping) so their exchanges close.
        for (Runnable r : calls.shutdownNow()) r.run();
        for (Runnable r : polls.shutdownNow()) r.run();
    }

    public InetSocketAddress address() {
        return http.getAddress();
    }

    /** Whether the bridge speaks HTTPS. */
    public boolean tls() {
        return tls;
    }

    public Agents agents() {
        return agents;
    }

    public Dispatch dispatch() {
        return dispatch;
    }

    /** Mount another endpoint (the MCP endpoint) on the same port; it authenticates with {@link #authenticate}. */
    public void context(String path, HttpHandler handler) {
        http.createContext(path, handler);
    }

    /**
     * Check a request's origin, lockout and token; the caller on success. Every endpoint on this port goes through
     * here, so the origin rule, backoff and per-agent tokens apply to all of them.
     */
    public Caller authenticate(HttpExchange exchange) throws RpcException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw new RpcException(403, "forbidden_origin", "Origin " + origin + " is not allowed (bridge.allowed_origins)");
        }
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        // A valid token always passes: a lockout only slows guessing, so one stale or hostile client sharing an
        // address (all of loopback, a Docker NAT) cannot lock the live agents out.
        Agent agent = agents.authenticate(exchange.getRequestHeaders().getFirst("Authorization"));
        if (agent == null) {
            long locked = guard.lockedFor(address);
            if (locked > 0) {
                throw new RpcException(429, "locked_out", "Too many failed authentications from this address; retry in "
                        + (locked + 999) / 1000 + " s");
            }
            long lock = guard.failure(address);
            if (lock > 0) {
                LOG.warn("Almighty bridge: {} failed authentications from {}; locked out for {} s", AuthGuard.FREE_FAILURES, address, lock / 1000);
            } else if (guard.firstInWindow(address)) {
                LOG.warn("Almighty bridge: request without a valid token from {}", address);
            }
            throw new RpcException(401, "unauthorized", "Header Authorization: Bearer <token> required");
        }
        guard.success(address);
        agent.seen(address);
        return new Caller(agent, address);
    }

    /** Run work on the long-poll or the call pool; false if that pool is full. */
    public boolean submit(boolean longPoll, Runnable work) {
        try {
            (longPoll ? polls : calls).execute(work);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private void handle(HttpExchange exchange) {
        boolean handedOff = false;
        try {
            Request request = accept(exchange);
            handedOff = submit(longMethods.contains(request.method), () -> {
                try {
                    finish(exchange, request);
                } finally {
                    exchange.close();
                }
            });
            if (!handedOff) send(exchange, 503, error("busy", "The bridge is busy (" + (longMethods.contains(request.method)
                    ? "too many long polls" : "too many calls") + " waiting); retry shortly"));
        } catch (RpcException e) {
            trySend(exchange, e);
        } catch (IOException e) {
            // The client disconnected before receiving a response.
        } finally {
            if (!handedOff) exchange.close();
        }
    }

    private record Request(Caller caller, String method, JsonObject params, String key) {}

    private Request accept(HttpExchange exchange) throws RpcException, IOException {
        if (!"POST".equals(exchange.getRequestMethod())) throw new RpcException(405, "method_not_allowed", "Only POST /rpc");
        Caller caller = authenticate(exchange);
        byte[] body = read(exchange.getRequestBody());
        if (body == null) throw new RpcException(413, "too_large", "Request body larger than " + (MAX_BODY >> 20) + " MB");
        JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw RpcException.badRequest("Expected an object {\"method\": ..., \"params\": {...}}");
            request = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw RpcException.badRequest("Not JSON: " + e.getMessage());
        }
        JsonElement name = request.get("method");
        if (name == null || !name.isJsonPrimitive()) throw RpcException.badRequest("Missing field method");
        JsonElement params = request.get("params");
        if (params != null && !params.isJsonNull() && !params.isJsonObject()) throw RpcException.badRequest("params: expected an object");
        JsonElement key = request.get("key");
        if (key != null && !key.isJsonNull() && !key.isJsonPrimitive()) throw RpcException.badRequest("key: expected a string");
        return new Request(caller, name.getAsString(), params == null || params.isJsonNull() ? null : params.getAsJsonObject(),
                key == null || key.isJsonNull() ? null : key.getAsString());
    }

    private void finish(HttpExchange exchange, Request request) {
        try {
            send(exchange, 200, ok(call(request.caller, request.method, request.params, request.key)));
        } catch (RpcException e) {
            trySend(exchange, e);
        } catch (IOException e) {
            // The client disconnected before receiving a response.
        }
    }

    /** Invoke and wait for a method's result, mapping failures to bridge errors; {@code key}: idempotency key or null. */
    public JsonElement call(Caller caller, String method, JsonObject params, String key) throws RpcException {
        try {
            return dispatch.invoke(caller, method, params, key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RpcException rpc) throw rpc;
            LOG.warn("Almighty bridge: method {} failed", method, cause);
            throw new RpcException(500, "internal", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (TimeoutException e) {
            throw new RpcException(504, "timeout",
                    "The server did not answer within " + TIMEOUT_SECONDS + " s (the server thread is busy); the call may still run later");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RpcException.unavailable("The bridge is stopping");
        } catch (RuntimeException e) {
            LOG.warn("Almighty bridge: method {} failed", method, e);
            throw new RpcException(500, "internal", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void trySend(HttpExchange exchange, RpcException e) {
        try {
            send(exchange, e.status(), error(e));
        } catch (IOException ignored) {
            // The client disconnected before receiving a response.
        }
    }

    /** The full body if no larger than {@link #MAX_BODY}; null otherwise. */
    private static byte[] read(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_BODY + 1);
        return body.length > MAX_BODY ? null : body;
    }

    private static JsonObject ok(JsonElement result) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.add("result", result);
        return out;
    }

    /** The error object of a refusal, with its extra fields ({@code allowed}, {@code node} …). */
    public static JsonObject error(RpcException e) {
        JsonObject out = error(e.code(), e.getMessage());
        if (e.details() != null) {
            for (var d : e.details().entrySet()) out.getAsJsonObject("error").add(d.getKey(), d.getValue());
        }
        return out;
    }

    public static JsonObject error(String code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.add("error", err);
        return out;
    }

    public static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
