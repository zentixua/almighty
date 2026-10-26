package ua.zentix.almighty.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP bridge: {@code POST /rpc} with body {@code {"method": "...", "params": {...}}} and header
 * {@code Authorization: Bearer <token>}; response {@code {"ok": true, "result": ...}} or
 * {@code {"ok": false, "error": {"code": ..., "message": ...}}}. Uses Java's own HTTP server ({@code jdk.httpserver})
 * and a dedicated thread pool: long-polling the feed does not block other calls. Bridge threads do not access
 * the world; methods do so through the server thread.
 */
public final class Bridge {
    private static final Logger LOG = LogUtils.getLogger();
    /** Maximum request body: a layered build plan for hundreds of thousands of positions takes a few MB. */
    private static final int MAX_BODY = 16 << 20;
    /**
     * How long the bridge thread waits for a method response. Methods themselves wait less (feed: up to 25 s,
     * build and snapshot: up to 30 s), so this limit is reached only when the server thread stalls.
     */
    private static final long TIMEOUT_SECONDS = 60;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final HttpServer http;
    private final ExecutorService pool;
    private final Token token;
    private final Map<String, Method> methods;

    public Bridge(InetSocketAddress address, Token token, Map<String, Method> methods) throws IOException {
        this.token = token;
        this.methods = Map.copyOf(methods);
        this.http = HttpServer.create(address, 16);
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "almighty bridge " + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/rpc", this::handle);
    }

    public void start() {
        http.start();
    }

    /** Stop: reject new requests and disconnect clients waiting for a response. */
    public void stop() {
        http.stop(0);
        pool.shutdownNow();
    }

    public InetSocketAddress address() {
        return http.getAddress();
    }

    private void handle(HttpExchange exchange) {
        try {
            JsonObject response;
            int status = 200;
            try {
                response = ok(respond(exchange));
            } catch (RpcException e) {
                status = e.status();
                response = error(e.code(), e.getMessage());
            }
            send(exchange, status, response);
        } catch (IOException e) {
            // The client disconnected before receiving a response.
        } finally {
            exchange.close();
        }
    }

    private JsonElement respond(HttpExchange exchange) throws RpcException, IOException {
        if (!"POST".equals(exchange.getRequestMethod())) throw new RpcException(405, "method_not_allowed", "Only POST /rpc");
        if (!token.accepts(exchange.getRequestHeaders().getFirst("Authorization"))) {
            LOG.warn("Almighty bridge: request without a valid token from {}", exchange.getRemoteAddress());
            throw new RpcException(401, "unauthorized", "Header Authorization: Bearer <token> required");
        }
        byte[] body = read(exchange.getRequestBody());
        if (body == null) throw new RpcException(413, "too_large", "Request body larger than " + (MAX_BODY >> 20) + " MB");
        return dispatch(new String(body, StandardCharsets.UTF_8));
    }

    private JsonElement dispatch(String body) throws RpcException {
        JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) throw RpcException.badRequest("Expected an object {\"method\": ..., \"params\": {...}}");
            request = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw RpcException.badRequest("Not JSON: " + e.getMessage());
        }
        JsonElement name = request.get("method");
        if (name == null || !name.isJsonPrimitive()) throw RpcException.badRequest("Missing field method");
        Method method = methods.get(name.getAsString());
        if (method == null) {
            throw RpcException.notFound("No method " + name.getAsString() + "; methods: " + String.join(", ", new TreeSet<>(methods.keySet())));
        }
        JsonElement params = request.get("params");
        if (params != null && !params.isJsonNull() && !params.isJsonObject()) throw RpcException.badRequest("params: expected an object");
        try {
            return method.call(new Args(params == null || params.isJsonNull() ? null : params.getAsJsonObject()))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RpcException rpc) throw rpc;
            LOG.warn("Almighty bridge: method {} failed", name.getAsString(), cause);
            throw new RpcException(500, "internal", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (TimeoutException e) {
            throw new RpcException(504, "timeout",
                    "The server did not answer within " + TIMEOUT_SECONDS + " s (the server thread is busy); the call may still run later");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RpcException.unavailable("The bridge is stopping");
        } catch (RuntimeException e) {
            LOG.warn("Almighty bridge: method {} failed", name.getAsString(), e);
            throw new RpcException(500, "internal", e.getClass().getSimpleName() + ": " + e.getMessage());
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

    private static JsonObject error(String code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.add("error", err);
        return out;
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
