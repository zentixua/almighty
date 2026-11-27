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

    /** Shows a waiting confirmation to the people who may decide (in game, on the server thread). */
    @FunctionalInterface
    public interface Prompter {
        void prompt(Confirmations.Pending p);
    }

    private final Policies policies;
    private final Tickets tickets;
    private final Holder holder;
    private final Predicate<String> isBot;
    private Confirmations confirmations;
    private Prompter prompter = p -> {};

    public Gate(Policies policies, Tickets tickets, Holder holder, Predicate<String> isBot) {
        this.policies = policies;
        this.tickets = tickets;
        this.holder = holder;
        this.isBot = isBot;
    }

    /** Turn on human confirmation (step 6): irreversible requests wait for their requester, {@code ask_admin} kinds for an admin. */
    public void confirmations(Confirmations c, Prompter prompter) {
        this.confirmations = c;
        this.prompter = prompter;
    }

    public Confirmations confirmations() {
        return confirmations;
    }

    public Policies policies() {
        return policies;
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
            case "status", "map", "look", "blocks", "ships", "jobs", "job", "areas", "bots", "bot", "rules", "pending", "changes" -> Node.OBSERVE_WORLD;
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
            case "script" -> has(p, "code") || !has(p, "name") ? Node.ENGINE : Node.SCRIPTS_RUN;
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
            case "give" -> Node.GIVE_SELF;
            case "file" -> Node.POLICY;
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
        Confirmations.Pending running = Confirmations.running();
        if (running != null && running.kind == Confirmations.Kind.ADMIN && running.decider != null) {
            // One call an admin approved: their rights, for this call only (nothing is stored or handed out).
            Tickets.Ticket approved = new Tickets.Ticket("approved", running.decider, running.deciderName, agent, 0,
                    System.currentTimeMillis(), Identity.verified(running.decider));
            return new Rights(agent, policy.ceiling(agent), policy.floor(agent), approved);
        }
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
        // Only the gate sets this marker: whatever the agent sent under the name is dropped.
        params.remove(ZONE_ONLY);
        Node node = node(method, params, isBot);
        if (node == null) throw new RpcException(403, "policy", "No permission node for " + method + ": refused by default");
        Rights r = rights(caller, params);
        if (method.equals("say") && replyToRequester(params, r.ticket)) node = Node.SPEAK_REPLY;
        boolean decided = Confirmations.running() != null;
        // Below owner, commands may not plant buttons that run commands as whoever clicks, nor fake the server's prefix
        // (tellraw, title, signs, books, data): a forged "[Almighty] … [Allow]" would run as the admin who clicks it.
        if ((method.equals("command") || method.equals("function")) && r.effective() != Role.OWNER) {
            for (String c : Confirmations.commands(method, params)) {
                String low = c.toLowerCase(java.util.Locale.ROOT);
                if (low.contains("run_command") || low.contains("suggest_command") || c.contains(ua.zentix.almighty.act.Chat.RESERVED)) {
                    throw new RpcException(403, "policy", "Below owner, commands may not carry run_command/suggest_command clicks or "
                            + ua.zentix.almighty.act.Chat.RESERVED);
                }
            }
        }
        if (r.holds(node, holder)) {
            // Allowed, but irreversible for others (or listed in confirm_first): the requester confirms the exact call.
            if (!decided && confirmations != null && r.ticket != null && Nodes.role(r.ticket.player()) != Role.OWNER
                    && Confirmations.needsRequester(method, params, r.ticket.name(), r.ticket.player(), policies.current().safety().confirmFirst())) {
                Confirmations.Pending p = confirmations.ask(Confirmations.Kind.REQUESTER, caller, method, params,
                        r.ticket.player(), node, r.ticket.player(), r.ticket.name());
                prompter.prompt(p);
                throw Confirmations.waiting(p, r.ticket.name());
            }
            return;
        }
        // Unasked, above the floor: allowed only inside a zone where the owner lets the agent (agent_may); the method
        // checks its exact box against those zones.
        if (r.ticket == null && r.ceiling.atLeast(node.defaultRole()) && ZONED.contains(method)) {
            for (Policy.Zone z : policies.current().zones().values()) {
                if (z.agentMay().contains(node.id())) {
                    params.addProperty(ZONE_ONLY, true);
                    return;
                }
            }
        }
        // Above the requester's rights: only kinds the owner listed in ask_admin may go to an admin; the rest is refused.
        if (!decided && confirmations != null && r.ticket != null && r.ceiling.atLeast(node.defaultRole())) {
            java.util.Set<String> ask = policies.current().safety().askAdmin();
            boolean listed = false;
            for (String k : Confirmations.kinds(method, params)) if (ask.contains(k)) listed = true;
            if (listed) {
                Confirmations.Pending p = confirmations.ask(Confirmations.Kind.ADMIN, caller, method, params, null, node,
                        r.ticket.player(), r.ticket.name());
                if (p.count == 1) prompter.prompt(p);
                throw Confirmations.waiting(p, "an admin");
            }
        }
        String as = r.ticket == null ? "unasked, agent " + r.agent + " acts as " + r.floor.id() + " (trust floor)"
                : "this request runs with " + r.ticket.name() + "'s rights";
        if (r.ticket != null && !r.ceiling.atLeast(node.defaultRole())) as = "agent " + r.agent + "'s ceiling is " + r.ceiling.id();
        RpcException e = new RpcException(403, "policy", "Not allowed: " + node.key() + " (" + node.description() + "); " + as);
        e.detail("node", node.key());
        e.detail("allowed", allowed(r));
        throw e;
    }

    /** Set by the gate on a call it let through only for an {@code agent_may} zone; the method then checks the box. */
    public static final String ZONE_ONLY = "\u0000zone_only";
    /** Methods whose box is known to the method (and checked there): the only ones an agent_may zone can allow. */
    static final Set<String> ZONED = Set.of("build", "area.prepare", "bot.spawn");

    /**
     * Zones (plan 4.7), checked by the method once it knows the call's box ({@code lo}..{@code hi} in {@code dim}):
     * below owner, a zone that meets the box and denies the node refuses it; a call the gate let through only for an
     * {@code agent_may} zone must lie wholly inside such a zone.
     */
    public void zone(ua.zentix.almighty.bridge.Caller caller, JsonObject params, Node node, String dim, int[] lo, int[] hi) throws RpcException {
        if (caller.agent() == null) return;
        Rights r = rights(caller, params);
        if (r.effective() == Role.OWNER) return;
        String denied = zoneDenies(r.effective(), node, dim, lo, hi);
        if (denied != null) throw new RpcException(403, "policy", denied);
        if (params.has(ZONE_ONLY)) {
            for (Policy.Zone z : policies.current().zones().values()) {
                if (z.agentMay().contains(node.id()) && z.holds(dim, lo, hi)) return;
            }
            throw new RpcException(403, "policy", "Not allowed: " + node.key() + " outside the zones where the agent may (agent_may)");
        }
    }

    /** Why a zone denies {@code node} over this box for these rights; null if none does (or for the owner). */
    public String zoneDenies(Role effective, Node node, String dim, int[] lo, int[] hi) {
        if (effective == Role.OWNER) return null;
        for (Policy.Zone z : policies.current().zones().values()) {
            if (z.denies(node) && z.meets(dim, lo, hi)) return "Zone " + z.name() + " denies " + node.key() + " here";
        }
        return null;
    }

    /** A rule creator's role now (plan 4.12): the agent's floor unasked, else the player's role under the ceiling. */
    public Role role(String agent, java.util.UUID player) {
        Policy p = policies.current();
        return player == null ? p.floor(agent) : Role.min(Nodes.role(player), p.ceiling(agent));
    }

    /** Whether a rule creator holds {@code node} now (the same answer a call with their ticket would get). */
    public boolean holds(String agent, java.util.UUID player, Node node) {
        Policy p = policies.current();
        return new Rights(agent, p.ceiling(agent), p.floor(agent), player == null ? null
                : new Tickets.Ticket("rule", player, null, agent, 0, 0, Identity.verified(player))).holds(node, holder);
    }

    /** The node a call needs (for methods that check zones themselves). */
    public Node nodeOf(String method, JsonObject params) throws RpcException {
        return node(method, params, isBot);
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
