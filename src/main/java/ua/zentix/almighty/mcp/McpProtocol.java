package ua.zentix.almighty.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import ua.zentix.almighty.bridge.RpcException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * MCP over Streamable HTTP, both protocol eras on one endpoint, without an SDK and without sessions:
 * <ul>
 * <li><b>Legacy</b> (2024-11-05 to 2025-11-25, what Codex CLI and Claude Code channels speak): {@code initialize}
 * answers the negotiated version, capabilities and instructions; no session id is minted, so every later request
 * stands alone; {@code ping}, {@code tools/list}, {@code tools/call}.</li>
 * <li><b>Modern</b> (2026-07-28): no handshake; each request carries its version and capabilities in {@code _meta};
 * {@code server/discover}; the {@code MCP-Protocol-Version}, {@code Mcp-Method} and {@code Mcp-Name} headers must
 * match the body (400, {@code -32020}); an unknown version is 400, {@code -32022}, listing the supported ones; every
 * result carries {@code resultType}, list results {@code ttlMs} and {@code cacheScope}.</li>
 * </ul>
 * Notifications get 202. Replies are always one JSON object ({@code application/json}); no SSE stream is needed
 * because nothing is sent before the final response. Pure: the HTTP endpoint supplies headers and body, and a
 * {@link Backend} that makes bridge calls as the authenticated agent.
 */
public final class McpProtocol {
    public static final String MODERN = "2026-07-28";
    /** Legacy versions served, newest first. */
    public static final List<String> LEGACY = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    public static final List<String> SUPPORTED = List.of(MODERN, "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_SERVER = "io.modelcontextprotocol/serverInfo";
    static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601, INVALID_PARAMS = -32602,
            INTERNAL = -32603, HEADER_MISMATCH = -32020, UNSUPPORTED_VERSION = -32022;
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private McpProtocol() {}

    /** Bridge calls as the connection's agent. */
    public interface Backend {
        /** Calls a bridge method and waits for its result; bridge errors as {@link RpcException}. */
        JsonElement call(String method, JsonObject params) throws RpcException;

        /** Server instructions: the core rules first, then the notes index. */
        String instructions();
    }

    /** What the connection asked for in its URL. */
    public record Settings(Profile profile, boolean images, String serverName, String serverVersion) {}

    /** An HTTP reply: status and a JSON body, or no body (202). */
    public record Reply(int status, JsonObject body) {}

    /**
     * A parsed request. {@link #reply} is set when it is answered without a bridge call (errors, handshakes,
     * notifications); otherwise {@link #call} is the bridge call that {@link #finish} makes.
     */
    public static final class Prepared {
        final JsonElement id;
        final boolean modern;
        final String method;
        final Tools.Call call;
        final Reply reply;

        private Prepared(JsonElement id, boolean modern, String method, Tools.Call call, Reply reply) {
            this.id = id;
            this.modern = modern;
            this.method = method;
            this.call = call;
            this.reply = reply;
        }

        /** The bridge method a {@code tools/call} will make (to pick the long-poll pool), or null. */
        public String bridgeMethod() {
            return call == null ? null : call.method();
        }

        public Reply reply() {
            return reply;
        }
    }

    /** Parse and check one POST; {@code headers} are keyed in lower case. */
    public static Prepared prepare(Map<String, String> headers, byte[] body, Settings settings, Backend backend) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            return done(null, false, 400, error(null, PARSE_ERROR, "Parse error: " + e.getMessage(), null));
        }
        if (parsed.isJsonArray()) {
            return done(null, false, 400, error(null, INVALID_REQUEST, "Batches are not supported: send one message per POST", null));
        }
        if (!parsed.isJsonObject()) return done(null, false, 400, error(null, INVALID_REQUEST, "Expected a JSON-RPC object", null));
        JsonObject msg = parsed.getAsJsonObject();
        JsonElement id = msg.get("id");
        if (!msg.has("method") || !msg.get("method").isJsonPrimitive()) {
            // A response or something else a client must not send.
            return done(id, false, 400, error(id, INVALID_REQUEST, "Expected a request or notification with a method", null));
        }
        String method = msg.get("method").getAsString();
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
        if (id == null || id.isJsonNull()) {
            // notifications/initialized and any other notification: accepted, nothing to answer.
            return new Prepared(null, false, method, null, new Reply(202, null));
        }
        JsonObject meta = params.has("_meta") && params.get("_meta").isJsonObject() ? params.getAsJsonObject("_meta") : null;
        String metaVersion = meta != null && meta.has(META_VERSION) && meta.get(META_VERSION).isJsonPrimitive()
                ? meta.get(META_VERSION).getAsString() : null;
        boolean modern = !method.equals("initialize") && (metaVersion != null || method.equals("server/discover"));

