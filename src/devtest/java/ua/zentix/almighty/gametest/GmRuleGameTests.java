package ua.zentix.almighty.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.policy.Policy;
import ua.zentix.almighty.policy.Role;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.params;
import static ua.zentix.almighty.gametest.GmGameTests.player;

/**
 * Declarative rules bound to their creator (plan 4.12, step 11): a builder's rule cancels only where they may
 * build, stops working when they lose the right, cannot cancel an event without a place, and expires.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmRuleGameTests {
    private GmRuleGameTests() {}

    private static final Caller GM = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "test");

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
    }

    private static String ticket(GameTestHelper h, GmServer gm, ServerPlayer p) throws InterruptedException {
        long after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "gm keep my blocks");
        for (JsonElement e : gm.feed().read(after, 100, 0).getAsJsonArray("events")) {
            if (e.getAsJsonObject().get("type").getAsString().equals("gm")) return e.getAsJsonObject().get("ticket").getAsString();
        }
        throw new GameTestAssertException("no /gm event");
    }

    private static JsonElement call(GmServer gm, String method, String json) throws Exception {
        CompletableFuture<JsonElement> f = gm.dispatch().invoke(GM, method, params(json));
        return f.get(1, TimeUnit.SECONDS);
    }

    private static RpcException refused(GmServer gm, String method, String json) {
        try {
            call(gm, method, json);
        } catch (RpcException e) {
            return e;
        } catch (Exception e) {
            if (e.getCause() instanceof RpcException r) return r;
            throw new GameTestAssertException(method + ": " + e);
        }
        throw new GameTestAssertException(method + " was allowed: " + json);
    }

    private static boolean breaks(GameTestHelper h, BlockPos at, ServerPlayer by) {
        return Gt.breaks(h.getLevel(), at, Blocks.STONE.defaultBlockState(), by);
    }

    private static String state(GmServer gm, String name) {
        for (JsonElement r : gm.rules().describe()) {
            JsonObject o = r.getAsJsonObject();
            if (o.has("name") && o.get("name").getAsString().equals(name)) return o.get("state").getAsString();
        }
        return "gone";
    }

    private static int emits(GmServer gm, long after) throws InterruptedException {
        int n = 0;
        for (JsonElement e : gm.feed().read(after, 1000, 0).getAsJsonArray("events")) {
            if (e.getAsJsonObject().get("type").getAsString().equals("emit")) n++;
        }
        return n;
    }

    /**
     * Stability M9: a declarative rule's command writes are journaled (one change per rule, collecting up to a
     * minute), listed in `changes` and reverted by `undo {change}` like a call's.
     */
    @GameTest(template = "floor", batch = "gm_rules_journal", timeoutTicks = 200)
    public static void ruleCommandWritesAreJournaled(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        ServerPlayer p = player(h, "rule-journal", UUID.randomUUID());
        BlockPos at = h.absolutePos(new BlockPos(3, 2, 3)), mark = h.absolutePos(new BlockPos(6, 2, 6));
        long[] change = new long[1];
        try {
            call(gm, "rule.add", String.format(Locale.ROOT, "{\"event\":\"%s\",\"name\":\"journal-rule\",\"then\":[{\"command\":"
                    + "\"setblock %d %d %d minecraft:gold_block\"}]}", Gt.BREAK_EVENT, mark.getX(), mark.getY(), mark.getZ()));
            breaks(h, at, p);
            check(h.getLevel().getBlockState(mark).is(Blocks.GOLD_BLOCK), "the rule's command ran");
            gm.closeRuleChanges();
            JsonObject newest = gm.journal().list(1).get(0).getAsJsonObject();
            check(newest.get("method").getAsString().equals("rule journal-rule") && newest.get("agent").getAsString().equals("gm")
                    && newest.get("positions").getAsLong() == 1, "the rule's write is a change of its creator: " + newest);
            change[0] = newest.get("id").getAsLong();
            check(call(gm, "changes", "{\"limit\":5}").toString().contains("rule journal-rule"), "listed by changes");
            call(gm, "undo", String.format(Locale.ROOT, "{\"change\":%d,\"wait\":0}", change[0]));
        } finally {
            try {
                call(gm, "rule.remove", "{\"name\":\"journal-rule\"}");
            } catch (Exception ignored) {
                // Not added.
            }
            h.getLevel().getServer().getPlayerList().remove(p);
        }
        h.succeedWhen(() -> {
            check(h.getLevel().getBlockState(mark).isAir(), "reverted: " + h.getLevel().getBlockState(mark));
            check(gm.journal().describe(change[0]).has("undone_by"), "marked undone");
        });
    }

    /** Final audit M1: below owner, a rule that writes event fields to the feed (they carry players' places) needs observe.players. */
    @GameTest(template = "floor", batch = "gm_rules_fields")
    public static void fieldRulesNeedObservePlayers(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        try {
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\"\n[agents.gm]\nfloor = \"builder\"\n"));
            RpcException e = refused(gm, "rule.add", "{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"watch\"}");
            check(e.status() == 403 && e.getMessage().contains("observe.players"), "a builder's field rule: " + e.getMessage());
            check(refused(gm, "rule.add", "{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"watch\",\"then\":[\"emit\"]}").status() == 403,
                    "emit too");
        } finally {
            gm.policies().set(before);
        }
        h.succeed();
    }

    /**
     * Pausing one agent stops its rules, not only its builds (AGENTS.md "Pause"); and a rule without a script on
     * command events never puts a player's private message into the feed (the feed's own filter, FeedListeners).
     */
    @GameTest(template = "floor", batch = "gm_rules_pause")
    public static void pausedAgentsRulesStopAndPrivateCommandsStayOut(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        ServerPlayer p = player(h, "rule-watcher", UUID.randomUUID());
        BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
        try {
            call(gm, "rule.add", "{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"watch-break\"}");
            long after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
            gm.pause().pause("gm");
            breaks(h, at, p);
            check(emits(gm, after) == 0, "a paused agent's rule ran");
            gm.pause().resume("gm");
            breaks(h, at, p);
            check(emits(gm, after) == 1, "the rule after resume: " + emits(gm, after));
            // Pause of all stops scripts and rules made below the owner; this one is the owner's (private, unasked).
            gm.pause().pause(null);
            breaks(h, at, p);
            gm.pause().resume(null);
            check(emits(gm, after) == 2, "an owner-made rule stopped on pause of all: " + emits(gm, after));
            //? if neoforge {
            call(gm, "rule.add", "{\"event\":\"CommandEvent\",\"name\":\"watch-commands\"}");
            after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
            var commands = h.getLevel().getServer().getCommands();
            commands.performPrefixedCommand(p.createCommandSourceStack(), "msg rule-watcher a secret");
            commands.performPrefixedCommand(p.createCommandSourceStack(), "list");
            String feed = gm.feed().read(after, 1000, 0).toString();
            check(emits(gm, after) == 1 && !feed.contains("a secret"), "private message in a rule's output: " + feed);
            //?}
        } finally {
            for (String name : new String[] {"watch-break", "watch-commands"}) {
                try {
                    call(gm, "rule.remove", "{\"name\":\"" + name + "\"}");
                } catch (Exception ignored) {
                    // Not added (Fabric has no command event).
                }
            }
            gm.pause().resume(null);
            h.getLevel().getServer().getPlayerList().remove(p);
        }
        h.succeed();
    }

    @GameTest(template = "floor", batch = "gm_rules_declarative")
    public static void declarativeRulesFollowTheirCreator(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        UUID id = UUID.randomUUID();
        ServerPlayer builder = player(h, "rule-builder", id);
        BlockPos mine = h.absolutePos(new BlockPos(3, 2, 3)), spawn = h.absolutePos(new BlockPos(10, 2, 10));
        String when = String.format(Locale.ROOT, "{\"pos.1\":%d}", mine.getY());
        try {
            gm.policies().set(Policy.parse(String.format(Locale.ROOT, "preset = \"friends\"\nidentity = \"proxy\"\n[zones.spawn]\nfrom = [%d, %d, %d]\nto = [%d, %d, %d]\ndeny = [\"build\"]",
                    spawn.getX() - 1, spawn.getY() - 1, spawn.getZ() - 1, spawn.getX() + 1, spawn.getY() + 1, spawn.getZ() + 1)));
            // Unasked, the agent is a member on a friends' server: no rules (builder).
            check(refused(gm, "rule.add", "{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"keep\",\"then\":[\"cancel\"]}").status() == 403, "member floor: no rules");
            check(gm.roles().set(id, "rule-builder", Role.BUILDER, null, Role.OWNER, "test") == null, "role");
            String t = ticket(h, gm, builder);
            // An event with no place cannot be canceled everywhere below owner.
            check(refused(gm, "rule.add", "{\"event\":\"" + Gt.UNPLACED_EVENT + "\",\"name\":\"mute\",\"then\":[\"cancel\"],\"for\":\"" + t + "\"}")
                    .getMessage().contains("needs the owner"), "server-wide cancel");
            //? if fabric {
            /*// A boolean callback outside Fabric's allow/before convention has no neutral answer: listening alone would
            // change the game (true = "glide without an elytra"), so no rule may subscribe, not even the owner's.
            RpcException glide = refused(gm, "rule.add", "{\"event\":\"EntityElytraEvents.CUSTOM\",\"name\":\"glide\",\"for\":\"" + t + "\"}");
            check(glide.status() == 400 && glide.getMessage().contains("allow/before"), "act-on-true callback: " + glide.getMessage());
            *///?}
            JsonObject added = call(gm, "rule.add", "{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"keep\",\"when\":" + when
                    + ",\"then\":[\"cancel\"],\"for\":\"" + t + "\"}").getAsJsonObject();
            check(added.has("expires") && added.get("for").getAsString().contains("rule-builder"), "bound to its creator, with a TTL: " + added);
            check(!breaks(h, mine, builder), "canceled where the creator may build");
            // In the spawn zone (deny build) the creator may not build: not canceled.
            BlockPos inZone = new BlockPos(spawn.getX(), mine.getY(), spawn.getZ());
            gm.policies().set(Policy.parse(String.format(Locale.ROOT, "preset = \"friends\"\nidentity = \"proxy\"\n[zones.spawn]\nfrom = [%d, %d, %d]\nto = [%d, %d, %d]\ndeny = [\"build\"]",
                    inZone.getX() - 1, inZone.getY() - 1, inZone.getZ() - 1, inZone.getX() + 1, inZone.getY() + 1, inZone.getZ() + 1)));
            check(breaks(h, inZone, builder), "not canceled inside a zone that denies the creator building");
            // The creator loses the role: the rule turns off at its next fire.
            gm.roles().set(id, "rule-builder", null, null, Role.OWNER, "test");
            check(breaks(h, mine, builder), "a rule stops working when its creator loses the right");
            check(state(gm, "keep").equals("off"), "turned off, still listed: " + state(gm, "keep"));
            // TTL: a rule made below owner expires.
            gm.roles().set(id, "rule-builder", Role.BUILDER, null, Role.OWNER, "test");
            call(gm, "rule.add", "{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"short\",\"when\":" + when
                    + ",\"then\":[\"cancel\"],\"ttl_hours\":0.00001,\"for\":\"" + t + "\"}");
            Thread.sleep(80);
            check(breaks(h, mine, builder), "expired");
            check(state(gm, "short").equals("gone"), "an expired rule is removed");
            // A same name does not replace someone else's rule (here the owner's).
            gm.dispatch().invoke(Caller.INTERNAL, "rule.add", params("{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"owners\",\"then\":[\"emit\"]}"));
            // A then the builder may add (cancel where they build), so the refusal is the name's, not observe.players'.
            RpcException byName = refused(gm, "rule.add", "{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"owners\",\"when\":" + when
                    + ",\"then\":[\"cancel\"],\"for\":\"" + t + "\"}");
            check(byName.status() == 403 && !byName.getMessage().contains("observe.players"), "the owner's rule is not replaced by name: " + byName.getMessage());
            // Rule commands get the same checks as command calls below owner.
            check(refused(gm, "rule.add", "{\"event\":\"" + Gt.LOGIN_EVENT + "\",\"name\":\"click\",\"then\":[{\"command\":"
                    + "\"tellraw @a {\\\"text\\\":\\\"x\\\",\\\"clickEvent\\\":{\\\"action\\\":\\\"run_command\\\",\\\"value\\\":\\\"/op me\\\"}}\"}],\"for\":\"" + t + "\"}")
                    .status() == 403, "no click events in rule commands");
            // The owner's own ttl_hours expires too.
            gm.dispatch().invoke(Caller.INTERNAL, "rule.add", params("{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"ownerShort\",\"then\":[\"emit\"],\"ttl_hours\":0.00001}"));
            Thread.sleep(80);
            breaks(h, mine, builder);
            check(state(gm, "ownerShort").equals("gone"), "an owner's rule with ttl_hours expires");
            // Bound to its creator: the agent unasked cannot remove the player's rule.
            call(gm, "rule.add", "{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"theirs\",\"when\":" + when + ",\"then\":[\"cancel\"],\"for\":\"" + t + "\"}");
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\"\n[agents.gm]\nfloor = \"builder\""));
            check(refused(gm, "rule.remove", "{\"name\":\"theirs\"}").status() == 403, "only its creator or an admin removes it");
        } finally {
            for (String n : new String[]{"keep", "short", "theirs", "probe", "mute", "owners", "click", "ownerShort"}) {
                try {
                    gm.dispatch().invoke(Caller.INTERNAL, "rule.remove", params("{\"name\":\"" + n + "\"}"));
                } catch (RpcException e) {
                    // not there
                }
            }
            gm.roles().set(id, "rule-builder", null, null, Role.OWNER, "test");
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(builder);
        }
        h.succeed();
    }
}
