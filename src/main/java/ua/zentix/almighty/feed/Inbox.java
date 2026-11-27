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

    private record Awaiting(long seq, Agent agent, UUID player, long deadline) {}

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
            String[] parts = body.substring(1).split("\\s+", 2);
            agent = parts[0].toLowerCase(java.util.Locale.ROOT);
            body = parts.length > 1 ? parts[1] : "";
            Agents known = agents.get();
            if (known != null && known.get(agent) == null) {
                player.sendSystemMessage(GmMessages.get("almighty.gm.no_agent", lang(player), agent).copy().withStyle(ChatFormatting.GRAY));
                return false;
            }
        }
        if (!addressing.allow(player.getUUID(), GmConfig.FEED_GM_MESSAGES.get())) {
            player.sendSystemMessage(GmMessages.get("almighty.gm.slow_down", lang(player)).copy().withStyle(ChatFormatting.GRAY));
            return false;
        }
        JsonObject d = FeedListeners.where(player);
        d.addProperty("text", body);
        d.add("untrusted", untrusted("text"));
        deliver(player, "gm", d, Addressing.Via.GM, agent);
        return true;
    }

    /** A public chat line: addressed to the GM or not, always in the feed (as today). */
    public void chat(ServerPlayer player, JsonObject d, String text) {
        boolean alone = humans().stream().allMatch(p -> p == player);
        Addressing.Via via = addressing.chat(player.getUUID(), text, alone);
        if (via != null && !addressing.allow(player.getUUID(), GmConfig.FEED_GM_MESSAGES.get())) {
            player.sendSystemMessage(GmMessages.get("almighty.gm.slow_down", lang(player)).copy().withStyle(ChatFormatting.GRAY));
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
        tickets.mint(id, player.getUUID(), player.getGameProfile().getName(), agentName, seq, verified);
        Agents known = agents.get();
        if (known == null) return;
        Agent agent = known.get(agentName);
        if (agent == null || !agent.present(PRESENT_MS)) away(player);
        else awaiting.add(new Awaiting(seq, agent, player.getUUID(), System.currentTimeMillis() + GmConfig.FEED_AWAY_SECONDS.get() * 1000L));
    }

    private void away(ServerPlayer player) {
        player.sendSystemMessage(GmMessages.get("almighty.gm.away", lang(player)).copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    /** Once a second: tell players whose message their agent has not read in time that it is away. */
    public void tick(int tickCount) {
        if (tickCount % 20 != 0 || awaiting.isEmpty()) return;
        long now = System.currentTimeMillis();
        Set<UUID> told = new HashSet<>();
        for (Iterator<Awaiting> it = awaiting.iterator(); it.hasNext(); ) {
            Awaiting a = it.next();
            if (a.agent.readUpTo() >= a.seq) {
                it.remove();
            } else if (now >= a.deadline) {
                it.remove();
                ServerPlayer p = server.getPlayerList().getPlayer(a.player);
                if (p != null && told.add(a.player)) away(p);
            }
        }
    }

    /** The GM replied: {@code to} null means everyone, answering every waiting player. */
    public void replied(Collection<ServerPlayer> to) {
        Collection<UUID> players = to == null ? new ArrayList<>(waiting) : to.stream().map(ServerPlayer::getUUID).toList();
        addressing.replied(players);
        players.forEach(waiting::remove);
    }

    public void left(ServerPlayer player) {
        addressing.forget(player.getUUID());
        waiting.remove(player.getUUID());
    }

    private List<ServerPlayer> humans() {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            JsonObject d = FeedListeners.player(p);
            if (!d.has("bot")) out.add(p);
        }
        return out;
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
