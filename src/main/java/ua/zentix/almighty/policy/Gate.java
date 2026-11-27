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

    /** The node a call needs; null for a method without one (refused: deny by default). */
    public static Node node(String method, JsonObject p, Predicate<String> isBot) {
        return switch (method) {
            case "status", "map", "look", "blocks", "entities", "ships", "jobs", "job", "areas", "bots", "bot", "rules" -> Node.OBSERVE_WORLD;
            case "see" -> {
                String name = p.has("name") ? p.get("name").getAsString() : null;
                // A bot's eyes are the agent's own; another player's view is theirs.
                yield (name != null && !isBot.test(name)) || p.has("uuid") ? Node.OBSERVE_PLAYERS : Node.OBSERVE_WORLD;
            }
            case "players", "player" -> Node.OBSERVE_PLAYERS;
            case "notes" -> p.has("text") ? Node.NOTES_WRITE : Node.KNOWLEDGE;
            case "events", "event.types" -> Node.KNOWLEDGE;
            case "say" -> p.has("to") && !style(p, "title", "subtitle") ? Node.SPEAK_REPLY : Node.SPEAK_ALL;
            case "build", "undo", "cancel" -> Node.BUILD;
            case "area.prepare", "area.release" -> Node.AREA;
            case "command", "function" -> Node.COMMAND;
            case "script" -> Node.ENGINE;
            case "rule.add" -> p.has("script") ? Node.ENGINE : Node.RULES;
            case "rule.remove" -> Node.RULES;
            case "bot.spawn" -> p.has("skin") || p.has("marker") && !p.get("marker").getAsBoolean()
                    || p.has("gamemode") && Set.of("creative", "spectator").contains(p.get("gamemode").getAsString()) ? Node.BOTS_ADMIN : Node.BOTS;
            case "bot.act" -> payload(p) ? Node.BOTS_ADMIN : Node.BOTS;
            case "bot.remove" -> Node.BOTS;
            case "audit" -> Node.AUDIT;
            default -> null;
        };
    }

    private static boolean style(JsonObject p, String... styles) {
        if (!p.has("style")) return false;
        String s = p.get("style").getAsString();
        for (String x : styles) if (x.equals(s)) return true;
        return false;
    }

    private static boolean payload(JsonObject p) {
        if (!(p.get("actions") instanceof JsonArray actions)) return false;
        for (JsonElement a : actions) if (a instanceof JsonObject o && o.has("payload")) return true;
        return false;
    }

    /** The rights a call runs with. */
    public record Rights(String agent, Role ceiling, Role floor, Tickets.Ticket ticket) {
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
        if (r.holds(node, holder)) return;
        String as = r.ticket == null ? "unasked, agent " + r.agent + " acts as " + r.floor.id() + " (trust floor)"
                : "this request runs with " + r.ticket.name() + "'s rights";
        if (r.ticket != null && !r.ceiling.atLeast(node.defaultRole())) as = "agent " + r.agent + "'s ceiling is " + r.ceiling.id();
        RpcException e = new RpcException(403, "policy", "Not allowed: " + node.key() + " (" + node.description() + "); " + as);
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
