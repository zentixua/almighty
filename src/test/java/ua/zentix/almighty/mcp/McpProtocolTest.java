package ua.zentix.almighty.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.RpcException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP endpoint against requests recorded from real clients on loopback (27.11.2026): Claude Code 2.1.332, which
 * probes the modern era with {@code server/discover} and falls back to {@code initialize} when the probe fails, and
 * Codex CLI 0.172.0, which speaks the legacy era at 2025-06-18. Each recorded request must get the reply that keeps
 * that client going: the version it asked for, tools, and a tool call routed to the bridge method.
 */
class McpProtocolTest {
    static final McpProtocol.Settings FULL = new McpProtocol.Settings(Profile.FULL, true, "almighty", "test");

    /** A bridge that records calls and answers {@code status} like a server. */
    static final class FakeBackend implements McpProtocol.Backend {
        final List<String> calls = new ArrayList<>();
        JsonElement answer = JsonParser.parseString("{\"tps\": 20.0, \"players\": [\"Alex\"]}");

        @Override
        public JsonElement call(String method, JsonObject params) throws RpcException {
            calls.add(method + " " + params);
            if (method.equals("nope")) throw RpcException.notFound("No method nope");
            return answer;
        }

        @Override
        public String instructions() {
            return "Game master on a Minecraft server.";
        }
    }

