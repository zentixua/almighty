package ua.zentix.almighty.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import ua.zentix.almighty.feed.GmMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a call will really do, in plain words, made by the server from the parsed call (never from the agent's own
 * summary): the kind of action (the tool or command in words) and its key target (which player, whose inventory,
 * which blocks). Shown next to the agent's optional summary in every confirmation (the decider never reads the
 * summary alone), on the hover of a receipt that shows the summary (lead's note: "Done for you: <summary> [Undo]";
 * a receipt reports what already ran, it decides nothing), and in the receipt line itself when there is no summary;
 * the exact call stays on hover and behind {@code /almighty show}. Pure: no world, any thread.
 */
public final class Facts {
    /** One fact: a lang key ({@code almighty.fact.*}) and its arguments (strings from the call, or nested facts). */
    public record Fact(String key, List<Object> args) {
        Fact(String key, Object... args) {
            this(key, List.of(args));
        }

        /** In the player's language (English or Russian; the client's own translation if it has the mod). */
        public MutableComponent component(String lang) {
            Object[] a = new Object[args.size()];
            for (int i = 0; i < a.length; i++) a[i] = arg(args.get(i), lang);
            return GmMessages.get(key, lang, a).copy();
        }
    }

    /** Parts shown one after another, joined with ", " (the who and where of an execute chain). */
    public record Join(List<Object> parts) {}

    private static Object arg(Object o, String lang) {
        if (o instanceof Fact f) return f.component(lang);
        if (o instanceof Join j) {
            MutableComponent c = Component.empty();
            for (int i = 0; i < j.parts.size(); i++) {
                if (i > 0) c.append(", ");
                Object part = arg(j.parts.get(i), lang);
                if (part instanceof Component pc) c.append(pc);
                else c.append(String.valueOf(part));
            }
            return c;
        }
        return o;
    }

    /**
     * Commands that need no confirmation described, at most (counted after that, "and 2 more"); a command that is
     * why the call waits for a person is always described, first, and identical facts are shown once.
     */
    static final int MAX_COMMANDS = 3;
    /** A word taken from the call (a name, an item, a selector) is clipped to this many characters. */
    static final int MAX_WORD = 48;

    /** Verb keys of commands the server knows by name; any other command is "run the command /x". */
    private static final Map<String, String> VERBS = Map.ofEntries(
            Map.entry("give", "give"), Map.entry("clear", "clear"), Map.entry("kill", "kill"), Map.entry("tp", "tp"),
            Map.entry("teleport", "tp"), Map.entry("effect", "effect"), Map.entry("gamemode", "gamemode"),
            Map.entry("xp", "xp"), Map.entry("experience", "xp"), Map.entry("enchant", "enchant"), Map.entry("summon", "summon"),
            Map.entry("setblock", "blocks"), Map.entry("fill", "blocks"), Map.entry("clone", "clone"), Map.entry("place", "place"),
            Map.entry("fillbiome", "biome"), Map.entry("item", "item"), Map.entry("data", "data"), Map.entry("ban", "ban"),
            Map.entry("ban-ip", "ban"), Map.entry("pardon", "pardon"), Map.entry("kick", "kick"), Map.entry("op", "op"),
            Map.entry("deop", "op"), Map.entry("whitelist", "whitelist"), Map.entry("spawnpoint", "spawnpoint"),
            Map.entry("setworldspawn", "worldspawn"), Map.entry("time", "time"), Map.entry("weather", "weather"),
            Map.entry("function", "function"), Map.entry("schedule", "schedule"), Map.entry("ride", "ride"),
            Map.entry("attribute", "attribute"), Map.entry("advancement", "advancement"), Map.entry("recipe", "recipe"),
            Map.entry("title", "title"), Map.entry("say", "message"), Map.entry("me", "message"), Map.entry("msg", "message"),
            Map.entry("tell", "message"), Map.entry("w", "message"), Map.entry("tellraw", "message"), Map.entry("damage", "damage"),
            Map.entry("difficulty", "difficulty"), Map.entry("gamerule", "gamerule"), Map.entry("worldborder", "worldborder"),
            Map.entry("playsound", "sound"), Map.entry("particle", "sound"), Map.entry("loot", "loot"), Map.entry("spreadplayers", "tp"));

