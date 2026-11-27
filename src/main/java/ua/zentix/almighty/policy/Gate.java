package ua.zentix.almighty.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.Dispatch;
import ua.zentix.almighty.bridge.RpcException;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The gate (plan 4.2–4.3): the check at the choke point that decides, for every call, which node it needs and
 * whether the rights it runs with hold that node. Rights come from who asked, never from a parameter:
 * <ul>
 * <li>no ticket ({@code for} absent): the agent's trust floor;</li>
 * <li>a ticket of a player's addressed message: that player's rights, capped by the agent's ceiling.</li>
 * </ul>
 * A ticket therefore never raises anything above what the requester could do. Refusals are 403 {@code policy}
 * with the node and an {@code allowed} list. In-process calls ({@link Caller#INTERNAL}) are not gated.
 */
public final class Gate implements Dispatch.Check {
    // Every lookup the gate makes runs on bridge threads: bot names, ops and online players come from snapshots that
    // the server thread refreshes ({@link Nodes#refresh}), never from the server's own unsynchronized lists.
    /** Whether a player (UUID; online or not) holds a node: the permission API, or the role ladder without a plugin. */
    @FunctionalInterface
    public interface Holder {
        boolean holds(UUID player, Node node);
    }

    private final Policies policies;
    private final Tickets tickets;
    private final Holder holder;
    private final Predicate<String> isBot;

    public Gate(Policies policies, Tickets tickets, Holder holder, Predicate<String> isBot) {
        this.policies = policies;
        this.tickets = tickets;
        this.holder = holder;
        this.isBot = isBot;
    }

    /** A parameter present and not JSON null (as {@code Args.has}). */
    static boolean has(JsonObject p, String key) {
        JsonElement e = p.get(key);
        return e != null && !e.isJsonNull();
    }

    /** A string parameter, or null if absent; 400 if it is not a string (never a 500 from a malformed call). */
    static String str(JsonObject p, String key) throws RpcException {
        if (!has(p, key)) return null;
        JsonElement e = p.get(key);
        if (!e.isJsonPrimitive()) throw RpcException.badRequest(key + ": expected a string");
        return e.getAsString();
    }

    /**
     * The node a call needs; null for a method without one (refused: deny by default). {@code say} starts at
     * {@code speak.all}: only a reply to the requester alone, checked with the rights ({@link #check}), needs less.
     */
    public static Node node(String method, JsonObject p, Predicate<String> isBot) throws RpcException {
        return switch (method) {
            case "status", "map", "look", "blocks", "ships", "jobs", "job", "areas", "bots", "bot", "rules", "pending" -> Node.OBSERVE_WORLD;
            case "entities" -> {
                // Players are among entities: listing them needs observe.players unless the call asks for another type.
                String type = str(p, "type");
                yield type == null || type.equals("player") || type.equals("minecraft:player") ? Node.OBSERVE_PLAYERS : Node.OBSERVE_WORLD;
            }
            case "see" -> {
                String name = str(p, "name");
                // A bot's eyes are the agent's own; another player's view is theirs.
                yield (name != null && !isBot.test(name)) || has(p, "uuid") ? Node.OBSERVE_PLAYERS : Node.OBSERVE_WORLD;
            }
            case "players", "player" -> Node.OBSERVE_PLAYERS;
            case "notes" -> has(p, "text") ? Node.NOTES_WRITE : Node.KNOWLEDGE;
            case "events", "event.types" -> Node.KNOWLEDGE;
            case "say" -> Node.SPEAK_ALL;
            case "build", "undo", "cancel" -> Node.BUILD;
            case "area.prepare", "area.release" -> Node.AREA;
            case "command", "function" -> Node.COMMAND;
            case "script" -> Node.ENGINE;
            case "rule.add" -> has(p, "script") ? Node.ENGINE : Node.RULES;
            case "rule.remove" -> Node.RULES;
            case "bot.spawn" -> {
                String mode = str(p, "gamemode");
                boolean unmarked = has(p, "marker") && !(p.get("marker").isJsonPrimitive() && p.get("marker").getAsJsonPrimitive().isBoolean()
                        && p.get("marker").getAsBoolean());
                yield has(p, "skin") || unmarked || mode != null && Set.of("creative", "spectator").contains(mode.toLowerCase(java.util.Locale.ROOT))
                        ? Node.BOTS_ADMIN : Node.BOTS;
            }
            case "bot.act" -> payload(p) ? Node.BOTS_ADMIN : Node.BOTS;
            case "bot.remove" -> Node.BOTS;
            case "audit" -> Node.AUDIT;
            default -> null;
        };
    }

    /** A private reply to the requester alone (chat or actionbar): the only {@code say} below {@code speak.all}. */
    static boolean replyToRequester(JsonObject p, Tickets.Ticket t) throws RpcException {
        if (t == null || !has(p, "to")) return false;
        String style = str(p, "style");
        if (style != null && !Set.of("chat", "actionbar").contains(style.toLowerCase(java.util.Locale.ROOT))) return false;
        JsonElement to = p.get("to");
        if (to.isJsonPrimitive()) return to.getAsString().equalsIgnoreCase(t.name());
        if (to.isJsonArray() && to.getAsJsonArray().size() == 1 && to.getAsJsonArray().get(0).isJsonPrimitive()) {
            return to.getAsJsonArray().get(0).getAsString().equalsIgnoreCase(t.name());
        }
        return false;
    }

    private static boolean payload(JsonObject p) {
        if (!(p.get("actions") instanceof JsonArray actions)) return false;
        for (JsonElement a : actions) if (a instanceof JsonObject o && o.has("payload")) return true;
        return false;
    }

    /** The rights a call runs with. */
    public record Rights(String agent, Role ceiling, Role floor, Tickets.Ticket ticket) {
        /** The role this call acts with on the ladder (requester capped by the ceiling, or the floor). */
        public Role effective() {
            return ticket == null ? floor : Role.min(Nodes.role(ticket.player()), ceiling);
        }

        /** Whether these rights hold {@code node}; the requester's own grant is checked by {@code holder}. */
        boolean holds(Node node, Holder holder) {
            if (!ceiling.atLeast(node.defaultRole())) return false;
            if (ticket == null) return floor.atLeast(node.defaultRole());
            return holder.holds(ticket.player(), node);
        }
    }

    public Rights rights(Caller caller, JsonObject params) throws RpcException {
        Policy policy = policies.current();
        String agent = caller.name();
        Tickets.Ticket ticket = null;
        if (params.has("for")) {
            if (!params.get("for").isJsonPrimitive()) throw RpcException.badRequest("for: the ticket of the player's message");
            ticket = tickets.check(params.get("for").getAsString(), agent);
        }
        return new Rights(agent, policy.ceiling(agent), policy.floor(agent), ticket);
    }

    @Override
    public void check(Caller caller, String method, JsonObject params) throws RpcException {
        if (caller.agent() == null) return;
        Node node = node(method, params, isBot);
        if (node == null) throw new RpcException(403, "policy", "No permission node for " + method + ": refused by default");
        Rights r = rights(caller, params);
        if (method.equals("say") && replyToRequester(params, r.ticket)) node = Node.SPEAK_REPLY;
        if (r.holds(node, holder)) return;
        String as = r.ticket == null ? "unasked, agent " + r.agent + " acts as " + r.floor.id() + " (trust floor)"
                : "this request runs with " + r.ticket.name() + "'s rights";
        if (r.ticket != null && !r.ceiling.atLeast(node.defaultRole())) as = "agent " + r.agent + "'s ceiling is " + r.ceiling.id();
        RpcException e = new RpcException(403, "policy", "Not allowed: " + node.key() + " (" + node.description() + "); " + as);
        e.detail("node", node.key());
        e.detail("allowed", allowed(r));
        throw e;
    }

    /** Refuse unless the call's rights hold {@code node} (checks made inside a method, e.g. another agent's job). */
    public void require(ua.zentix.almighty.bridge.Caller caller, JsonObject params, Node node, String why) throws RpcException {
        if (caller.agent() == null) return;
        Rights r = rights(caller, params);
        if (r.holds(node, holder)) return;
        RpcException e = new RpcException(403, "policy", "Not allowed: " + node.key() + " (" + why + ")");
        e.detail("node", node.key());
        e.detail("allowed", allowed(r));
        throw e;
    }

    /** The nodes these rights hold: the refusal's hint and {@code status} rights. */
    public JsonArray allowed(Rights r) {
        JsonArray out = new JsonArray();
        for (Node n : Node.values()) if (r.holds(n, holder)) out.add(n.key());
        return out;
    }

    /** {@code status} rights: the agent's floor and ceiling, and what a ticket (if given) allows. */
    public JsonObject describe(Caller caller, JsonObject params) throws RpcException {
        Rights r = rights(caller, params);
        JsonObject out = new JsonObject();
        out.addProperty("agent", r.agent);
        out.addProperty("preset", policies.current().preset().id());
        out.addProperty("ceiling", r.ceiling.id());
        out.addProperty("floor", r.floor.id());
        if (r.ticket != null) out.addProperty("for", r.ticket.name());
        out.add("allowed", allowed(r));
        return out;
    }
}