    record Recorded(String verb, Map<String, String> headers, String body) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    static List<Recorded> recording(String name) throws IOException {
        List<Recorded> out = new ArrayList<>();
        try (InputStream in = McpProtocolTest.class.getResourceAsStream("/mcp/" + name)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) continue;
                JsonObject r = JsonParser.parseString(line).getAsJsonObject();
                Map<String, String> headers = new HashMap<>();
                r.getAsJsonObject("headers").entrySet().forEach(e -> headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString()));
                out.add(new Recorded(r.get("verb").getAsString(), headers, r.get("body").isJsonNull() ? null : r.get("body").getAsString()));
            }
        }
        return out;
    }

    static McpProtocol.Reply send(Recorded r, McpProtocol.Settings s, McpProtocol.Backend b) {
        McpProtocol.Prepared p = McpProtocol.prepare(r.headers(), r.body().getBytes(StandardCharsets.UTF_8), s, b);
        return McpProtocol.finish(p, s, b);
    }

    static McpProtocol.Reply send(Map<String, String> headers, String body, McpProtocol.Settings s, McpProtocol.Backend b) {
        return send(new Recorded("POST", headers, body), s, b);
    }

    @Test
    void claudeCodeModernEra() throws IOException {
        FakeBackend backend = new FakeBackend();
        List<Recorded> rec = recording("claude-code-2.1.332-modern.jsonl");
        assertEquals("server/discover", rec.get(0).json().get("method").getAsString());

        McpProtocol.Reply discover = send(rec.get(0), FULL, backend);
        assertEquals(200, discover.status());
        JsonObject result = discover.body().getAsJsonObject("result");
        assertTrue(result.getAsJsonArray("supportedVersions").toString().contains("2026-07-28"));
        assertEquals("complete", result.get("resultType").getAsString());
        assertTrue(result.has("ttlMs") && result.has("cacheScope"));
        assertTrue(result.get("instructions").getAsString().startsWith("Game master"));
        assertEquals("almighty", result.getAsJsonObject("_meta").getAsJsonObject(McpProtocol.META_SERVER).get("name").getAsString());

        McpProtocol.Reply list = send(rec.get(1), FULL, backend);
        JsonArray tools = list.body().getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(14, tools.size());
        assertEquals("complete", list.body().getAsJsonObject("result").get("resultType").getAsString());

        McpProtocol.Reply call = send(rec.get(2), FULL, backend);
        assertEquals(200, call.status());
        JsonObject r = call.body().getAsJsonObject("result");
        assertFalse(r.get("isError").getAsBoolean());
        assertEquals("complete", r.get("resultType").getAsString());
        assertTrue(r.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("Alex"));
        assertEquals(List.of("status {}"), backend.calls);
    }

    @Test
    void claudeCodeFallbackLegacyEra() throws IOException {
        FakeBackend backend = new FakeBackend();
        List<Recorded> rec = recording("claude-code-2.1.332-fallback.jsonl");
        for (Recorded r : rec) {
            if (!r.verb().equals("POST")) continue; // GET is answered 405 by the endpoint
            String method = r.json().get("method").getAsString();
            McpProtocol.Reply reply = send(r, FULL, backend);
            switch (method) {
                case "server/discover" -> {
                    // The probe now succeeds: this server is dual-era, so the client stays modern.
                    assertEquals(200, reply.status());
                    assertTrue(reply.body().has("result"));
                }
                case "initialize" -> {
                    JsonObject result = reply.body().getAsJsonObject("result");
                    assertEquals("2025-11-25", result.get("protocolVersion").getAsString());
                    assertTrue(result.getAsJsonObject("capabilities").has("tools"));
                    assertFalse(result.has("resultType"), "legacy results keep their legacy shape");
                }
                case "notifications/initialized" -> {
                    assertEquals(202, reply.status());
                    assertNull(reply.body());
                }
                case "tools/list" -> assertEquals(14, reply.body().getAsJsonObject("result").getAsJsonArray("tools").size());
                case "tools/call" -> assertFalse(reply.body().getAsJsonObject("result").get("isError").getAsBoolean());
                default -> throw new AssertionError(method);
            }
        }
        assertEquals(List.of("status {}"), backend.calls);
    }

    @Test
    void codexLegacyEra() throws IOException {
        FakeBackend backend = new FakeBackend();
        List<Recorded> rec = recording("codex-0.172.0.jsonl");
        McpProtocol.Reply init = send(rec.get(0), FULL, backend);
        assertEquals("2025-06-18", init.body().getAsJsonObject("result").get("protocolVersion").getAsString());
        assertEquals(202, send(rec.get(1), FULL, backend).status());
        JsonArray tools = send(rec.get(2), FULL, backend).body().getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(14, tools.size());
        for (JsonElement t : tools) {
            JsonObject tool = t.getAsJsonObject();
            assertTrue(tool.getAsJsonObject("inputSchema").get("type").getAsString().equals("object"), tool.get("name").toString());
            assertTrue(tool.getAsJsonObject("annotations").has("readOnlyHint"), tool.get("name").toString());
        }
    }

    private static Map<String, String> modernHeaders(String method, String name) {
        Map<String, String> h = new HashMap<>();
        h.put("mcp-protocol-version", "2026-07-28");
        h.put("mcp-method", method);
        if (name != null) h.put("mcp-name", name);
        return h;
    }

    private static String modernCall(String version, String tool, String args) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + args
                + ",\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + version + "\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
    }

    @Test
    void modernHeadersMustMatchTheBody() {
        FakeBackend b = new FakeBackend();
        McpProtocol.Reply wrongName = send(modernHeaders("tools/call", "events"), modernCall("2026-07-28", "status", "{}"), FULL, b);
        assertEquals(400, wrongName.status());
        assertEquals(McpProtocol.HEADER_MISMATCH, wrongName.body().getAsJsonObject("error").get("code").getAsInt());
        McpProtocol.Reply noName = send(modernHeaders("tools/call", null), modernCall("2026-07-28", "status", "{}"), FULL, b);
        assertEquals(400, noName.status());
        Map<String, String> h = modernHeaders("tools/call", "=?base64?c3RhdHVz?=");
        assertEquals(200, send(h, modernCall("2026-07-28", "status", "{}"), FULL, b).status(), "base64 sentinel names decode");
        Map<String, String> otherVersion = modernHeaders("tools/call", "status");
        otherVersion.put("mcp-protocol-version", "2025-06-18");
        assertEquals(400, send(otherVersion, modernCall("2026-07-28", "status", "{}"), FULL, b).status());
        assertEquals(List.of("status {}"), b.calls, "only the valid request reached the bridge");
    }

    @Test
    void unknownVersionListsTheSupportedOnes() {
        Map<String, String> h = modernHeaders("tools/call", "status");
        h.put("mcp-protocol-version", "2099-01-01");
        McpProtocol.Reply r = send(h, modernCall("2099-01-01", "status", "{}"), FULL, new FakeBackend());
        assertEquals(400, r.status());
        JsonObject err = r.body().getAsJsonObject("error");
        assertEquals(McpProtocol.UNSUPPORTED_VERSION, err.get("code").getAsInt());
        assertEquals("2099-01-01", err.getAsJsonObject("data").get("requested").getAsString());
        assertTrue(err.getAsJsonObject("data").getAsJsonArray("supported").toString().contains("2025-06-18"));
    }

    @Test
    void badMessagesAndToolErrors() {
        FakeBackend b = new FakeBackend();
        Map<String, String> legacy = Map.of("mcp-protocol-version", "2025-06-18");
        assertEquals(400, send(legacy, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]", FULL, b).status());
        assertEquals(400, send(legacy, "not json", FULL, b).status());
        JsonObject unknown = send(legacy, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"fly\"}}", FULL, b).body();
        assertEquals(McpProtocol.INVALID_PARAMS, unknown.getAsJsonObject("error").get("code").getAsInt());
        JsonObject refused = send(legacy, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"view\",\"arguments\":{\"kind\":\"map\"}}}", FULL, b)
                .body().getAsJsonObject("result");
        assertTrue(refused.get("isError").getAsBoolean());
        assertTrue(refused.toString().contains("center [x, z] or both corners"));
        JsonObject bridgeError = send(legacy, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"call\",\"arguments\":{\"method\":\"nope\"}}}", FULL, b)
                .body().getAsJsonObject("result");
        assertTrue(bridgeError.get("isError").getAsBoolean());
        assertTrue(bridgeError.toString().contains("not_found: No method nope"));
        assertEquals(List.of("nope {}"), b.calls, "refused arguments never reach the bridge");
        JsonObject resources = send(legacy, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}", FULL, b).body();
        assertEquals(McpProtocol.METHOD_NOT_FOUND, resources.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(send(legacy, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}", FULL, b).body().has("result"));
    }

    @Test
    void imagesComeWithTheirTextUnlessTurnedOff() {
        JsonElement snapshot = JsonParser.parseString("{\"png_base64\":\"iVBORw0KGgo=\",\"legend\":{\"x\":1}}");
        JsonArray with = McpProtocol.content(snapshot, true).getAsJsonArray("content");
        assertEquals("image", with.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("image/png", with.get(0).getAsJsonObject().get("mimeType").getAsString());
        assertTrue(with.get(1).getAsJsonObject().get("text").getAsString().contains("legend"));
        assertFalse(with.get(1).toString().contains("iVBOR"));
        JsonArray without = McpProtocol.content(snapshot, false).getAsJsonArray("content");
        assertEquals(1, without.size());
        assertTrue(without.get(0).toString().contains("images=false"));
    }

    @Test
    void compactProfileHasSixShortTools() {
        McpProtocol.Settings compact = new McpProtocol.Settings(Profile.COMPACT, false, "almighty", "test");
        JsonArray tools = Tools.list(Profile.COMPACT);
        assertEquals(Tools.COMPACT.size(), tools.size());
        int chars = tools.toString().length();
        assertTrue(chars < Tools.list(Profile.FULL).toString().length() / 2, "compact list is under half the full one: " + chars);
        FakeBackend b = new FakeBackend();
        Map<String, String> legacy = Map.of("mcp-protocol-version", "2025-06-18");
        send(legacy, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"act\",\"arguments\":{\"kind\":\"command\",\"commands\":[\"time set day\"]}}}", compact, b);
        assertEquals(List.of("command {\"commands\":[\"time set day\"]}"), b.calls);
        JsonObject bot = send(legacy, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"bot\",\"arguments\":{}}}", compact, b).body();
        assertTrue(bot.has("error"), "full-only tools are unknown in the compact profile");
    }
}
