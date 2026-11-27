package ua.zentix.almighty.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ua.zentix.almighty.bridge.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Declarative rules (plan 4.12): conditions on event fields ({@code when}) and actions ({@code then}) instead of
 * Groovy, for holders of {@code almighty.rules}. Event fields are those of {@code Json.fields(event)} (what a rule
 * without a script writes to the feed); a path is dotted ({@code entity.name}, {@code pos.1}).
 * <ul>
 * <li>{@code when}: {@code {"path": value}} (equal), or {@code {"path": {"ne"|"lt"|"gt"|"in"|"contains"|"exists": x}}};
 * all must hold.</li>
 * <li>{@code then}: a list of {@code "cancel"}, {@code "emit"}, {@code {"say": text, "to"?: name, "style"?}},
 * {@code {"command": text}}. {@code {path}} in say text is the field's text; in {@code to} and commands only values
 * of letters, digits and {@code _ : . -} are filled in (no command injection through chat text or names).</li>
 * </ul>
 * Pure: no world access, unit-tested.
 */
public final class Declarative {
    private Declarative() {}

    static final int MAX_ACTIONS = 8;
    static final int MAX_CONDITIONS = 16;
    static final int MAX_TEXT = 256;
    private static final Pattern FIELD = Pattern.compile("\\{([A-Za-z0-9_.]{1,64})}");
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_:.\\-]{1,64}");
    private static final Set<String> OPS = Set.of("ne", "lt", "gt", "in", "contains", "exists");

    /** Who a rule acts for: an agent unasked ({@code player} null), a player's request, or the owner ({@code agent} null). */
    public record Creator(String agent, UUID player, String name) {
        public static final Creator OWNER = new Creator(null, null, null);

        public JsonObject json() {
            JsonObject o = new JsonObject();
            if (agent != null) o.addProperty("agent", agent);
            if (player != null) o.addProperty("player", player.toString());
            if (name != null) o.addProperty("name", name);
            return o;
        }

        public static Creator from(JsonElement e) {
            if (e == null || !e.isJsonObject()) return OWNER;
            JsonObject o = e.getAsJsonObject();
            return new Creator(o.has("agent") ? o.get("agent").getAsString() : null,
                    o.has("player") ? UUID.fromString(o.get("player").getAsString()) : null,
                    o.has("name") ? o.get("name").getAsString() : null);
        }

        public String describe() {
            if (agent == null) return "the owner";
            return player == null ? "agent " + agent + " (unasked)" : name + " (through agent " + agent + ")";
        }
    }

    public record Action(String kind, String text, String to, String style) {}

    public record Decl(JsonObject when, List<Action> then) {
        public boolean cancels() {
            return then.stream().anyMatch(a -> a.kind.equals("cancel"));
        }

        /** Whether any say action speaks to everyone for this creator (see {@link #speaksToAll}). */
        public boolean saysToAll(Creator c) {
            return then.stream().anyMatch(a -> a.kind.equals("say") && speaksToAll(a.to, a.style, c));
        }

        public List<Action> actions(String kind) {
            return then.stream().filter(a -> a.kind.equals(kind)).toList();
        }

        public boolean commands() {
            return then.stream().anyMatch(a -> a.kind.equals("command"));
        }
    }

    /** Whether a say speaks to everyone: anything but a chat line to the creator player themself (case-insensitive). */
    public static boolean speaksToAll(String to, String style, Creator c) {
        return to == null || !style.equals("chat") || c.name() == null || !to.equalsIgnoreCase(c.name());
    }

    /** Whether events of this type have a place in the world (a block's, or an entity's): cancels can be zone-limited. */
    public static boolean placed(Class<?> type) {
        return ua.zentix.almighty.platform.Platform.get().events().placed(type);
    }

    /** Null when neither is given (a rule that writes event fields to the feed, as before). */
    public static Decl parse(JsonElement when, JsonElement then) throws RpcException {
        if (when == null && then == null) return null;
        JsonObject w = new JsonObject();
        if (when != null) {
            if (!when.isJsonObject()) throw RpcException.badRequest("when: an object of field conditions, such as {\"entity.name\": \"Steve\"}");
            w = when.getAsJsonObject();
            if (w.size() > MAX_CONDITIONS) throw RpcException.badRequest("when: at most " + MAX_CONDITIONS + " conditions");
            for (var e : w.entrySet()) {
                if (!e.getKey().matches("[A-Za-z0-9_.]{1,64}")) throw RpcException.badRequest("when: field path " + e.getKey());
                if (e.getValue().isJsonObject()) {
                    JsonObject c = e.getValue().getAsJsonObject();
                    if (c.size() != 1 || !OPS.contains(c.keySet().iterator().next())) {
                        throw RpcException.badRequest("when." + e.getKey() + ": a value, or one of " + String.join(", ", new java.util.TreeSet<>(OPS)));
                    }
                }
            }
        }
        List<Action> actions = new ArrayList<>();
        JsonArray list;
        if (then == null) {
            list = new JsonArray();
            list.add("emit");
        } else if (then.isJsonArray()) {
            list = then.getAsJsonArray();
        } else {
            list = new JsonArray();
            list.add(then);
        }
        if (list.isEmpty() || list.size() > MAX_ACTIONS) throw RpcException.badRequest("then: 1 to " + MAX_ACTIONS + " actions");
        for (JsonElement a : list) {
            if (a.isJsonPrimitive() && Set.of("cancel", "emit").contains(a.getAsString())) {
                actions.add(new Action(a.getAsString(), null, null, null));
                continue;
            }
            if (!a.isJsonObject()) throw RpcException.badRequest("then: \"cancel\", \"emit\", {\"say\": text}, {\"command\": text}");
            JsonObject o = a.getAsJsonObject();
            if (o.has("say")) {
                String text = string(o, "say");
                String style = o.has("style") ? string(o, "style").toLowerCase(Locale.ROOT) : "chat";
                if (!Set.of("chat", "title", "subtitle", "actionbar").contains(style)) throw RpcException.badRequest("say style: chat, title, subtitle or actionbar");
                actions.add(new Action("say", text, o.has("to") ? string(o, "to") : null, style));
            } else if (o.has("command")) {
                actions.add(new Action("command", string(o, "command"), null, null));
            } else {
                throw RpcException.badRequest("then: \"cancel\", \"emit\", {\"say\": text}, {\"command\": text}");
            }
        }
        return new Decl(w, List.copyOf(actions));
    }

    private static String string(JsonObject o, String key) throws RpcException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || e.getAsString().isBlank() || e.getAsString().length() > MAX_TEXT) {
            throw RpcException.badRequest(key + ": text of 1 to " + MAX_TEXT + " characters");
        }
        return e.getAsString();
    }

    /** The field at a dotted path (array elements by index); null if absent. */
    static JsonElement path(JsonObject fields, String path) {
        JsonElement at = fields;
        for (String part : path.split("\\.")) {
            if (at == null) return null;
            if (at.isJsonObject()) at = at.getAsJsonObject().get(part);
            else if (at.isJsonArray() && part.matches("\\d{1,3}") && Integer.parseInt(part) < at.getAsJsonArray().size()) {
                at = at.getAsJsonArray().get(Integer.parseInt(part));
            } else return null;
        }
        return at == null || at.isJsonNull() ? null : at;
    }

    public static boolean matches(JsonObject when, JsonObject fields) {
        for (var e : when.entrySet()) {
            JsonElement v = path(fields, e.getKey()), want = e.getValue();
            if (!want.isJsonObject()) {
                if (!same(v, want)) return false;
                continue;
            }
            var c = want.getAsJsonObject().entrySet().iterator().next();
            JsonElement x = c.getValue();
            boolean ok = switch (c.getKey()) {
                case "ne" -> !same(v, x);
                case "lt" -> number(v) != null && number(x) != null && number(v) < number(x);
                case "gt" -> number(v) != null && number(x) != null && number(v) > number(x);
                case "in" -> x.isJsonArray() && x.getAsJsonArray().asList().stream().anyMatch(y -> same(v, y));
                case "contains" -> v != null && (v.isJsonArray() ? v.getAsJsonArray().asList().stream().anyMatch(y -> same(y, x))
                        : v.isJsonPrimitive() && x.isJsonPrimitive() && v.getAsString().contains(x.getAsString()));
                case "exists" -> (v != null) == (x.isJsonPrimitive() && x.getAsJsonPrimitive().isBoolean() && x.getAsBoolean());
                default -> false;
            };
            if (!ok) return false;
        }
        return true;
    }

    private static Double number(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : null;
    }

    private static boolean same(JsonElement a, JsonElement b) {
        if (a == null) return b == null || b.isJsonNull();
        if (number(a) != null && number(b) != null) return number(a).doubleValue() == number(b).doubleValue();
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) return a.getAsString().equals(b.getAsString());
        return a.equals(b);
    }

    /**
     * Fill {@code {path}} with field values. {@code safe}: only short values of letters, digits and {@code _ : . -}
     * (names, ids, numbers), else {@link Unsafe}; otherwise any value as plain text.
     */
    public static String fill(String template, JsonObject fields, boolean safe) throws Unsafe {
        Matcher m = FIELD.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            JsonElement v = path(fields, m.group(1));
            String s = v == null ? "" : v.isJsonPrimitive() ? v.getAsString() : v.toString();
            if (safe && !SAFE.matcher(s).matches()) throw new Unsafe(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(s));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A field value that cannot go into a command or a player name. */
    public static final class Unsafe extends Exception {
        public Unsafe(String field) {
            super("field " + field + " is not a plain name, id or number: not filled into a command");
        }
    }
}