    /**
     * Where each known command names its target (token index after the command name, 1-based) and whether it
     * defaults to the one who runs it; {@code coords}: index of the first coordinate and how many triples.
     */
    private record Shape(int target, boolean selfDefault, int coords, int triples) {}

    private static final Map<String, Shape> SHAPES = Map.ofEntries(
            Map.entry("give", new Shape(1, false, 0, 0)), Map.entry("clear", new Shape(1, true, 0, 0)),
            Map.entry("kill", new Shape(1, true, 0, 0)), Map.entry("effect", new Shape(2, true, 0, 0)),
            Map.entry("gamemode", new Shape(2, true, 0, 0)), Map.entry("xp", new Shape(2, false, 0, 0)),
            Map.entry("experience", new Shape(2, false, 0, 0)), Map.entry("enchant", new Shape(1, false, 0, 0)),
            Map.entry("spawnpoint", new Shape(1, true, 0, 0)), Map.entry("ride", new Shape(1, false, 0, 0)),
            Map.entry("attribute", new Shape(1, false, 0, 0)), Map.entry("advancement", new Shape(2, false, 0, 0)),
            Map.entry("recipe", new Shape(2, false, 0, 0)), Map.entry("title", new Shape(1, false, 0, 0)),
            Map.entry("kick", new Shape(1, false, 0, 0)), Map.entry("ban", new Shape(1, false, 0, 0)),
            Map.entry("ban-ip", new Shape(1, false, 0, 0)), Map.entry("pardon", new Shape(1, false, 0, 0)),
            Map.entry("op", new Shape(1, false, 0, 0)), Map.entry("deop", new Shape(1, false, 0, 0)),
            Map.entry("damage", new Shape(1, false, 0, 0)), Map.entry("msg", new Shape(1, false, 0, 0)),
            Map.entry("tell", new Shape(1, false, 0, 0)), Map.entry("w", new Shape(1, false, 0, 0)),
            Map.entry("tellraw", new Shape(1, false, 0, 0)), Map.entry("setblock", new Shape(0, false, 1, 1)),
            Map.entry("fill", new Shape(0, false, 1, 2)), Map.entry("clone", new Shape(0, false, 1, 3)),
            Map.entry("fillbiome", new Shape(0, false, 1, 2)), Map.entry("summon", new Shape(0, false, 2, 1)),
            Map.entry("setworldspawn", new Shape(0, false, 1, 1)));

    private Facts() {}

    /** The facts of a call; {@code requesterName} stands for {@code @s} (requests run as the requester) and may be null. */
    public static List<Fact> of(String method, JsonObject p, String requesterName) {
        List<Fact> out = new ArrayList<>();
        switch (method) {
            case "command", "function" -> {
                // Comments and blank lines run nothing; what makes the call wait for a person comes first and is
                // never folded into "and N more" (review: a kill behind three harmless lines).
                List<String> commands = new ArrayList<>();
                for (String c : Confirmations.commands(method, p)) {
                    String line = c.strip();
                    if (!line.isEmpty() && !line.startsWith("#")) commands.add(c);
                }
                java.util.LinkedHashMap<String, Fact> risky = new java.util.LinkedHashMap<>(), safe = new java.util.LinkedHashMap<>();
                for (String c : commands) {
                    Fact f = command(c, requesterName);
                    JsonObject one = new JsonObject();
                    one.addProperty("commands", c);
                    boolean asks = Confirmations.needsRequester("command", one, requesterName == null ? "" : requesterName, Set.of());
                    (asks ? risky : safe).putIfAbsent(english(List.of(f)), f);
                }
                out.addAll(risky.values());
                int shown = 0;
                for (Fact f : safe.values()) {
                    if (out.size() >= MAX_COMMANDS) break;
                    out.add(f);
                    shown++;
                }
                if (safe.size() > shown) out.add(new Fact("almighty.fact.more", String.valueOf(safe.size() - shown)));
                if (commands.isEmpty()) out.add(new Fact("almighty.fact.method", method));
            }
            case "build" -> out.add(build(p));
            case "give" -> out.add(new Fact("almighty.fact.give_item", word(str(p, "item", "?")),
                    word(str(p, "count", "1")), who(requesterName)));
            case "script" -> out.add(p.has("name") ? new Fact("almighty.fact.script_named", word(str(p, "name", "?")))
                    : new Fact("almighty.fact.script_code"));
            case "rule.add" -> out.add(new Fact("almighty.fact.rule_add", word(str(p, "event", "?"))));
            case "rule.remove" -> out.add(new Fact("almighty.fact.rule_remove", word(str(p, "name", str(p, "id", "?")))));
            case "role" -> out.add(new Fact("almighty.fact.role", word(str(p, "player", "?")), word(str(p, "role", "?"))));
            case "file" -> out.add(new Fact("almighty.fact.file", word(str(p, "path", "?"))));
            case "undo" -> out.add(p.has("change") ? new Fact("almighty.fact.undo_change", word(str(p, "change", "?")))
                    : new Fact("almighty.fact.undo_job", word(str(p, "job", "?"))));
            case "bot.spawn" -> out.add(new Fact("almighty.fact.bot_spawn", word(str(p, "name", "?"))));
            case "bot.act" -> out.add(new Fact("almighty.fact.bot_act", word(str(p, "name", "?"))));
            case "bot.remove" -> out.add(new Fact("almighty.fact.bot_remove", word(str(p, "name", "?"))));
            case "say" -> out.add(new Fact("almighty.fact.say"));
            case "area.prepare" -> out.add(new Fact("almighty.fact.area", box2(p.get("from"), p.get("to"))));
            default -> out.add(new Fact("almighty.fact.method", word(method)));
        }
        return out;
    }

