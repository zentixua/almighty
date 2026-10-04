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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Мост по настоящему HTTP на петле: токен, разбор запроса, ошибки методов. */
class BridgeTest {
    private Bridge bridge;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeEach
    void start() throws Exception {
        Map<String, Method> methods = Map.of(
                "echo", a -> CompletableFuture.completedFuture(a.raw()),
                "need", a -> CompletableFuture.completedFuture(new JsonPrimitive(a.integer("n", 1, 10))),
                "boom", a -> CompletableFuture.failedFuture(new IllegalStateException("сломалось")),
                "late", a -> CompletableFuture.failedFuture(RpcException.conflict("занято")),
                "throws", a -> {
                    throw new IllegalArgumentException("сразу");
                });
        bridge = new Bridge(new InetSocketAddress("127.0.0.1", 0), Token.of("secret-abc"), methods);
        bridge.start();
    }

    @AfterEach
    void stop() {
        bridge.stop();
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.address().getPort() + "/rpc"))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) r.header("Authorization", "Bearer " + token);
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
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
        assertTrue(json(missing).getAsJsonObject("error").get("message").getAsString().contains("echo"), "список методов");
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
        assertEquals("занято", json(late).getAsJsonObject("error").get("message").getAsString());
    }
}
