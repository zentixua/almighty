package ua.zentix.almighty.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Bridge;
import ua.zentix.almighty.bridge.Dispatch;
import ua.zentix.almighty.bridge.Method;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /mcp} on a real loopback bridge: the recorded Claude Code requests get through with the agent's token and
 * reach the method as that agent; without a token the answer is 401 with {@code WWW-Authenticate}; a browser origin
 * is refused; GET is 405; the URL picks the profile. The instructions keep the core rule within the first 512
 * characters and stay under Claude Code's 4,096 cut.
 */
class McpEndpointTest {
    private Bridge bridge;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeEach
    void start() throws Exception {
        Map<String, Method> methods = Map.of(
                "status", a -> CompletableFuture.completedFuture(new JsonPrimitive("status as " + a.caller().name())),
                "events", a -> CompletableFuture.completedFuture(a.raw()),
                "notes", a -> CompletableFuture.completedFuture(JsonParser.parseString(
                        "{\"notes\":[{\"id\":\"almighty:game_master\",\"description\":\"How to run the server\"}]}")));
        bridge = new Bridge(new InetSocketAddress("127.0.0.1", 0), Agents.of(Map.of("gm", "secret-gm", "codex", "secret-codex")),
                new Dispatch(methods), Set.of("events"), Set.of());
        McpEndpoint endpoint = new McpEndpoint(bridge, Set.of("events"), "test", null);
        // policy.toml [agents.codex] profile = "compact": its own default, the URL still overrides it.
        endpoint.defaults(new McpEndpoint.Defaults() {
            public String profile(String agent) {
                return agent.equals("codex") ? "compact" : null;
            }

            public Boolean images(String agent) {
                return agent.equals("codex") ? Boolean.FALSE : null;
            }
        });
        bridge.context("/mcp", endpoint);
        bridge.start();
    }

    @AfterEach
    void stop() {
        bridge.stop();
    }

    private HttpResponse<String> post(String path, String token, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.address().getPort() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (token != null) b.header("Authorization", "Bearer " + token);
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void recordedClaudeCodeSessionOverHttp() throws Exception {
        List<McpProtocolTest.Recorded> rec = McpProtocolTest.recording("claude-code-2.1.332-modern.jsonl");
        for (McpProtocolTest.Recorded r : rec) {
            Map<String, String> h = new java.util.HashMap<>();
            r.headers().forEach((k, v) -> {
                if (k.startsWith("mcp-")) h.put(k, v);
            });
            HttpResponse<String> resp = post("/mcp", "secret-codex", h, r.body());
            assertEquals(200, resp.statusCode(), resp.body());
            assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
            JsonObject result = JsonParser.parseString(resp.body()).getAsJsonObject().getAsJsonObject("result");
            switch (r.json().get("method").getAsString()) {
                case "server/discover" -> {
                    String instructions = result.get("instructions").getAsString();
                    assertTrue(instructions.contains("almighty:game_master: How to run the server"), instructions);
                    assertTrue(instructions.length() < 4096);
                }
                case "tools/call" -> assertTrue(result.toString().contains("status as codex"), "the call runs as the token's agent");
                default -> { }
            }
        }
    }

    @Test
    void noTokenGetsAChallengeAndOthersAreRefused() throws Exception {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        HttpResponse<String> none = post("/mcp", null, Map.of(), init);
        assertEquals(401, none.statusCode());
        assertEquals(1, JsonParser.parseString(none.body()).getAsJsonObject().get("id").getAsInt(), "errors keep the request id");
        assertTrue(none.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
        assertEquals(401, post("/mcp", "wrong", Map.of(), init).statusCode());
        assertEquals(403, post("/mcp", "secret-gm", Map.of("Origin", "http://evil.example"), init).statusCode());
        HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.address().getPort() + "/mcp"))
                .header("Authorization", "Bearer secret-gm").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());
        HttpResponse<String> ok = post("/mcp", "secret-gm", Map.of(), init);
        assertEquals(200, ok.statusCode());
        assertFalse(ok.headers().firstValue("Mcp-Session-Id").isPresent(), "stateless: no session id");
        assertEquals(202, post("/mcp", "secret-gm", Map.of("MCP-Protocol-Version", "2025-06-18"),
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode());
    }

    @Test
    void urlPicksTheProfileAndLongToolsRun() throws Exception {
        String list = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        Map<String, String> legacy = Map.of("MCP-Protocol-Version", "2025-06-18");
        int full = JsonParser.parseString(post("/mcp", "secret-gm", legacy, list).body()).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools").size();
        int compact = JsonParser.parseString(post("/mcp?profile=compact&images=false", "secret-gm", legacy, list).body()).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools").size();
        assertEquals(14, full);
        assertEquals(6, compact);
        assertEquals(400, post("/mcp?profile=huge", "secret-gm", legacy, list).statusCode());
        int codex = JsonParser.parseString(post("/mcp", "secret-codex", legacy, list).body()).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools").size();
        int codexFull = JsonParser.parseString(post("/mcp?profile=full", "secret-codex", legacy, list).body()).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools").size();
        assertEquals(6, codex, "the agent's policy profile");
        assertEquals(14, codexFull, "the URL overrides it");
        HttpResponse<String> events = post("/mcp", "secret-gm", legacy,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"events\",\"arguments\":{\"after\":5}}}");
        assertTrue(events.body().contains("\\\"after\\\": 5"), events.body());
    }

    @Test
    void instructionsKeepTheCoreRuleFirst() {
        assertTrue(McpEndpoint.CORE.length() <= 512, "core rule: " + McpEndpoint.CORE.length() + " chars");
        String note = "{\"id\":\"m:n\",\"description\":\"" + "d".repeat(300) + "\"},";
        String many = McpEndpoint.notesBrief(JsonParser.parseString("{\"notes\":[" + note.repeat(200)
                + "{\"id\":\"last\",\"description\":\"x\"}]}"), McpEndpoint.MAX_INSTRUCTIONS - 900);
        assertTrue(many.length() <= McpEndpoint.MAX_INSTRUCTIONS - 900, "notes brief " + many.length());
        assertTrue(many.contains("more: the notes tool"));
        assertTrue(McpEndpoint.notesBrief(JsonParser.parseString("[{\"id\":\"old:a\",\"text\":\"Legacy first line\\nmore\"}]"), 1000)
                .contains("old:a: Legacy first line"), "older bridges return a plain list");
    }
}