        if (modern) {
            Reply bad = checkModernHeaders(headers, id, method, params, metaVersion);
            if (bad != null) return new Prepared(id, true, method, null, bad);
            if (!MODERN.equals(metaVersion)) {
                if (!LEGACY.contains(metaVersion)) {
                    JsonObject data = new JsonObject();
                    data.add("supported", strings(SUPPORTED));
                    data.addProperty("requested", metaVersion);
                    return done(id, true, 400, error(id, UNSUPPORTED_VERSION, "Unsupported protocol version", data));
                }
                // A legacy version in per-request metadata: serve it with that revision's result shapes.
                modern = false;
            }
        }

        switch (method) {
            case "initialize" -> {
                String asked = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : LEGACY.get(0);
                JsonObject r = new JsonObject();
                r.addProperty("protocolVersion", LEGACY.contains(asked) ? asked : LEGACY.get(0));
                r.add("capabilities", capabilities());
                r.add("serverInfo", serverInfo(settings));
                r.addProperty("instructions", backend.instructions());
                return done(id, false, 200, result(id, r));
            }
            case "server/discover" -> {
                JsonObject r = new JsonObject();
                r.add("supportedVersions", strings(SUPPORTED));
                r.add("capabilities", capabilities());
                r.addProperty("instructions", backend.instructions());
                cacheable(r);
                return done(id, true, 200, result(id, modernResult(r, settings)));
            }
            case "ping" -> {
                JsonObject r = new JsonObject();
                return done(id, modern, 200, result(id, modern ? modernResult(r, settings) : r));
            }
            case "tools/list" -> {
                JsonObject r = new JsonObject();
                r.add("tools", Tools.list(settings.profile()));
                if (modern) cacheable(r);
                return done(id, modern, 200, result(id, modern ? modernResult(r, settings) : r));
            }
            case "tools/call" -> {
                String name = params.has("name") && params.get("name").isJsonPrimitive() ? params.get("name").getAsString() : null;
                if (name == null || !Tools.exists(settings.profile(), name)) {
                    return done(id, modern, modern ? 400 : 200, error(id, INVALID_PARAMS, "Unknown tool: " + name, null));
                }
                JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                        ? params.getAsJsonObject("arguments") : new JsonObject();
                try {
                    return new Prepared(id, modern, method, Tools.route(name, args), null);
                } catch (Tools.ToolError e) {
                    return done(id, modern, 200, result(id, toolResult(e.getMessage(), modern, settings)));
                }
            }
            default -> {
                // Resources and prompts are not used (plan 1.2): an unknown method.
                return done(id, modern, modern ? 404 : 200, error(id, METHOD_NOT_FOUND, "Method not found: " + method, null));
            }
        }
    }

    /** Make the bridge call of a prepared {@code tools/call} and build its result. */
    public static Reply finish(Prepared p, Settings settings, Backend backend) {
        if (p.reply != null) return p.reply;
        JsonObject r;
        try {
            r = content(backend.call(p.call.method(), p.call.params()), settings.images());
            r.addProperty("isError", false);
        } catch (RpcException e) {
            r = toolError(e.code() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            r = toolError("internal: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return new Reply(200, result(p.id, p.modern ? modernResult(r, settings) : r));
    }

    /**
     * A bridge result as MCP content: an image result gives the PNG as image content and the rest as text, so
     * clients without vision still read the legend. With {@code images} off, only the text, with a note.
     */
    static JsonObject content(JsonElement result, boolean images) {
        JsonArray content = new JsonArray();
        JsonElement rest = result;
        if (result != null && result.isJsonObject() && result.getAsJsonObject().has("png_base64")) {
            JsonObject copy = result.getAsJsonObject().deepCopy();
            String png = copy.remove("png_base64").getAsString();
            if (images) {
                JsonObject image = new JsonObject();
                image.addProperty("type", "image");
                image.addProperty("data", png);
                image.addProperty("mimeType", "image/png");
                content.add(image);
            } else {
                copy.addProperty("image", "left out: this connection asked for no images (images=false)");
            }
            rest = copy;
        }
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", PRETTY.toJson(rest));
        content.add(text);
        JsonObject out = new JsonObject();
        out.add("content", content);
        return out;
    }

    private static Reply checkModernHeaders(Map<String, String> headers, JsonElement id, String method, JsonObject params, String metaVersion) {
        String version = headers.get("mcp-protocol-version");
        if (version == null) return mismatch(id, "Missing MCP-Protocol-Version header");
        if (metaVersion != null && !version.equals(metaVersion)) {
            return mismatch(id, "MCP-Protocol-Version header " + version + " does not match _meta " + metaVersion);
        }
        String headerMethod = headers.get("mcp-method");
        if (headerMethod == null) return mismatch(id, "Missing Mcp-Method header");
        if (!headerMethod.equals(method)) return mismatch(id, "Mcp-Method header " + headerMethod + " does not match body " + method);
        if (method.equals("tools/call") || method.equals("resources/read") || method.equals("prompts/get")) {
            String headerName = headers.get("mcp-name");
            if (headerName == null) return mismatch(id, "Missing Mcp-Name header");
            String decoded = decodeHeader(headerName);
            JsonElement bodyName = params.get(method.equals("resources/read") ? "uri" : "name");
            String expected = bodyName != null && bodyName.isJsonPrimitive() ? bodyName.getAsString() : null;
            if (decoded == null || !decoded.equals(expected)) {
                return mismatch(id, "Mcp-Name header does not match body value " + expected);
            }
        }
        if (metaVersion == null) return mismatch(id, "Missing _meta " + META_VERSION);
        return null;
    }

    /** {@code =?base64?...?=} sentinel values are decoded; others are taken as they are. Null if invalid. */
    static String decodeHeader(String value) {
        if (value.startsWith("=?base64?") && value.endsWith("?=") && value.length() >= 11) {
            try {
                return new String(Base64.getDecoder().decode(value.substring(9, value.length() - 2)), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return value;
    }

    private static Reply mismatch(JsonElement id, String message) {
        return new Reply(400, error(id, HEADER_MISMATCH, "Header mismatch: " + message, null));
    }

    private static JsonObject capabilities() {
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        JsonObject c = new JsonObject();
        c.add("tools", tools);
        return c;
    }

    private static JsonObject serverInfo(Settings s) {
        JsonObject info = new JsonObject();
        info.addProperty("name", s.serverName());
        info.addProperty("title", "Almighty");
        info.addProperty("version", s.serverVersion());
        return info;
    }

    private static JsonObject modernResult(JsonObject r, Settings s) {
        r.addProperty("resultType", "complete");
        JsonObject meta = r.has("_meta") ? r.getAsJsonObject("_meta") : new JsonObject();
        meta.add(META_SERVER, serverInfo(s));
        r.add("_meta", meta);
        return r;
    }

    /** List results may be cached for a minute, per authorization (they differ by profile and, later, by rights). */
    private static void cacheable(JsonObject r) {
        r.addProperty("ttlMs", 60_000);
        r.addProperty("cacheScope", "private");
    }

    private static JsonObject toolResult(String message, boolean modern, Settings s) {
        JsonObject r = toolError(message);
        return modern ? modernResult(r, s) : r;
    }

    private static JsonObject toolError(String message) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", message);
        JsonArray content = new JsonArray();
        content.add(text);
        JsonObject r = new JsonObject();
        r.add("content", content);
        r.addProperty("isError", true);
        return r;
    }

    private static Prepared done(JsonElement id, boolean modern, int status, JsonObject body) {
        return new Prepared(id, modern, null, null, new Reply(status, body));
    }

    static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject out = new JsonObject();
        out.addProperty("jsonrpc", "2.0");
        out.add("id", id);
        out.add("result", result);
        return out;
    }

    static JsonObject error(JsonElement id, int code, String message, JsonObject data) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        if (data != null) err.add("data", data);
        JsonObject out = new JsonObject();
        out.addProperty("jsonrpc", "2.0");
        if (id != null) out.add("id", id);
        out.add("error", err);
        return out;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(v -> a.add(new JsonPrimitive(v)));
        return a;
    }
}
