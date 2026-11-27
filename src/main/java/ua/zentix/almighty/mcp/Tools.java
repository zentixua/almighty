package ua.zentix.almighty.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The MCP tools: definitions (names, descriptions, input schemas, annotations) in
 * {@code assets/almighty/mcp/tools.json}, the one source for every harness and for {@code docs/guide/tools.md}; and
 * how each tool call becomes one bridge method call, with the argument checks the Python adapter made (the bridge
 * would name {@code from}/{@code to} instead of {@code start}/{@code end} and silently ignore extra parameters).
 * Few levers: 14 tools reach every bridge method; {@code call} reaches any of them as is.
 */
public final class Tools {
    static final String RESOURCE = "/assets/almighty/mcp/tools.json";
    /** The compact profile for small-context local models: the rest through notes (skills) and {@code call}. */
    public static final List<String> COMPACT = List.of("status", "events", "view", "act", "notes", "call");

    private static final Map<String, JsonObject> FULL = load();

    private Tools() {}

    /** A tool call that the checks refused: shown to the model as a tool error, not a protocol error. */
    public static final class ToolError extends Exception {
        public ToolError(String message) {
            super(message);
        }
    }

    /** One bridge call: method and parameters (nulls dropped). */
    public record Call(String method, JsonObject params) {}

