package ua.zentix.almighty.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.policy.Node;
import ua.zentix.almighty.policy.Nodes;
import ua.zentix.almighty.policy.Policy;
import ua.zentix.almighty.policy.Role;
import ua.zentix.almighty.world.Claims;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.params;
import static ua.zentix.almighty.gametest.GmGameTests.player;

/**
 * Presets, zones, roles, quotas and claims (plan step 9): a zone that denies building, an arena the agent may build in
 * on its own, a granted role, a permission handler (the LuckPerms path), the hourly block quota, and a claim mod.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmLadderGameTests {
    private GmLadderGameTests() {}

    private static final Caller GM = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "test");

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
    }

    private static String ticket(GameTestHelper h, GmServer gm, ServerPlayer p) throws InterruptedException {
        long after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "gm please build");
        for (JsonElement e : gm.feed().read(after, 100, 0).getAsJsonArray("events")) {
            if (e.getAsJsonObject().get("type").getAsString().equals("gm")) return e.getAsJsonObject().get("ticket").getAsString();
        }
        throw new GameTestAssertException("no /gm event");
    }

    private static String set(BlockPos at, String extra) {
        return String.format(Locale.ROOT, "{\"ops\":[{\"op\":\"set\",\"pos\":[%d,%d,%d],\"block\":\"minecraft:stone\"}],\"wait\":0%s}",
                at.getX(), at.getY(), at.getZ(), extra);
    }

    /** Refused at once (thrown, or a future already failed); never joins a call that needs another tick. */
    private static RpcException refused(GmServer gm, String method, JsonObject p) {
        CompletableFuture<?> f;
        try {
            f = gm.dispatch().invoke(GM, method, p);
        } catch (RpcException e) {
            return e;
        }
        if (f.isCompletedExceptionally()) {
            try {
                f.join();
            } catch (java.util.concurrent.CompletionException e) {
                if (e.getCause() instanceof RpcException r) return r;
            }
        }
        throw new GameTestAssertException(method + " was allowed: " + p);
    }

    private static String box(BlockPos a, BlockPos b) {
        return String.format(Locale.ROOT, "from = [%d, %d, %d]\nto = [%d, %d, %d]\n", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    @GameTest(template = "floor", batch = "gm_ladder")
    public static void zonesDenyAndAgentMay(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        BlockPos in = h.absolutePos(new BlockPos(2, 2, 2)), out = h.absolutePos(new BlockPos(12, 2, 12));
        try {
            gm.policies().set(Policy.parse("preset = \"friends\"\n[zones.spawn]\n" + box(in.offset(-1, -1, -1), in.offset(1, 1, 1))
                    + "deny = [\"build\"]"));
            check(refused(gm, "build", params(set(in, ""))).getMessage().contains("Zone spawn"), "a zone denies building");
            gm.dispatch().invoke(GM, "build", params(set(out, "")));
            // Public: the agent is a guest unasked, but may build in its arena.
            gm.policies().set(Policy.parse("preset = \"public\"\n[zones.arena]\n" + box(in.offset(-1, -1, -1), in.offset(1, 1, 1))
                    + "agent_may = [\"build\"]"));
            gm.dispatch().invoke(GM, "build", params(set(in, "")));
            check(refused(gm, "build", params(set(out, ""))).status() == 403, "outside the arena the floor applies");
            JsonObject sneaky = params(set(out, ""));
            sneaky.addProperty("\u0000zone_only", true);
            check(refused(gm, "build", sneaky).status() == 403, "the marker cannot be sent by the agent");
        } finally {
            gm.policies().set(before);
        }
        h.succeed();
    }

    @GameTest(template = "floor", batch = "gm_ladder")
    public static void rolesHandlersQuotasClaims(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        CompletableFuture<?>[] claimed = new CompletableFuture<?>[1];
        Policy before = gm.policies().current();
        UUID id = UUID.randomUUID();
        ServerPlayer guest = player(h, "ladder-guest", id);
        BlockPos at = h.absolutePos(new BlockPos(5, 2, 5));
        try {
            gm.policies().set(Policy.parse("preset = \"public\"\nidentity = \"proxy\"\n[quotas]\nbuild_blocks = 2"));
            String t = ticket(h, gm, guest);
            check(refused(gm, "build", params(set(at, ",\"for\":\"" + t + "\""))).status() == 403, "a guest cannot build");
            // A role from the ladder (what /almighty role does).
            check(gm.roles().set(id, "ladder-guest", Role.MEMBER, null, Role.OWNER, "test") == null, "role granted");
            gm.dispatch().invoke(GM, "build", params(set(at, ",\"for\":\"" + t + "\"")));
            // The hourly block quota: 1 used, 2 more would exceed 2.
            String two = String.format(Locale.ROOT, "{\"ops\":[{\"op\":\"fill\",\"from\":[%d,%d,%d],\"to\":[%d,%d,%d],\"block\":\"minecraft:stone\"}],\"wait\":0,\"for\":\"%s\"}",
                    at.getX(), at.getY() + 1, at.getZ(), at.getX() + 1, at.getY() + 1, at.getZ(), t);
            check(refused(gm, "build", params(two)).status() == 429, "block quota");
            gm.roles().set(id, "ladder-guest", null, null, Role.OWNER, "test");
            // A permission handler deciding (the LuckPerms path): it grants build to this guest.
            Nodes.handler((player, node) -> player.equals(id) && node == Node.BUILD ? Boolean.TRUE : null);
            gm.policies().set(Policy.parse("preset = \"public\"\nidentity = \"proxy\""));
            gm.dispatch().invoke(GM, "build", params(set(at.north(), ",\"for\":\"" + t + "\"")));
            // A claim mod: this chunk is someone else's claim.
            Claims.testSource(new Claims.Source() {
                public String name() {
                    return "test claims";
                }

                // The chunk of the build below (at.north(2), which is in another chunk than at when at.z % 16 < 2).
                public boolean mayBuild(UUID player, net.minecraft.server.level.ServerLevel level, int cx, int cz) {
                    return cx != (at.north(2).getX() >> 4) || cz != (at.north(2).getZ() >> 4);
                }
            });
            // The claim check is a world call: under a tight tick budget (slow CI) it runs on a later tick.
            CompletableFuture<?> f;
            try {
                f = gm.dispatch().invoke(GM, "build", params(set(at.north(2), ",\"for\":\"" + t + "\"")));
            } catch (RpcException e) {
                f = CompletableFuture.failedFuture(e);
            }
            claimed[0] = f;
        } finally {
            Nodes.handler(null);
            gm.roles().set(id, "ladder-guest", null, null, Role.OWNER, "test");
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(guest);
        }
        if (claimed[0] == null) {
            Claims.testSource(null);
            throw new GameTestAssertException("no claim check");
        }
        h.succeedWhen(() -> {
            if (!claimed[0].isDone()) throw new GameTestAssertException("waiting for the claim check");
            Claims.testSource(null);
            try {
                claimed[0].join();
            } catch (java.util.concurrent.CompletionException e) {
                check(e.getCause() instanceof RpcException r && r.getMessage().contains("claimed"), "claimed chunk: " + e.getCause());
                return;
            }
            throw new GameTestAssertException("build was allowed in a claimed chunk");
        });
    }

    //? if cpa {
    /*// Plan step 19: on Fabric, claims of any protection mod that registers with the Common Protection API. A
    // provider protecting one chunk from everyone: the source refuses exactly that chunk, and the server's claims
    // (built at start from the installed mods) refuse a build there naming the API.
    @GameTest(template = "floor", batch = "gm_ladder_cpa")
    public static void commonProtectionApiDecidesClaims(GameTestHelper h) {
        GmServer gm = gm(h);
        BlockPos at = h.absolutePos(new BlockPos(5, 2, 5));
        int cx = at.getX() >> 4, cz = at.getZ() >> 4;
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("almighty", "test_protection");
        eu.pb4.common.protection.api.CommonProtection.register(id, new eu.pb4.common.protection.api.ProtectionProvider() {
            public boolean isProtected(net.minecraft.world.level.Level level, BlockPos pos) {
                return (pos.getX() >> 4) == cx && (pos.getZ() >> 4) == cz;
            }

            public boolean isAreaProtected(net.minecraft.world.level.Level level, net.minecraft.world.phys.AABB box) {
                return false;
            }

            public boolean canPlaceBlock(net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.server.players.NameAndId who,
                                         net.minecraft.world.entity.player.Player player) {
                return !isProtected(level, pos);
            }

            public boolean canBreakBlock(net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.server.players.NameAndId who,
                                         net.minecraft.world.entity.player.Player player) {
                return !isProtected(level, pos);
            }
        });
        try {
            var source = new ua.zentix.almighty.compat.cpa.CommonProtectionClaims();
            check(!source.mayBuild(null, h.getLevel(), cx, cz), "the protected chunk is refused to a stranger");
            check(!source.mayBuild(UUID.randomUUID(), h.getLevel(), cx, cz), "and to a player the provider does not allow");
            check(source.mayBuild(null, h.getLevel(), cx + 1, cz), "the next chunk is open");
            String refusal = gm.claims().refusal(null, h.getLevel(), at.getX(), at.getZ(), at.getX(), at.getZ());
            check(refusal != null && refusal.contains("Common Protection API"), "a build there is refused: " + refusal);
        } finally {
            eu.pb4.common.protection.api.CommonProtection.remove(id);
        }
        h.succeed();
    }
    *///?}

    /** Review of step 9: commands reaching into a zone by coordinates, execute relocation, bots in a zone without pos. */
    @GameTest(template = "floor", batch = "gm_ladder_commands")
    public static void zonesCoverCommandsAndBots(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        UUID id = UUID.randomUUID();
        ServerPlayer builder = player(h, "ladder-builder", id);
        BlockPos in = h.absolutePos(new BlockPos(2, 2, 2)), out = h.absolutePos(new BlockPos(12, 2, 12));
        builder.teleportTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
        try {
            gm.policies().set(Policy.parse("preset = \"public\"\nidentity = \"proxy\"\n[zones.spawn]\n" + box(in.offset(-1, -1, -1), in.offset(1, 1, 1))
                    + "deny = [\"build\", \"bots\"]"));
            check(gm.roles().set(id, "ladder-builder", Role.BUILDER, null, Role.OWNER, "test") == null, "role granted");
            String t = ticket(h, gm, builder);
            String fill = String.format(Locale.ROOT, "{\"commands\":[\"fill %d %d %d %d %d %d minecraft:stone\"],\"for\":\"%s\"}",
                    out.getX(), in.getY(), out.getZ(), in.getX() - 3, in.getY(), in.getZ() - 3, t);
            check(refused(gm, "command", params(fill)).getMessage().contains("Zone spawn"), "a fill straddling the zone is a build there");
            String moved = String.format(Locale.ROOT, "{\"commands\":[\"execute positioned %d %d %d run setblock ~ ~ ~ minecraft:stone\"],\"for\":\"%s\"}",
                    in.getX(), in.getY(), in.getZ(), t);
            check(refused(gm, "command", params(moved)).getMessage().contains("execute positioned"), "execute relocation refused");
            String macro = "{\"lines\":[\"$setblock $(x) 1 1 minecraft:stone\"],\"args\":{\"x\":1},\"for\":\"" + t + "\"}";
            check(refused(gm, "function", params(macro)).status() == 403, "macro lines cannot be checked");
            String fine = String.format(Locale.ROOT, "{\"commands\":[\"setblock %d %d %d minecraft:stone\"],\"for\":\"%s\"}",
                    out.getX(), out.getY(), out.getZ(), t);
            gm.dispatch().invoke(GM, "command", params(fine)).get(5, java.util.concurrent.TimeUnit.SECONDS);
            check(h.getLevel().getBlockState(out).is(net.minecraft.world.level.block.Blocks.STONE), "outside the zone it runs");
            // bots denies bots.admin too (a creative bot), and a bot without pos is checked at the world spawn.
            String creative = String.format(Locale.ROOT, "{\"name\":\"zonebot\",\"pos\":[%d,%d,%d],\"gamemode\":\"creative\"}", in.getX(), in.getY(), in.getZ());
            gm.policies().set(Policy.parse("preset = \"private\"\n[agents.gm]\nfloor = \"admin\"\n[zones.spawn]\n" + box(in.offset(-1, -1, -1), in.offset(1, 1, 1))
                    + "deny = [\"bots\"]"));
            String m1 = refused(gm, "bot.spawn", params(creative)).getMessage();
            check(m1.contains("Zone spawn"), "bots denies bots.admin: " + m1);
            BlockPos spawn = Mc.spawnPos(h.getLevel().getServer().overworld());
            gm.policies().set(Policy.parse("preset = \"private\"\n[agents.gm]\nfloor = \"admin\"\n[zones.spawn]\n" + box(spawn.offset(-1, -400, -1), spawn.offset(1, 400, 1))
                    + "deny = [\"bots\"]"));
            check(refused(gm, "bot.spawn", params("{\"name\":\"zonebot\"}")).getMessage().contains("Zone spawn"), "no pos: checked at spawn");
            // An operator (level 4) who is not an owner cannot make owners or agent tokens; the console can.
            var almighty = h.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("almighty");
            var op = Mc.withLevel(builder.createCommandSourceStack(), 4);
            for (String sub : new String[]{"owner", "agent", "revoke"}) {
                check(!almighty.getChild(sub).canUse(op), "an operator who is not an owner: /almighty " + sub);
                check(almighty.getChild(sub).canUse(h.getLevel().getServer().createCommandSourceStack()), "the console: /almighty " + sub);
            }
        } finally {
            gm.roles().set(id, "ladder-builder", null, null, Role.OWNER, "test");
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(builder);
        }
        h.succeed();
    }
}