    /** All facts in one line, joined with "; ". */
    public static MutableComponent component(List<Fact> facts, String lang) {
        MutableComponent c = Component.empty();
        for (int i = 0; i < facts.size(); i++) {
            if (i > 0) c.append("; ");
            c.append(facts.get(i).component(lang));
        }
        return c;
    }

    /** The facts in English as plain text (for agents, audit and owner notices). */
    public static String english(List<Fact> facts) {
        return component(facts, "en_us").getString();
    }

    /**
     * One command. Under {@code execute} the chain is read with {@link #tokens} (quotes and brackets whole, so a
     * " run " inside a name or filter is not a boundary), its {@code as}/{@code at}/{@code on} targets are always
     * shown in words (never clipped), and {@code @s} of the command it runs is whoever the chain picked.
     */
    private static Fact command(String raw, String requesterName) {
        List<String> t = tokens(raw);
        if (t.isEmpty() || !root(t.get(0)).equals("execute")) return plain(raw, requesterName);
        List<Object> who = new ArrayList<>();
        boolean other = false;
        int i = 0;
        while (i < t.size() && root(t.get(i)).equals("execute")) {
            i++;
            while (i < t.size() && !t.get(i).equalsIgnoreCase("run")) {
                String sub = t.get(i).toLowerCase(Locale.ROOT);
                if ((sub.equals("as") || sub.equals("at")) && i + 1 < t.size()) {
                    who.add(new Fact("almighty.fact.exec_" + sub, selector(t.get(i + 1), null)));
                    i += 2;
                } else if (sub.equals("on") && i + 1 < t.size()) {
                    who.add(new Fact("almighty.fact.exec_on", word(t.get(i + 1))));
                    i += 2;
                } else {
                    other = true;
                    i++;
                }
            }
            if (i < t.size()) i++; // run
        }
        if (other) who.add(new Fact("almighty.fact.exec_other"));
        Fact main = i < t.size() ? plain(String.join(" ", t.subList(i, t.size())), null) : new Fact("almighty.fact.exec_nothing");
        return new Fact("almighty.fact.via_execute", main, new Join(who.isEmpty() ? List.of(new Fact("almighty.fact.exec_other")) : who));
    }

