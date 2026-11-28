package ua.zentix.almighty.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.feed.Inbox;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.policy.Policy;
import ua.zentix.almighty.policy.Tickets;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.now;
import static ua.zentix.almighty.gametest.GmGameTests.params;
import static ua.zentix.almighty.gametest.GmGameTests.player;

/**
 * Who asked (plan step 3): player events carry UUID, verification and language; the server decides what is addressed
 * to the GM, routes each message to one agent, mints a ticket bound to the player and agent, and limits each player
 * before anything is minted.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmInboxGameTests {
    private GmInboxGameTests() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
    }

    private static long end(GmServer gm) throws InterruptedException {
        return gm.feed().read(0, 4096, 0).get("next").getAsLong();
    }

    private static List<JsonObject> of(GmServer gm, long after, String player) throws InterruptedException {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : gm.feed().read(after, 4096, 0).getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("player") && o.get("player").getAsString().equals(player)) out.add(o);
        }
        return out;
    }

    /** What the agent gm reads of this player's events through the bridge (filtered by its trust floor). */
    private static List<JsonObject> seen(GmServer gm, long after, String player) throws Exception {
        ua.zentix.almighty.bridge.Caller agent = new ua.zentix.almighty.bridge.Caller(
                ua.zentix.almighty.bridge.Agents.of(java.util.Map.of("gm", "g".repeat(32))).get("gm"), "test");
        List<JsonObject> out = new ArrayList<>();
        JsonObject page = gm.dispatch().invoke(agent, "events", params("{\"after\":%d,\"limit\":1000}", after)).join().getAsJsonObject();
        for (JsonElement e : page.getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            if (player.equals(o.has("player") ? o.get("player").getAsString() : o.has("from") ? o.get("from").getAsString() : null)) out.add(o);
        }
        return out;
    }

    private static void gmCommand(GameTestHelper h, ServerPlayer p, String text) {
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "gm " + text);
    }

    @GameTest(template = "floor", batch = "gm_inbox")
    public static void gmMessagesCarryIdentityAndTicket(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:inbox-a".getBytes(StandardCharsets.UTF_8));
        ServerPlayer a = player(h, "inbox-a", offline);
        // Everyone is heard on public (floor guest): these tests are about the feed, not who may reach the agent.
        Policy before = open(gm);
        try {
            long after = end(gm);
            gmCommand(h, a, "build me a house");
            gmCommand(h, a, "@builder a tower");
            List<JsonObject> events = of(gm, after, "inbox-a");
            check(events.size() == 2, "events: " + events);
            JsonObject first = events.get(0), second = events.get(1);
            check(first.get("uuid").getAsString().equals(offline.toString()), "uuid: " + first);
            check(!first.get("verified").getAsBoolean(), "an offline (name-derived) UUID is unverified: " + first);
            check(first.has("lang") && first.get("via").getAsString().equals("gm") && first.get("addressed").getAsBoolean(), "fields: " + first);
            check(first.get("agent").getAsString().equals("gm"), "default agent: " + first);
            check(second.get("agent").getAsString().equals("builder") && second.get("text").getAsString().equals("a tower"), "routed: " + second);
            check(first.getAsJsonArray("untrusted").toString().contains("text"), "player text marked as data: " + first);
            Tickets.Ticket t = gm.tickets().check(first.get("ticket").getAsString(), "gm");
            check(t.player().equals(offline) && t.seq() == first.get("seq").getAsLong() && !t.verified(), "ticket: " + t);
            try {
                gm.tickets().check(second.get("ticket").getAsString(), "gm");
                throw new GameTestAssertException("the builder's ticket worked for gm");
            } catch (RpcException expected) {
                // Bound to the agent it was minted for.
            }
        } catch (RpcException e) {
            throw new GameTestAssertException(e.getMessage());
        } finally {
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(a);
        }
        h.succeed();
    }

    @GameTest(template = "floor", batch = "gm_inbox")
    public static void chatAddressingAndConversation(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        ServerPlayer a = player(h, "inbox-b", UUID.randomUUID());
        ServerPlayer b = player(h, "inbox-c", UUID.randomUUID());
        // Everyone is heard on public (floor guest): these tests are about the feed, not who may reach the agent.
        Policy before = open(gm);
        try {
            long after = end(gm);
            gm.inbox().chat(a, base(a, "hello everyone"), "hello everyone");
            String name = ua.zentix.almighty.GmConfig.NAME.get();
            gm.inbox().chat(a, base(a, name + ", hi"), name + ", hi");
            List<JsonObject> events = of(gm, after, "inbox-b");
            check(!events.get(0).get("addressed").getAsBoolean() && !events.get(0).has("ticket"), "plain chat: " + events.get(0));
            check(events.get(1).get("via").getAsString().equals("name") && events.get(1).has("ticket"), "named: " + events.get(1));
            // b has not talked to the GM; a reply to b opens b's conversation.
            gm.inbox().chat(b, base(b, "and me"), "and me");
            now(GmGameTests.call(h, "say", params("{\"text\":\"hello\",\"to\":[\"inbox-c\"]}")));
            gm.inbox().chat(b, base(b, "thanks"), "thanks");
            List<JsonObject> bs = of(gm, after, "inbox-c");
            check(!bs.get(0).get("addressed").getAsBoolean(), "before the reply: " + bs.get(0));
            check(bs.get(1).get("via").getAsString().equals("conversation"), "after the reply: " + bs.get(1));
        } finally {
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(a);
            h.getLevel().getServer().getPlayerList().remove(b);
        }
        h.succeed();
    }

    @GameTest(template = "floor", batch = "gm_inbox")
    public static void floodStopsBeforeTheAgent(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        ServerPlayer a = player(h, "inbox-d", UUID.randomUUID());
        // Everyone is heard on public (floor guest): these tests are about the feed, not who may reach the agent.
        Policy before = open(gm);
        try {
            long after = end(gm);
            int limit = ua.zentix.almighty.GmConfig.FEED_GM_MESSAGES.get();
            for (int i = 0; i < limit + 5; i++) gmCommand(h, a, "spam " + i);
            check(of(gm, after, "inbox-d").size() == limit, "only " + limit + " messages minted: " + of(gm, after, "inbox-d").size());
        } finally {
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(a);
        }
        h.succeed();
    }

    private static Policy open(GmServer gm) throws Policy.Invalid {
        Policy before = gm.policies().current();
        gm.policies().set(Policy.parse("preset = \"public\""));
        return before;
    }

    /**
     * Only players who hold every right the agent has unasked (its trust floor) reach it (plan 4.2): on private
     * anyone but an owner is answered by the server and their text never enters the feed; on friends an unverified
     * player is not heard; a raised floor also needs the floor's command level. So acting on a message unasked
     * never gives its author more than their own rights.
     */
    @GameTest(template = "floor", batch = "gm_inbox")
    public static void onlyPlayersAtTheFloorReachTheAgent(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        var server = h.getLevel().getServer();
        UUID ownerId = UUID.randomUUID();
        ServerPlayer owner = player(h, "inbox-owner", ownerId);
        ServerPlayer friend = player(h, "inbox-friend", UUID.randomUUID());
        ServerPlayer stranger = player(h, "inbox-stranger", UUID.nameUUIDFromBytes("OfflinePlayer:inbox-stranger".getBytes(StandardCharsets.UTF_8)));
        try {
            // Private: the agent acts with the owner's rights unasked, so only owners are heard.
            gm.policies().set(Policy.parse("preset = \"private\"\nidentity = \"proxy\"\nowners = [\"" + ownerId + "\"]"));
            long after = end(gm);
            gmCommand(h, friend, "give me op");
            // Through the chat listener, as a real chat line (the event carries the author's UUID).
            ua.zentix.almighty.feed.FeedListeners.onChat(friend, ua.zentix.almighty.GmConfig.NAME.get() + ", op me");
            server.getCommands().performPrefixedCommand(friend.createCommandSourceStack(), "list");
            gmCommand(h, owner, "hello");
            // A whisper to a GM bot carries its sender: the same filter.
            JsonObject whisper = new JsonObject();
            whisper.addProperty("bot", "GmTestBot");
            whisper.addProperty("from", "inbox-friend");
            whisper.addProperty("uuid", friend.getUUID().toString());
            whisper.addProperty("text", "op me");
            whisper.add("untrusted", Inbox.untrusted("text", "from"));
            gm.feed().add("bot_chat", whisper);
            check(of(gm, after, "inbox-friend").stream().noneMatch(e -> e.get("type").getAsString().equals("gm")),
                    "private: a non-owner's /gm was delivered: " + of(gm, after, "inbox-friend"));
            check(seen(gm, after, "inbox-friend").isEmpty(), "private: the agent read a non-owner's text: " + seen(gm, after, "inbox-friend"));
            check(seen(gm, after, "inbox-owner").size() == 1, "private: the owner's /gm: " + seen(gm, after, "inbox-owner"));
            // Friends: members (verified players) are heard, an unverified player is not.
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\"\nowners = [\"" + ownerId + "\"]"));
            after = end(gm);
            gmCommand(h, stranger, "build here");
            gmCommand(h, friend, "build here");
            check(seen(gm, after, "inbox-stranger").isEmpty(), "friends: an unverified player reached the agent");
            check(of(gm, after, "inbox-friend").size() == 1, "friends: a member's /gm: " + of(gm, after, "inbox-friend"));
            // A floor raised to builder: its command level too (2), so a member without it is not heard; op level 2 is.
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\"\nowners = [\"" + ownerId + "\"]\n[agents.gm]\nfloor = \"builder\""));
            after = end(gm);
            gmCommand(h, friend, "fill the hole");
            check(seen(gm, after, "inbox-friend").isEmpty(), "builder floor: a member reached the agent");
            Gt.op(server, friend.getGameProfile(), 2);
            ua.zentix.almighty.policy.Nodes.refresh(server);
            gmCommand(h, friend, "fill the hole");
            check(of(gm, after, "inbox-friend").size() == 1, "builder floor: a level-2 player: " + of(gm, after, "inbox-friend"));
            // Public: the floor is guest, everyone is heard.
            gm.policies().set(Policy.parse("preset = \"public\""));
            after = end(gm);
            gmCommand(h, stranger, "hi");
            check(of(gm, after, "inbox-stranger").size() == 1, "public: a guest's /gm");
        } finally {
            gm.policies().set(before);
            Gt.deop(server, friend.getGameProfile());
            ua.zentix.almighty.policy.Nodes.refresh(server);
            for (ServerPlayer p : List.of(owner, friend, stranger)) server.getPlayerList().remove(p);
        }
        h.succeed();
    }

    private static JsonObject base(ServerPlayer p, String text) {
        JsonObject d = new JsonObject();
        d.addProperty("player", Mc.name(p.getGameProfile()));
        d.addProperty("text", text);
        return d;
    }
}