    private static Map<String, JsonObject> load() {
        try (InputStream in = Tools.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, JsonObject> out = new LinkedHashMap<>();
            for (JsonElement e : root.getAsJsonArray("tools")) out.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Tool definitions for {@code tools/list}, in a fixed order. */
    public static JsonArray list(Profile profile) {
        JsonArray out = new JsonArray();
        if (profile == Profile.FULL) {
            FULL.values().forEach(t -> out.add(t.deepCopy()));
            return out;
        }
        for (String name : COMPACT) out.add(name.equals("act") ? act() : compact(FULL.get(name)));
        return out;
    }

    public static boolean exists(Profile profile, String name) {
        return profile == Profile.FULL ? FULL.containsKey(name) : COMPACT.contains(name);
    }

    public static Set<String> names() {
        return FULL.keySet();
    }

    /** A compact definition: the first sentence of the description only. */
    private static JsonObject compact(JsonObject tool) {
        JsonObject t = tool.deepCopy();
        String d = t.get("description").getAsString();
        int dot = d.indexOf(". ");
        t.addProperty("description", (dot > 0 ? d.substring(0, dot + 1) : d) + " Details: the notes tool.");
        return t;
    }

    /** {@code act} (compact profile only): command, build or say in one tool. */
    private static JsonObject act() {
        return JsonParser.parseString("""
                {"name": "act", "title": "Act in the world",
                 "description": "Change the world. kind command: commands (list of strings). kind build: ops (fill/set/layers) or undo (job id). kind say: text, to (player names). Details: notes {id: \\"almighty:game_master\\"}.",
                 "annotations": {"readOnlyHint": false, "destructiveHint": true, "openWorldHint": false},
                 "inputSchema": {"type": "object", "properties": {
                   "kind": {"type": "string", "enum": ["command", "build", "say"]},
                   "commands": {"type": "array", "items": {"type": "string"}},
                   "ops": {"type": "array", "items": {"type": "object"}}, "undo": {"type": "integer"},
                   "text": {"type": "string"}, "to": {"type": "array", "items": {"type": "string"}},
                   "dimension": {"type": "string"}}, "required": ["kind"]}}""").getAsJsonObject();
    }

    /** The bridge call for a tool call; {@link ToolError} for arguments the tool does not take. */
    public static Call route(String name, JsonObject args) throws ToolError {
        A a = new A(name, args == null ? new JsonObject() : args);
        return switch (name) {
            case "status" -> {
                a.allow("player");
                yield a.has("player") ? call("player", "name", a.get("player")) : call("status");
            }
            case "entities" -> {
                a.allow("center", "radius", "start", "end", "type", "limit", "dimension");
                a.points(3, "center", "start", "end");
                yield call("entities", "center", a.get("center"), "radius", a.get("radius"), "from", a.get("start"),
                        "to", a.get("end"), "type", a.get("type"), "limit", a.get("limit"), "dimension", a.get("dimension"));
            }
            case "command" -> {
                a.allow("commands", "lines", "args", "pos", "dimension");
                if (a.has("commands") == a.has("lines")) throw new ToolError("command: exactly one of commands (commands) or lines (function lines)");
                a.points(3, "pos");
                if (a.has("commands")) {
                    if (a.has("args")) throw new ToolError("command: args only goes with lines (function macros)");
                    yield call("command", "commands", a.get("commands"), "pos", a.get("pos"), "dimension", a.get("dimension"));
                }
                yield call("function", "lines", a.get("lines"), "args", a.get("args"), "pos", a.get("pos"), "dimension", a.get("dimension"));
            }
            case "say" -> {
                a.allow("text", "component", "to", "style");
                yield call("say", "text", a.get("text"), "component", a.get("component"), "to", a.get("to"), "style", a.get("style"));
            }
            case "script" -> {
                a.allow("code", "args", "timeout_ms");
                a.need("code");
                yield call("script", "code", a.get("code"), "args", a.get("args"), "timeout_ms", a.get("timeout_ms"));
            }
            case "rule" -> rule(a);
            case "events" -> {
                a.allow("after", "wait", "limit");
                yield call("events", "after", a.get("after"), "wait", a.get("wait"), "limit", a.get("limit"));
            }
            case "view" -> view(a);
            case "build" -> {
                a.allow("ops", "undo", "dimension", "wait");
                if (a.has("ops") == a.has("undo")) throw new ToolError("build: exactly one of ops (build) or undo (job number to undo)");
                if (a.has("undo")) {
                    a.only("build undo", "dimension");
                    yield call("undo", "job", a.get("undo"), "wait", a.get("wait"));
                }
                yield call("build", "ops", a.get("ops"), "dimension", a.get("dimension"), "wait", a.get("wait"));
            }
            case "area" -> area(a);
            case "job" -> {
                a.allow("id", "wait", "cancel");
                boolean cancel = a.bool("cancel");
                boolean wait = a.has("wait") && a.get("wait").getAsDouble() != 0;
                if (!a.has("id")) {
                    if (cancel || wait) throw new ToolError("job: cancel and wait need a job id");
                    yield call("jobs");
                }
                if (cancel) {
                    if (wait) throw new ToolError("job: cancel does not wait; leave out wait");
                    yield call("cancel", "id", a.get("id"));
                }
                yield call("job", "id", a.get("id"), "wait", a.get("wait"));
            }
            case "bot" -> bot(a);
            case "notes" -> {
                a.allow("id", "query", "kind", "text", "offset", "limit");
                if (a.has("text")) {
                    a.need("id");
                    a.only("notes write", "query", "kind", "offset", "limit");
                }
                yield call("notes", "id", a.get("id"), "query", a.get("query"), "kind", a.get("kind"), "text", a.get("text"),
                        "offset", a.get("offset"), "limit", a.get("limit"));
            }
            case "call" -> {
                a.allow("method", "params");
                a.need("method");
                JsonElement params = a.get("params");
                if (params != null && !params.isJsonObject()) throw new ToolError("call: params must be an object");
                yield new Call(a.get("method").getAsString(), params == null ? new JsonObject() : params.getAsJsonObject().deepCopy());
            }
            case "act" -> {
                a.allow("kind", "commands", "ops", "undo", "text", "to", "dimension");
                a.need("kind");
                String kind = a.get("kind").getAsString();
                yield switch (kind) {
                    case "command" -> {
                        a.only("act command", "ops", "undo", "text", "to");
                        a.need("commands");
                        yield call("command", "commands", a.get("commands"), "dimension", a.get("dimension"));
                    }
                    case "build" -> {
                        a.only("act build", "commands", "text", "to");
                        yield route("build", strip(args, "kind", "commands", "text", "to"));
                    }
                    case "say" -> {
                        a.only("act say", "commands", "ops", "undo", "dimension");
                        yield call("say", "text", a.get("text"), "to", a.get("to"));
                    }
                    default -> throw new ToolError("act: kind is command, build or say, not " + kind);
                };
            }
            default -> throw new ToolError("No tool " + name);
        };
    }

    private static Call rule(A a) throws ToolError {
        a.allow("action", "event", "script", "name", "every", "limit", "priority", "canceled", "budget_ms", "persist", "id", "query");
        String action = a.string("action", "list");
        Map<String, List<String>> takes = Map.of(
                "add", List.of("event", "script", "name", "every", "limit", "priority", "canceled", "budget_ms", "persist"),
                "remove", List.of("id", "name"), "list", List.of(), "types", List.of("query"));
        if (!takes.containsKey(action)) throw new ToolError("rule: action is add, remove, list or types, not '" + action + "'");
        List<String> not = new ArrayList<>(List.of("event", "script", "name", "every", "limit", "priority", "canceled", "budget_ms", "persist", "id", "query"));
        not.removeAll(takes.get(action));
        a.only("rule " + action, not.toArray(String[]::new));
        return switch (action) {
            case "add" -> {
                a.need("event");
                JsonObject p = new JsonObject();
                for (String k : takes.get("add")) if (a.has(k)) p.add(k, a.get(k));
                yield new Call("rule.add", p);
            }
            case "remove" -> {
                if (!a.has("id") && !a.has("name")) throw new ToolError("rule remove: needs id or name");
                yield call("rule.remove", "id", a.get("id"), "name", a.get("name"));
            }
            case "list" -> call("rules");
            default -> call("event.types", "query", a.get("query"));
        };
    }

    private static Call view(A a) throws ToolError {
        a.allow("kind", "center", "size", "start", "end", "below", "marks", "direction", "properties", "dimension", "wait",
                "who", "eye", "yaw", "pitch", "image", "width", "height", "fov", "distance", "radius");
        a.need("kind");
        String kind = a.get("kind").getAsString();
        String[] eyeOnly = {"who", "eye", "yaw", "pitch", "width", "height", "fov", "distance", "radius"};
        if (kind.equals("eye")) {
            a.only("view eye", "center", "start", "end", "below", "marks", "direction");
            if (a.has("who") == a.has("eye")) throw new ToolError("view eye: exactly one of who (name or UUID) or eye [x, y, z]");
            JsonObject p = new JsonObject();
            if (a.has("who")) {
                if (a.has("yaw") || a.has("pitch") || a.has("dimension")) {
                    throw new ToolError("view eye: yaw, pitch and dimension only go with the free camera eye");
                }
                String who = a.get("who").getAsString();
                p.addProperty(who.length() == 36 && who.chars().filter(c -> c == '-').count() == 4 ? "uuid" : "name", who);
            } else {
                a.points(3, "eye");
                p.add("at", a.get("eye"));
            }
            for (String k : List.of("yaw", "pitch", "dimension", "image", "width", "height", "fov", "distance", "radius", "wait")) {
                if (a.has(k)) p.add(k, a.get(k));
            }
            return new Call("see", p);
        }
        a.only("view " + kind, eyeOnly);
        switch (kind) {
            case "map" -> {
                a.only("view map", "direction");
                boolean byCenter = a.has("center") && !a.has("start") && !a.has("end");
                boolean byCorners = !a.has("center") && a.has("start") && a.has("end");
                if (!(byCenter || byCorners)) throw new ToolError("view map: center [x, z] or both corners start and end [x, z]");
                a.points(2, "center", "start", "end");
                return call("map", "center", a.get("center"), "size", byCenter ? a.get("size") : null, "from", a.get("start"),
                        "to", a.get("end"), "below", a.get("below"), "marks", a.get("marks"), "dimension", a.get("dimension"), "wait", a.get("wait"));
            }
            case "look" -> {
                a.only("view look", "center", "below", "marks");
                a.need("start", "end", "direction");
                a.points(3, "start", "end");
                return call("look", "from", a.get("start"), "to", a.get("end"), "look", a.get("direction"), "dimension", a.get("dimension"),
                        "wait", a.get("wait"));
            }
            case "blocks" -> {
                a.only("view blocks", "center", "below", "marks", "direction");
                a.need("start", "end");
                a.points(3, "start", "end");
                return call("blocks", "from", a.get("start"), "to", a.get("end"), "properties", a.get("properties"), "dimension", a.get("dimension"));
            }
            default -> throw new ToolError("view: kind is map, look, blocks or eye, not '" + kind + "'");
        }
    }

    private static Call area(A a) throws ToolError {
        a.allow("action", "start", "end", "ttl_seconds", "wait", "id", "dimension");
        String action = a.string("action", "list");
        return switch (action) {
            case "prepare" -> {
                a.only("area prepare", "id");
                a.need("start", "end");
                a.points(2, "start", "end");
                yield call("area.prepare", "from", a.get("start"), "to", a.get("end"), "ttl_seconds", a.get("ttl_seconds"),
                        "wait", a.get("wait"), "dimension", a.get("dimension"));
            }
            case "release" -> {
                a.only("area release", "start", "end", "dimension");
                a.need("id");
                yield call("area.release", "id", a.get("id"));
            }
            case "list" -> {
                a.only("area list", "start", "end", "id", "dimension");
                yield call("areas");
            }
            default -> throw new ToolError("area: action is prepare, release or list, not '" + action + "'");
        };
    }

    private static Call bot(A a) throws ToolError {
        a.allow("action", "name", "actions", "replace", "wait", "pos", "dimension", "yaw", "pitch", "gamemode", "skin", "marker",
                "auto_respawn", "after");
        String action = a.string("action", "list");
        List<String> all = List.of("name", "actions", "pos", "dimension", "yaw", "pitch", "gamemode", "skin", "marker", "auto_respawn", "after");
        Map<String, List<String>> takes = Map.of(
                "spawn", List.of("name", "pos", "dimension", "yaw", "pitch", "gamemode", "skin", "marker", "auto_respawn"),
                "remove", List.of("name"), "list", List.of(), "state", List.of("name", "after"), "act", List.of("name", "actions"));
        if (!takes.containsKey(action)) throw new ToolError("bot: action is spawn, remove, list, state or act, not '" + action + "'");
        List<String> not = new ArrayList<>(all);
        not.removeAll(takes.get(action));
        a.only("bot " + action, not.toArray(String[]::new));
        if (action.equals("list")) return call("bots");
        a.need("name");
        return switch (action) {
            case "spawn" -> {
                a.points(3, "pos");
                JsonObject p = new JsonObject();
                for (String k : takes.get("spawn")) if (a.has(k)) p.add(k, a.get(k));
                yield new Call("bot.spawn", p);
            }
            case "remove" -> call("bot.remove", "name", a.get("name"));
            case "state" -> call("bot", "name", a.get("name"), "after", a.get("after"));
            default -> {
                a.need("actions");
                yield call("bot.act", "name", a.get("name"), "actions", a.get("actions"), "replace", a.get("replace"), "wait", a.get("wait"));
            }
        };
    }

    private static JsonObject strip(JsonObject args, String... keys) {
        JsonObject out = args.deepCopy();
        for (String k : keys) out.remove(k);
        return out;
    }

    private static Call call(String method, Object... kv) {
        JsonObject p = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            JsonElement v = (JsonElement) kv[i + 1];
            if (v != null && !v.isJsonNull()) p.add((String) kv[i], v.deepCopy());
        }
        return new Call(method, p);
    }

    /** Arguments with the adapter's checks: unknown arguments, missing ones, points of the wrong length. */
    private record A(String tool, JsonObject json) {
        boolean has(String k) {
            JsonElement e = json.get(k);
            return e != null && !e.isJsonNull();
        }

        JsonElement get(String k) {
            return has(k) ? json.get(k) : null;
        }

        String string(String k, String fallback) throws ToolError {
            if (!has(k)) return fallback;
            JsonElement e = json.get(k);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new ToolError(tool + ": " + k + " must be a string");
            return e.getAsString();
        }

        boolean bool(String k) throws ToolError {
            if (!has(k)) return false;
            JsonElement e = json.get(k);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new ToolError(tool + ": " + k + " must be true or false");
            return e.getAsBoolean();
        }

        void allow(String... keys) throws ToolError {
            Set<String> ok = Set.of(keys);
            List<String> extra = json.keySet().stream().filter(k -> !ok.contains(k) && has(k)).sorted().toList();
            if (!extra.isEmpty()) throw new ToolError(tool + ": does not take " + String.join(", ", extra));
        }

        void only(String where, String... notTaken) throws ToolError {
            List<String> extra = new ArrayList<>();
            for (String k : notTaken) if (has(k)) extra.add(k);
            if (!extra.isEmpty()) throw new ToolError(where + ": does not take " + String.join(", ", extra));
        }

        void need(String... keys) throws ToolError {
            List<String> missing = new ArrayList<>();
            for (String k : keys) if (!has(k)) missing.add(k);
            if (!missing.isEmpty()) throw new ToolError(tool + ": missing parameter " + String.join(", ", missing));
        }

        void points(int n, String... keys) throws ToolError {
            for (String k : keys) {
                if (!has(k)) continue;
                JsonElement e = json.get(k);
                if (!e.isJsonArray() || e.getAsJsonArray().size() != n) {
                    throw new ToolError(tool + ": " + k + " must be " + (n == 2 ? "[x, z]" : "[x, y, z]"));
                }
            }
        }
    }
}
