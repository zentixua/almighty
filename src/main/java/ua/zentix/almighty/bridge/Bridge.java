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
 * HTTP-мост: {@code POST /rpc} с телом {@code {"method": "...", "params": {...}}} и заголовком
 * {@code Authorization: Bearer <токен>}; ответ {@code {"ok": true, "result": ...}} или
 * {@code {"ok": false, "error": {"code": ..., "message": ...}}}. HTTP-сервер — из самой Java ({@code jdk.httpserver}),
 * свой пул потоков: долгий опрос ленты не держит остальные вызовы. Потоки моста к миру не ходят — это делают методы
 * через поток сервера.
 */
public final class Bridge {
    private static final Logger LOG = LogUtils.getLogger();
    /** Тело запроса не больше: план постройки слоями на сотни тысяч мест — единицы МБ. */
    private static final int MAX_BODY = 16 << 20;
    /**
     * Сколько поток моста ждёт ответа метода. Сами методы ждут меньше (лента — до 25 с, постройка и снимок — до 30 с),
     * так что предел срабатывает, только когда поток сервера стоит.
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

    /** Остановить: новые запросы не принимаются, ждущие ответа получают обрыв соединения. */
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
            // клиент ушёл, не дождавшись ответа
        } finally {
            exchange.close();
        }
    }

    private JsonElement respond(HttpExchange exchange) throws RpcException, IOException {
        if (!"POST".equals(exchange.getRequestMethod())) throw new RpcException(405, "method_not_allowed", "Только POST /rpc");
        if (!token.accepts(exchange.getRequestHeaders().getFirst("Authorization"))) {
            LOG.warn("Мост ведущего: запрос без верного токена с {}", exchange.getRemoteAddress());
            throw new RpcException(401, "unauthorized", "Нужен заголовок Authorization: Bearer <токен>");
        }
        byte[] body = read(exchange.getRequestBody());
        if (body == null) throw new RpcException(413, "too_large", "Тело запроса больше " + (MAX_BODY >> 20) + " МБ");
        return dispatch(new String(body, StandardCharsets.UTF_8));
    }

    private JsonElement dispatch(String body) throws RpcException {
        JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) throw RpcException.badRequest("Ожидается объект {\"method\": ..., \"params\": {...}}");
            request = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw RpcException.badRequest("Не JSON: " + e.getMessage());
        }
        JsonElement name = request.get("method");
        if (name == null || !name.isJsonPrimitive()) throw RpcException.badRequest("Нет поля method");
        Method method = methods.get(name.getAsString());
        if (method == null) {
            throw RpcException.notFound("Нет метода " + name.getAsString() + "; есть: " + String.join(", ", new TreeSet<>(methods.keySet())));
        }
        JsonElement params = request.get("params");
        if (params != null && !params.isJsonNull() && !params.isJsonObject()) throw RpcException.badRequest("params: ожидается объект");
        try {
            return method.call(new Args(params == null || params.isJsonNull() ? null : params.getAsJsonObject()))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RpcException rpc) throw rpc;
            LOG.warn("Мост ведущего: метод {} упал", name.getAsString(), cause);
            throw new RpcException(500, "internal", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (TimeoutException e) {
            throw new RpcException(504, "timeout",
                    "Сервер не ответил за " + TIMEOUT_SECONDS + " с (поток сервера занят); вызов мог выполниться позже");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RpcException.unavailable("Мост останавливается");
        } catch (RuntimeException e) {
            LOG.warn("Мост ведущего: метод {} упал", name.getAsString(), e);
            throw new RpcException(500, "internal", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Тело целиком, если не больше {@link #MAX_BODY}; больше — null. */
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
