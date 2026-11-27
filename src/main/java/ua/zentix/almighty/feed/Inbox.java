package ua.zentix.almighty.feed;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.bridge.Agent;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.policy.Identity;
import ua.zentix.almighty.policy.Tickets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Messages players address to the game master (plan step 3): the server decides what is addressed
 * ({@link Addressing}), limits each player before anything is minted, routes each message to one agent, mints a
 * ticket for it and tells the player at once when that agent is away. Server thread only.
 */
public final class Inbox {
    /** An agent that made no call for this long is away. */
    static final long PRESENT_MS = 60_000;

    private final MinecraftServer server;
    private final Feed feed;
    private final Tickets tickets;
    /** The agents; null when the bridge is not running (any agent name routes, presence unknown: no away replies). */
    private final Supplier<Agents> agents;
    private final Addressing addressing;
    private final List<Awaiting> awaiting = new ArrayList<>();
    /** Players whose messages reached the GM and await a reply (a reply to everyone answers them). */
    private final Set<UUID> waiting = new HashSet<>();

    private record Awaiting(long seq, String agent, UUID player, long deadline) {}

    /** At most one "away" line per player in this many ms. */
    static final long AWAY_EVERY_MS = 300_000;
    private final java.util.Map<UUID, Long> toldAway = new java.util.HashMap<>();

    public Inbox(MinecraftServer server, Feed feed, Tickets tickets, Supplier<Agents> agents) {
        this.server = server;
        this.feed = feed;
        this.tickets = tickets;
        this.agents = agents;
        List<String> names = new ArrayList<>(GmConfig.FEED_NAMES.get());
        if (names.isEmpty()) names.add(GmConfig.NAME.get());
        this.addressing = new Addressing(names, new ArrayList<>(GmConfig.FEED_TYPO_NAMES.get()), System::currentTimeMillis);
    }

    public Addressing addressing() {
        return addressing;
    }

    /** {@code /gm [@agent] text}; false if the server refused it (and told the player why). */
    public boolean gm(ServerPlayer player, String text) {
        String agent = GmConfig.FEED_DEFAULT_AGENT.get();
        String body = text.strip();
        if (body.startsWith("@")) {
            // "@<agent> …" routes to that agent; "@everyone …" or any other word is just text for the default agent.
            String[] parts = body.substring(1).split("\\s+", 2);
            String named = parts[0].toLowerCase(java.util.Locale.ROOT);
            Agents known = agents.get();
            if (known == null || known.get(named) != null) {
                agent = named;
                body = parts.length > 1 ? parts[1] : "";
            }
        }
        // Below the agent's trust floor (private: anyone but an owner): the agent would act on it with more than the
        // player's rights, so the message never reaches it; the server answers.
        if (!heard(player, agent)) {
            player.sendSystemMessage(GmMessages.get("almighty.gm.not_heard", lang(player)).copy().withStyle(ChatFormatting.GRAY));
            return false;
        }
        // Cooling down after repeated refusals: answered by the server, so the attempts cost no model tokens either.
        if (cooling(player.getUUID()) || !addressing.allow(player.getUUID(), GmConfig.FEED_GM_MESSAGES.get())) {
            player.sendSystemMessage(GmMessages.get("almighty.gm.slow_down", lang(player)).copy().withStyle(ChatFormatting.GRAY));
            return false;
        }
        JsonObject d = FeedListeners.where(player);
        d.addProperty("text", body);
        d.add("untrusted", untrusted("text"));
        deliver(player, "gm", d, Addressing.Via.GM, agent);
        return true;
    }

    /**
     * Whether an agent may hear this player: the player holds every right the agent has unasked (its trust floor;
     * {@link ua.zentix.almighty.policy.Nodes#covers}). A GM bot is the GM's own voice.
     */
    public boolean heard(ServerPlayer player, String agent) {
        if (bot(player)) return true;
        return ua.zentix.almighty.policy.Nodes.covers(player.getUUID(), ua.zentix.almighty.policy.Nodes.policy().floor(agent));
    }

    /** Whether every agent may hear this player (text in the shared feed: chat lines, commands, whispers to bots). */
    public boolean heardByAll(ServerPlayer player) {
        if (bot(player)) return true;
        ua.zentix.almighty.policy.Policy policy = ua.zentix.almighty.policy.Nodes.policy();
        ua.zentix.almighty.policy.Role floor = policy.floor(GmConfig.FEED_DEFAULT_AGENT.get());
        Agents known = agents.get();
        if (known != null) for (String name : known.names()) {
            ua.zentix.almighty.policy.Role f = policy.floor(name);
            if (f.compareTo(floor) > 0) floor = f;
        }
        return ua.zentix.almighty.policy.Nodes.covers(player.getUUID(), floor);
    }

    /**
     * A public chat line: addressed to the GM or not, in the feed, unless its author is below an agent's trust floor
     * (private: anyone but an owner): then no agent reads it (plan 4.2: other players' text is not fed to it).
     */
    public void chat(ServerPlayer player, JsonObject d, String text) {
        if (!heardByAll(player)) return;
        // A GM bot's chat is the GM's own voice, never a message to it.
        Addressing.Via via = bot(player) ? null
                : addressing.chat(player.getUUID(), text, humans().stream().allMatch(p -> p == player));
        if (via == Addressing.Via.NAME && !addressing.allow(player.getUUID(), GmConfig.FEED_GM_MESSAGES.get())) {
            player.sendSystemMessage(GmMessages.get("almighty.gm.slow_down", lang(player)).copy().withStyle(ChatFormatting.GRAY));
            via = null;
        } else if ((via == Addressing.Via.ALONE || via == Addressing.Via.CONVERSATION)
                && !addressing.allowImplicit(player.getUUID(), GmConfig.FEED_GM_MESSAGES.get() * 5)) {
            // Ordinary chat of a solo player or an open conversation: over its own, larger limit it quietly stops
            // reaching the GM; the player is never told off for chatting.
            via = null;
        }
        if (via == null) {
            d.addProperty("addressed", false);
            feed.add("chat", d);
            return;
        }
        deliver(player, "chat", d, via, GmConfig.FEED_DEFAULT_AGENT.get());
    }