    private static Fact plain(String c, String requesterName) {
        List<String> t = tokens(c);
        String root = t.isEmpty() ? "" : root(t.get(0));
        String verb = VERBS.get(root);
        // An unknown command is named as typed (its namespace too: mymod:explode is not explode).
        Fact what = verb == null ? new Fact("almighty.fact.cmd", word(t.isEmpty() ? "" : t.get(0).toLowerCase(Locale.ROOT)))
                : new Fact("almighty.fact.cmd." + verb);
        if (root.equals("tp") || root.equals("teleport")) return teleport(what, t, requesterName);
        String sub = t.size() > 1 ? t.get(1).toLowerCase(Locale.ROOT) : "";
        // Commands whose target sits after a subcommand word.
        if ((root.equals("item") || root.equals("data")) && t.size() > 3 && t.get(2).equalsIgnoreCase("entity")) {
            return new Fact("almighty.fact.target", what, selector(t.get(3), requesterName));
        }
        if (root.equals("loot") && t.size() > 2 && sub.equals("give")) return new Fact("almighty.fact.target", what, selector(t.get(2), requesterName));
        if (root.equals("function") && t.size() > 1) return new Fact("almighty.fact.function", word(t.get(1)));
        if (root.equals("schedule") && t.size() > 2 && sub.equals("function")) return new Fact("almighty.fact.function_later", word(t.get(2)));
        if (root.equals("spreadplayers") && t.size() > 1) return new Fact("almighty.fact.target", what, selector(t.get(t.size() - 1), requesterName));
        if (root.equals("clone") && t.size() > 1 && sub.equals("from")) return what;
        Shape s = SHAPES.get(root);
        if (s == null) return what;
        if (s.target > 0) {
            Object target = t.size() > s.target ? selector(t.get(s.target), requesterName) : s.selfDefault ? who(requesterName) : null;
            if (target != null) {
                if (root.equals("give") && t.size() > 2) {
                    return new Fact("almighty.fact.give_item", word(t.get(2)), t.size() > 3 ? word(t.get(3)) : "1", target);
                }
                return new Fact("almighty.fact.target", what, target);
            }
        }
        if (s.coords > 0 && t.size() >= s.coords + 3 * s.triples) {
            List<String> pos = new ArrayList<>();
            for (int i = 0; i < s.triples; i++) pos.add(word(String.join(" ", t.subList(s.coords + 3 * i, s.coords + 3 * i + 3))));
            Object where = switch (s.triples) {
                case 1 -> pos.get(0);
                case 2 -> new Fact("almighty.fact.box", pos.get(0), pos.get(1));
                default -> new Fact("almighty.fact.box_to", new Fact("almighty.fact.box", pos.get(0), pos.get(1)), pos.get(2));
            };
            if (root.equals("summon") && t.size() > 1) return new Fact("almighty.fact.summon_at", word(t.get(1)), where);
            return new Fact("almighty.fact.where", what, where);
        }
        return what;
    }

    private static Fact teleport(Fact what, List<String> t, String requesterName) {
        if (t.size() == 2) return new Fact("almighty.fact.teleport", who(requesterName), selector(t.get(1), requesterName));
        // tp x y z [rotation]: the one who runs it goes there.
        if (t.size() >= 4 && coordinate(t.get(1))) {
            return new Fact("almighty.fact.teleport", who(requesterName), word(String.join(" ", t.subList(1, 4))));
        }
        if (t.size() >= 3) {
            String to = t.size() >= 5 && !t.get(2).startsWith("@") ? String.join(" ", t.subList(2, 5)) : t.get(2);
            Object dest = to.equals(t.get(2)) ? selector(to, requesterName) : word(to);
            return new Fact("almighty.fact.teleport", selector(t.get(1), requesterName), dest);
        }
        return what;
    }

    private static boolean coordinate(String w) {
        return w.matches("[~^]?-?[0-9]*(\\.[0-9]+)?") && !w.isEmpty();
    }

    private static Fact build(JsonObject p) {
        long[] b = null;
        if (p.get("ops") instanceof JsonArray ops) {
            for (JsonElement e : ops) {
                if (!(e instanceof JsonObject op)) continue;
                long[] o = opBounds(op);
                if (o == null) continue;
                if (b == null) b = o;
                else for (int i = 0; i < 3; i++) {
                    b[i] = Math.min(b[i], o[i]);
                    b[i + 3] = Math.max(b[i + 3], o[i + 3]);
                }
            }
        }
        if (b == null) return new Fact("almighty.fact.cmd.blocks");
        return new Fact("almighty.fact.where", new Fact("almighty.fact.cmd.blocks"),
                b[0] == b[3] && b[1] == b[4] && b[2] == b[5] ? xyz(b, 0)
                        : new Fact("almighty.fact.box", xyz(b, 0), xyz(b, 3)));
    }

