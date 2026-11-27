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
            check(refused(gm, "rule.add", "{\"event\":\"" + Gt.BREAK_EVENT + "\",\"name\":\"owners\",\"then\":[\"emit\"],\"for\":\"" + t + "\"}").status() == 403,
                    "the owner's rule is not replaced by name");
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