    private void deliver(ServerPlayer player, String type, JsonObject d, Addressing.Via via, String agentName) {
        if (via != Addressing.Via.CONVERSATION) addressing.opened(player.getUUID());
        waiting.add(player.getUUID());
        String id = Tickets.newId();
        boolean verified = Identity.verified(player.getUUID());
        d.addProperty("addressed", true);
        d.addProperty("via", via.name().toLowerCase(java.util.Locale.ROOT));
        d.addProperty("agent", agentName);
        d.addProperty("ticket", id);
        long seq = feed.add(type, d);
        tickets.mint(id, player.getUUID(), Mc.name(player.getGameProfile()), agentName, seq, verified);
        Agents known = agents.get();
        // Away replies only for messages the player clearly meant for the GM (/gm or its name).
        if (known == null || (via != Addressing.Via.GM && via != Addressing.Via.NAME)) return;
        Agent agent = known.get(agentName);
        if (agent == null || !agent.present(PRESENT_MS)) away(player);
        else awaiting.add(new Awaiting(seq, agentName, player.getUUID(), System.currentTimeMillis() + GmConfig.FEED_AWAY_SECONDS.get() * 1000L));
    }

    private void away(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = toldAway.get(player.getUUID());
        if (last != null && now - last < AWAY_EVERY_MS) return;
        toldAway.put(player.getUUID(), now);
        player.sendSystemMessage(GmMessages.get("almighty.gm.away", lang(player)).copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    /** Once a second: tell players whose message their agent has not read in time that it is away. */
    public void tick(int tickCount) {
        if (tickCount % 1200 == 0) {
            addressing.prune();
            long nowMs = System.currentTimeMillis();
            cooled.values().removeIf(until -> until < nowMs);
            refusals.values().removeIf(d -> d.isEmpty() || nowMs - d.peekLast() >= COOL_MS);
            long now = System.currentTimeMillis();
            toldAway.values().removeIf(t -> now - t >= AWAY_EVERY_MS);
        }
        if (tickCount % 20 != 0 || awaiting.isEmpty()) return;
        long now = System.currentTimeMillis();
        Agents known = agents.get();
        for (Iterator<Awaiting> it = awaiting.iterator(); it.hasNext(); ) {
            Awaiting a = it.next();
            // Looked up again: a rotated token makes a new Agent object.
            Agent agent = known == null ? null : known.get(a.agent);
            if (agent != null && agent.readUpTo() >= a.seq) {
                it.remove();
            } else if (now >= a.deadline) {
                it.remove();
                ServerPlayer p = server.getPlayerList().getPlayer(a.player);
                if (p != null) away(p);
            }
        }
    }

    /** The GM replied: {@code to} null means everyone, answering every waiting player. */
    public void replied(Collection<ServerPlayer> to) {
        Collection<UUID> players = to == null ? new ArrayList<>(waiting) : to.stream().map(ServerPlayer::getUUID).toList();
        addressing.replied(players);
        players.forEach(waiting::remove);
    }

    /** Refused requests per player: {@value #REFUSALS} within 10 minutes cool their /gm down (plan 4.8 rule 4). */
    static final int REFUSALS = 5;
    static final long COOL_MS = 10 * 60_000;
    private final java.util.Map<UUID, java.util.ArrayDeque<Long>> refusals = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> cooled = new java.util.HashMap<>();

    /** A request of this player was refused by the policy (server thread). */
    public void refused(UUID player) {
        long now = System.currentTimeMillis();
        var times = refusals.computeIfAbsent(player, p -> new java.util.ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= COOL_MS) times.pollFirst();
        times.addLast(now);
        if (times.size() >= REFUSALS) {
            times.clear();
            cooled.put(player, now + COOL_MS);
            com.mojang.logging.LogUtils.getLogger().info("Almighty: {} refusals in 10 minutes for {}; their /gm cools down for 10 minutes",
                    REFUSALS, player);
        }
    }

    private boolean cooling(UUID player) {
        Long until = cooled.get(player);
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        cooled.remove(player);
        return false;
    }

    public void left(ServerPlayer player) {
        addressing.forget(player.getUUID());
        waiting.remove(player.getUUID());
    }

    private List<ServerPlayer> humans() {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!bot(p)) out.add(p);
        return out;
    }

    private boolean bot(ServerPlayer p) {
        return ua.zentix.almighty.GmServer.of(server) instanceof ua.zentix.almighty.GmServer gm && gm.bots().of(p) != null;
    }

    static String lang(ServerPlayer p) {
        return p.clientInformation().language();
    }

    /** Untrusted fields of a player event: player-authored text is data, never instructions (plan 4.11). */
    static JsonArray untrusted(String... fields) {
        JsonArray a = new JsonArray();
        for (String f : fields) a.add(f);
        return a;
    }
}
