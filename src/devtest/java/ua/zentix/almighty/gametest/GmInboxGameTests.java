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
import ua.zentix.almighty.platform.Mc;
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

    private static void gmCommand(GameTestHelper h, ServerPlayer p, String text) {
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "gm " + text);
    }

    @GameTest(template = "floor", batch = "gm_inbox")
    public static void gmMessagesCarryIdentityAndTicket(GameTestHelper h) throws InterruptedException {
        GmServer gm = gm(h);
        UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:inbox-a".getBytes(StandardCharsets.UTF_8));
        ServerPlayer a = player(h, "inbox-a", offline);
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
            h.getLevel().getServer().getPlayerList().remove(a);
        }
        h.succeed();
    }

    @GameTest(template = "floor", batch = "gm_inbox")
    public static void chatAddressingAndConversation(GameTestHelper h) throws InterruptedException {
        GmServer gm = gm(h);
        ServerPlayer a = player(h, "inbox-b", UUID.randomUUID());
        ServerPlayer b = player(h, "inbox-c", UUID.randomUUID());
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
            h.getLevel().getServer().getPlayerList().remove(a);
            h.getLevel().getServer().getPlayerList().remove(b);
        }
        h.succeed();
    }

    @GameTest(template = "floor", batch = "gm_inbox")
    public static void floodStopsBeforeTheAgent(GameTestHelper h) throws InterruptedException {
        GmServer gm = gm(h);
        ServerPlayer a = player(h, "inbox-d", UUID.randomUUID());
        try {
            long after = end(gm);
            int limit = ua.zentix.almighty.GmConfig.FEED_GM_MESSAGES.get();
            for (int i = 0; i < limit + 5; i++) gmCommand(h, a, "spam " + i);
            check(of(gm, after, "inbox-d").size() == limit, "only " + limit + " messages minted: " + of(gm, after, "inbox-d").size());
        } finally {
            h.getLevel().getServer().getPlayerList().remove(a);
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
