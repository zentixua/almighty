package ua.zentix.almighty.policy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ua.zentix.almighty.bridge.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in deny list: calls and commands known to crash, freeze or stop the server, refused for every agent and every
 * harness (formerly the Claude-only {@code mcp/gm/crash_guard.py}). An accident guard, not security: a determined
 * script can always reach the JVM, which is why the engine is owner-only (plan 4.12). Checked once per bridge call
 * ({@link #check(String, JsonObject)}) and again for every GM command at run time ({@link #command}), so commands that
 * scripts and rules issue are covered too.
 */
public final class DenyList {
    /** Sable ship plots start at x 20 480 000; anything from 20 000 000 is treated as a plot (crash_guard's rule). */
    public static final double PLOT = 20_000_000;
    public static final double TICK_RATE_MAX = 100;

    private record Rule(Pattern pattern, String why) {}

    private static final List<Rule> COMMANDS = List.of(
            new Rule(Pattern.compile("^(stop|reload|save-off)\\b|^save-all\\s+flush\\b", Pattern.CASE_INSENSITIVE),
                    "stops or breaks the server; the owner can run it in the console"),
            new Rule(Pattern.compile("^forceload\\s+add\\b", Pattern.CASE_INSENSITIVE),
                    "forceload add loads chunks synchronously and for good: for a distant place use area.prepare"),
            new Rule(Pattern.compile("^gm\\b", Pattern.CASE_INSENSITIVE),
                    "/gm is a player's own message to the game master; the GM never speaks in a player's name"),
            new Rule(Pattern.compile("^almighty\\b", Pattern.CASE_INSENSITIVE),
                    "agents, tokens and the policy are the owner's: the owner runs /almighty in the console or in game"));
    private static final Pattern TICK_RATE = Pattern.compile("^tick\\s+rate\\s+(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAR_COMMAND = Pattern.compile("^(tp|teleport|spreadplayers|fill|clone|setblock|place|forceload)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.~^])-?\\d+(?:\\.\\d+)?");
    private static final Pattern RUN = Pattern.compile("\\brun\\s+");
    /** Commands that run another command after {@code run}: {@code execute … run}, {@code return run}. */
    private static final Pattern EXECUTE = Pattern.compile("^(execute|return)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MACRO = Pattern.compile("\\$\\((\\w+)\\)");

    private static final List<Rule> SCRIPT = List.of(
            new Rule(Pattern.compile("\\bTicketType\\b|\\baddRegionTicket\\b|\\baddTicket\\b|\\bforceChunk\\b|\\bsetChunkForced\\b"),
                    "chunk tickets from a script crash the server: for a distant place use area.prepare"),
            new Rule(Pattern.compile("\\bgetChunk\\s*\\(|\\bgetChunkAt\\s*\\("),
                    "getChunk loads a chunk synchronously (the server freezes): use getChunkNow, and area.prepare for a distant place"),
            new Rule(Pattern.compile("\\bnew\\s+Thread\\b|\\bThread\\s*\\.\\s*start|\\bExecutors\\b|\\bnew\\s+Timer\\b|\\bCompletableFuture\\b"
                    + "|\\.(execute|submit|schedule)\\s*[({]"),
                    "do not hand threads or deferred tasks to the game: react to events with rule.add"),
            new Rule(Pattern.compile("EVENT_BUS|\\.addListener\\s*\\(|\\.register\\s*\\("),
                    "no event bus listeners from a script: react to events with rule.add"),
            new Rule(Pattern.compile("\\bThread\\s*\\.\\s*sleep\\b|(?<![\\w.])sleep\\s*\\(|\\.wait\\s*\\(\\s*\\)"),
                    "sleeping on the server thread freezes the game: wait between calls, and use every in a rule"),
            new Rule(Pattern.compile("\\bSystem\\s*\\.\\s*exit\\b|\\bRuntime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*(exec|exit|halt)\\b|\\bProcessBuilder\\b"),
                    "no JVM exit or processes from the game"));

    private DenyList() {}

    /** Refuse a bridge call before it runs: 403 {@code denied} with the reason. */
    public static void check(String method, JsonObject params) throws RpcException {
        String why = reason(method, params == null ? new JsonObject() : params);
        if (why != null) throw new RpcException(403, "denied", why);
    }

    /** The reason a call is refused, or null. */
    public static String reason(String method, JsonObject p) {
        switch (method) {
            case "command" -> {
                for (String c : strings(p.get("commands"))) {
                    String why = command(c);
                    if (why != null) return why;
                }
            }
            case "function" -> {
                JsonObject args = p.get("args") instanceof JsonObject o ? o : new JsonObject();
                for (String line : logicalLines(strings(p.get("lines")))) {
                    String why = command(expand(line, args));
                    if (why != null) return why;
                }
            }
            case "script" -> {
                return script(string(p.get("code")));
            }
            case "rule.add" -> {
                return script(string(p.get("script")));
            }
            case "build", "area.prepare" -> {
                if (onPlot(p.get("ops")) || onPlot(p.get("from")) || onPlot(p.get("to"))) {
                    return "building and loading areas on Sable plots (x or z from 20,000,000) crashes the server";
                }
            }
            case "bot.spawn" -> {
                if (onPlot(p.get("pos"))) return "a bot on a Sable plot loads chunks there: reach a ship at its position in the world";
            }
            case "bot.act" -> {
                if (p.get("actions") != null && p.get("actions").isJsonArray()) {
                    for (JsonElement a : p.getAsJsonArray("actions")) {
                        if (a instanceof JsonObject o && o.get("chat") != null && o.get("chat").isJsonPrimitive()) {
                            String chat = o.get("chat").getAsString();
                            // A bot's own /gm is its own message (rehearsals), not a player's: allowed.
                            if (chat.startsWith("/") && !chat.matches("(?i)^/gm\\b.*")) {
                                String why = command(chat);
                                if (why != null) return why;
                            }
                        }
                    }
                }
            }
            default -> {
            }
        }
        return null;
    }

    /** The reason a command (with or without {@code /}, {@code execute … run} chains included) is refused, or null. */
    public static String command(String command) {
        for (String seg : segments(command)) {
            for (Rule r : COMMANDS) {
                if (r.pattern.matcher(seg).find()) return "\"" + clip(seg) + "\": " + r.why;
            }
            Matcher m = TICK_RATE.matcher(seg);
            if (m.find() && Double.parseDouble(m.group(1)) > TICK_RATE_MAX) {
                return "/tick rate above " + (int) TICK_RATE_MAX + " crashes the server";
            }
            if (FAR_COMMAND.matcher(seg).find() && onPlot(seg)) {
                return "do not load Sable ship plots (x or z from 20,000,000): reach a ship at its position in the world";
            }
        }
        return null;
    }

    /** The reason script source is refused by the accident guard, or null. */
    public static String script(String code) {
        if (code == null) return null;
        for (Rule r : SCRIPT) {
            if (r.pattern.matcher(code).find()) return r.why;
        }
        return null;
    }

    /** The command and, for {@code execute … run} chains, each command after a {@code run} (chat text is not split). */
    public static List<String> segments(String command) {
        String c = strip(command);
        List<String> out = new ArrayList<>();
        out.add(c);
        if (!EXECUTE.matcher(c).find()) return out;
        String[] parts = RUN.split(c, -1);
        for (int i = 1; i < parts.length; i++) out.add(strip(parts[i]));
        return out;
    }

    private static String strip(String s) {
        s = s.strip();
        while (s.startsWith("/") || s.startsWith("$")) s = s.substring(1).strip();
        return s;
    }

    /** A function line with its {@code $(name)} macro arguments filled in. */
    public static String expand(String line, JsonObject args) {
        Matcher m = MACRO.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            JsonElement v = args.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group(0) : v.isJsonPrimitive() ? v.getAsString() : v.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean onPlot(String text) {
        Matcher m = NUMBER.matcher(text);
        while (m.find()) if (Math.abs(Double.parseDouble(m.group())) >= PLOT) return true;
        return false;
    }

    private static boolean onPlot(JsonElement e) {
        if (e == null || e.isJsonNull()) return false;
        if (e.isJsonPrimitive()) {
            if (e.getAsJsonPrimitive().isBoolean()) return false;
            if (e.getAsJsonPrimitive().isNumber()) return Math.abs(e.getAsDouble()) >= PLOT;
            return onPlot(e.getAsString());
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) if (onPlot(x)) return true;
            return false;
        }
        for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) if (onPlot(x.getValue())) return true;
        return false;
    }

    /**
     * Function lines as the game reads them: a line ending in a backslash continues on the next (leading spaces of
     * both are dropped, as {@code CommandFunction.fromLines} trims), so {@code "sto\\"}, {@code "p"} is one command, {@code stop}. Every check reads these.
     */
    public static List<String> logicalLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        StringBuilder pending = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (pending != null) line = pending + line;
            if (line.endsWith("\\")) {
                pending = new StringBuilder(line.substring(0, line.length() - 1));
                continue;
            }
            pending = null;
            out.add(line);
        }
        if (pending != null) out.add(pending.toString());
        return out;
    }

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonPrimitive()) out.add(e.getAsString());
        else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
        return out;
    }

    private static String string(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String clip(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80);
    }
}