    private static long[] opBounds(JsonObject op) {
        try {
            if (op.get("from") instanceof JsonArray f && op.get("to") instanceof JsonArray t && f.size() == 3 && t.size() == 3) {
                long[] b = new long[6];
                for (int i = 0; i < 3; i++) {
                    b[i] = Math.min(f.get(i).getAsLong(), t.get(i).getAsLong());
                    b[i + 3] = Math.max(f.get(i).getAsLong(), t.get(i).getAsLong());
                }
                return b;
            }
            if (op.get("pos") instanceof JsonArray q && q.size() == 3) {
                return new long[] {q.get(0).getAsLong(), q.get(1).getAsLong(), q.get(2).getAsLong(), q.get(0).getAsLong(), q.get(1).getAsLong(), q.get(2).getAsLong()};
            }
            if (op.get("origin") instanceof JsonArray o && o.size() == 3 && op.get("layers") instanceof JsonArray layers) {
                int rows = 0, cols = 0;
                for (JsonElement l : layers) {
                    JsonElement r = l.isJsonObject() ? l.getAsJsonObject().get("rows") : l;
                    if (!(r instanceof JsonArray ra)) continue;
                    rows = Math.max(rows, ra.size());
                    for (JsonElement row : ra) if (row.isJsonPrimitive()) cols = Math.max(cols, row.getAsString().length());
                }
                long x = o.get(0).getAsLong(), y = o.get(1).getAsLong(), z = o.get(2).getAsLong();
                return new long[] {x, y, z, x + Math.max(0, cols - 1), y + Math.max(0, layers.size() - 1), z + Math.max(0, rows - 1)};
            }
        } catch (RuntimeException e) {
            // Not numbers: the build refuses it anyway; no bounds for this op.
        }
        return null;
    }

    private static String xyz(long[] b, int from) {
        return b[from] + " " + b[from + 1] + " " + b[from + 2];
    }

    private static Object box2(JsonElement from, JsonElement to) {
        if (from instanceof JsonArray f && to instanceof JsonArray t && f.size() == 2 && t.size() == 2) {
            return new Fact("almighty.fact.box", word(f.get(0) + " " + f.get(1)), word(t.get(0) + " " + t.get(1)));
        }
        return "?";
    }

    /** A target in plain words: a selector's meaning (with its filter shown as is), else the name as typed. */
    static Object selector(String w, String requesterName) {
        if (w.length() < 2 || w.charAt(0) != '@') return word(w);
        char kind = w.charAt(1);
        String filter = w.length() > 2 ? w.substring(2) : "";
        Object base = switch (kind) {
            case 'a' -> new Fact("almighty.fact.sel.a");
            case 'p' -> new Fact("almighty.fact.sel.p");
            case 'r' -> new Fact("almighty.fact.sel.r");
            case 'e' -> new Fact("almighty.fact.sel.e");
            case 'n' -> new Fact("almighty.fact.sel.n");
            case 's' -> requesterName != null ? word(requesterName) : new Fact("almighty.fact.sel.s");
            default -> null;
        };
        if (base == null) return word(w);
        return filter.isEmpty() ? base : new Fact("almighty.fact.filtered", base, word(w));
    }

    private static Object who(String requesterName) {
        return requesterName != null ? word(requesterName) : new Fact("almighty.fact.sel.s");
    }

    private static String root(String token) {
        String r = token.toLowerCase(Locale.ROOT);
        while (r.startsWith("/") || r.startsWith("$")) r = r.substring(1);
        // Only the game's own namespace: othermod:kill is another mod's command, not kill.
        return r.startsWith("minecraft:") ? r.substring("minecraft:".length()) : r;
    }

    /** Words of a command, keeping {@code [..]}, {@code {..}} and quoted parts whole (a selector with spaces in its filter). */
    static List<String> tokens(String c) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        String s = c.strip();
        while (s.startsWith("/") || s.startsWith("$")) s = s.substring(1);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == '\\' && i + 1 < s.length()) cur.append(s.charAt(++i));
                else if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '"' || ch == '\'') quote = ch;
            else if (ch == '[' || ch == '{') depth++;
            else if ((ch == ']' || ch == '}') && depth > 0) depth--;
            if (Character.isWhitespace(ch) && depth == 0) {
                if (!cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** A word from the call as shown: no formatting codes or line breaks, clipped. */
    static String word(String w) {
        return word(w, MAX_WORD);
    }

    static String word(String w, int max) {
        String s = w.replaceAll("§.?", "").replaceAll("[\\p{Cntrl}]", " ").strip();
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…";
    }

    private static String str(JsonObject p, String k, String fallback) {
        JsonElement e = p.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }
}
