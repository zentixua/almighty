package ua.zentix.almighty.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.util.UndashedUuid;
//? if ia {
import immersive_aircraft.entity.EngineVehicle;
import immersive_aircraft.network.c2s.CommandMessage;
//?}
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
//? if >=26.1 {
/*import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.vehicle.boat.Boat;
*///?} else {
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.vehicle.Boat;
//?}
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.Api;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.bot.Bot;
import ua.zentix.almighty.bot.BotPlayer;
import ua.zentix.almighty.bot.Bots;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.script.ScriptApi;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static ua.zentix.almighty.gametest.GmGameTests.call;
import static ua.zentix.almighty.gametest.GmGameTests.check;
import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.now;
import static ua.zentix.almighty.gametest.GmGameTests.params;

/**
 * GM bots in the world: login, walking, mining and building, chest, chat and whisper, death and respawn,
 * dimension change, vehicles (horse, boat, IA aircraft), vision, player name protection. Remove the bot at each
 * test's end ({@code finally}/last step): bots are shared server-wide. Each test has its own batch.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmBotGameTests {
    private GmBotGameTests() {}

    private static String vec(GameTestHelper h, double x, double y, double z) {
        Vec3 v = h.absoluteVec(new Vec3(x, y, z));
        return String.format(Locale.ROOT, "[%.3f,%.3f,%.3f]", v.x, v.y, v.z);
    }

    private static String block(GameTestHelper h, int x, int y, int z) {
        BlockPos p = h.absolutePos(new BlockPos(x, y, z));
        return "[" + p.getX() + "," + p.getY() + "," + p.getZ() + "]";
    }

    /**
     * Standing height on the platform: the GameTest structure block is below it, and {@code absolutePos} counts
     * from that block, so the stone floor is at 1 and standing height at 2.
     */
    private static final int GROUND = 2;

    /** Bot at a platform point; yaw -90 faces east (+x). */
    private static Bot spawn(GameTestHelper h, String name, double x, double z, String extra) {
        JsonObject r = now(call(h, "bot.spawn", params("{\"name\":\"%s\",\"pos\":%s,\"yaw\":-90%s}", name, vec(h, x, GROUND, z), extra))).getAsJsonObject();
        check(r.get("bot").getAsBoolean(), "bot.spawn: " + r);
        Bot bot = gm(h).bots().find(name);
        check(bot != null && bot.online(), "bot is missing from the list");
        return bot;
    }

    private static void leave(GameTestHelper h, String name) {
        if (gm(h).bots().find(name) != null) now(call(h, "bot.remove", params("{\"name\":\"%s\"}", name)));
    }

    private static CompletableFuture<JsonElement> act(GameTestHelper h, String name, String actions) {
        return call(h, "bot.act", params("{\"name\":\"%s\",\"actions\":%s,\"wait\":50}", name, actions));
    }

    private static JsonObject finished(CompletableFuture<JsonElement> f) {
        // null means the step starting it failed (the test already failed, but the sequence continues).
        if (f == null || !f.isDone()) throw new GameTestAssertException("bot program is still running");
        JsonObject r = f.join().getAsJsonObject();
        check(r.get("state").getAsString().equals("done"), "program: " + r);
        return r;
    }

    private static JsonObject events(GameTestHelper h, long after) {
        try {
            return gm(h).feed().read(after, 1000, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameTestAssertException("feed interrupted");
        }
    }

    private static long feedEnd(GameTestHelper h) {
        return events(h, 0).get("next").getAsLong();
    }

    private static boolean hasEvent(GameTestHelper h, long after, String type, String key, String value) {
        for (JsonElement e : events(h, after).getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("type").getAsString().equals(type) && o.has(key) && o.get(key).getAsString().equals(value)) return true;
        }
        return false;
    }

    /**
     * Login with tab and chat markers, sprint east using server physics, logout: feed join/leave events carry
     * the bot flag; the player list no longer contains the bot.
     */
    @GameTest(template = "floor", batch = "gm_bot_walk", timeoutTicks = 200, skyAccess = true)
    public static void botWalksAndLeaves(GameTestHelper h) {
        long after = feedEnd(h);
        Bot bot = spawn(h, "GmTestWalker", 10.5, 10.5, ",\"gamemode\":\"survival\"");
        ServerPlayer p = bot.player();
        check(p.getTabListDisplayName() != null && p.getTabListDisplayName().getString().equals("[bot] GmTestWalker"), "tab: " + p.getTabListDisplayName());
        check(p.getDisplayName().getString().contains("[bot]"), "chat name: " + p.getDisplayName().getString());
        check(p.getUUID().equals(Bots.uuid("GmTestWalker")) && !p.getUUID().equals(UUIDUtil.createOfflinePlayerUUID("GmTestWalker")), "bot UUID: " + p.getUUID());
        Vec3 start = p.position();
        CompletableFuture<JsonElement> walk = act(h, "GmTestWalker", "[{\"hold\":[\"forward\",\"sprint\"]},{\"wait\":20},{\"release\":\"all\"}]");
        h.startSequence()
                .thenWaitUntil(() -> finished(walk))
                .thenExecute(() -> {
                    Vec3 end = bot.player().position();
                    ServerPlayer q = bot.player();
                    check(end.x - start.x > 3.0 && Math.abs(end.z - start.z) < 0.5 && Math.abs(end.y - start.y) < 0.1,
                            "sprinting east for 20 ticks: " + start + " → " + end + ", on ground " + q.onGround() + ", mode " + q.gameMode.getGameModeForPlayer()
                                    + ", underfoot at start " + q.level().getBlockState(BlockPos.containing(start).below()) + ", at end " + q.level().getBlockState(BlockPos.containing(end))
                                    + ", sprint " + q.isSprinting() + ", program " + walk.join());
                    check(Gt.exhaustion(bot.player()) > 0, "sprinting does not consume food");
                    leave(h, "GmTestWalker");
                    check(h.getLevel().getServer().getPlayerList().getPlayerByName("GmTestWalker") == null, "bot remains in the player list");
                    check(hasEvent(h, after, "join", "player", "GmTestWalker") && hasEvent(h, after, "leave", "player", "GmTestWalker"), "join and leave in the feed");
                    check(hasEvent(h, after, "bot", "bot", "GmTestWalker"), "bot event on leave");
                    for (JsonElement e : events(h, after).getAsJsonArray("events")) {
                        JsonObject o = e.getAsJsonObject();
                        if (o.get("type").getAsString().equals("join")) check(o.has("bot"), "join without bot flag: " + o);
                    }
                })
                // Lease release removes tickets at tick end.
                .thenExecuteAfter(1, () -> check(GmTickets.count(h.getLevel()) == 0, "login lease after bot logout: tickets " + GmTickets.count(h.getLevel())))
                .thenSucceed();
    }

    /**
     * Dead man's switch: a timed key ({@code ticks}) releases itself; when a rule controlling the bot
     * ({@code act} from a script) turns off, the bot stops: running program removed, keys released,
     * {@code bot_stopped} in the feed and {@code bots_stopped} in {@code rule.off}. Otherwise a disabled autopilot's
     * nose-down control would remain pressed and send the aircraft into the sea.
     */
    @GameTest(template = "floor", batch = "gm_bot_deadman", timeoutTicks = 200, skyAccess = true)
    public static void botKeysDieWithTheirRule(GameTestHelper h) {
        long after = feedEnd(h);
        Bot bot = spawn(h, "GmTestPilot", 3.5, 10.5, "");
        CompletableFuture<JsonElement> lease = act(h, "GmTestPilot", "[{\"hold\":[\"forward\",\"sneak\"],\"ticks\":5}]");
        AtomicReference<Vec3> start = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> finished(lease))
                .thenExecute(() -> {
                    JsonObject d = bot.describe(0);
                    check(d.getAsJsonArray("keys").size() == 2 && d.getAsJsonObject("keys_ticks_left").get("forward").getAsInt() > 0,
                            "timed keys: " + d.get("keys") + " " + d.get("keys_ticks_left"));
                    start.set(bot.player().position());
                })
                .thenExecuteAfter(6, () -> {
                    JsonObject d = bot.describe(0);
                    check(d.getAsJsonArray("keys").isEmpty() && !d.has("keys_ticks_left"), "timed keys did not release: " + d.get("keys"));
                    check(bot.player().position().x - start.get().x > 0.1, "bot did not walk with a timed key: " + start.get() + " → " + bot.player().position());
                    // The rule holds a control indefinitely and a long program; from the second run it exceeds budget and disables itself.
                    now(call(h, "rule.add", params("{\"event\":\"tick\",\"name\":\"gm-test-autopilot\",\"budget_ms\":0.5,\"script\":"
                            + "\"gm.bot('GmTestPilot').act([[hold: 'forward'], [wait: 100]], true); long t = System.nanoTime(); "
                            + "while (System.nanoTime() - t < 15_000_000L) {}\"}")));
                })
                .thenWaitUntil(() -> check(rule(h, "gm-test-autopilot").get("state").getAsString().equals("off"), "rule is still running"))
                .thenExecuteAfter(2, () -> {
                    try {
                        JsonObject d = bot.describe(0);
                        check(d.getAsJsonArray("keys").isEmpty(), "rule disabled but bot still holds " + d.get("keys"));
                        check(!d.has("program") && d.get("programs_queued").getAsInt() == 0, "rule programs were not removed: " + d.get("program"));
                        check(d.getAsJsonObject("last_program").get("state").getAsString().equals("cancelled"), "last program: " + d.get("last_program"));
                        check(hasEvent(h, after, "bot_stopped", "bot", "GmTestPilot"), "bot_stopped is missing from the feed");
                        boolean listed = false;
                        for (JsonElement e : events(h, after).getAsJsonArray("events")) {
                            JsonObject o = e.getAsJsonObject();
                            if (o.get("type").getAsString().equals("rule.off") && o.has("bots_stopped")) listed |= o.get("bots_stopped").toString().contains("GmTestPilot");
                        }
                        check(listed, "rule.off is missing bots_stopped");
                    } finally {
                        now(call(h, "rule.remove", params("{\"name\":\"gm-test-autopilot\"}")));
                        leave(h, "GmTestPilot");
                    }
                })
                .thenSucceed();
    }

    private static JsonObject rule(GameTestHelper h, String name) {
        for (JsonElement e : gm(h).rules().describe()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("name") && o.get("name").getAsString().equals(name)) return o;
        }
        throw new GameTestAssertException("missing rule " + name);
    }

    /**
     * Mine stone at eye level with a diamond pickaxe by holding attack (nothing else within hand reach behind it,
     * so only one block breaks), and place dirt by a use click on the floor.
     */
    @GameTest(template = "floor", batch = "gm_bot_hands", timeoutTicks = 300, skyAccess = true)
    public static void botMinesAndPlaces(GameTestHelper h) {
        BlockPos stone = new BlockPos(12, GROUND + 1, 10), dirt = new BlockPos(12, GROUND, 10);
        h.setBlock(stone, Blocks.STONE);
        Bot bot = spawn(h, "GmTestMiner", 10.5, 10.5, ",\"gamemode\":\"survival\"");
        bot.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        CompletableFuture<JsonElement> mine = act(h, "GmTestMiner",
                "[{\"look_at\":" + block(h, 12, GROUND + 1, 10) + "},{\"hold\":\"attack\"},{\"wait\":20},{\"release\":\"attack\"}]");
        AtomicReference<CompletableFuture<JsonElement>> place = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> finished(mine))
                .thenExecute(() -> {
                    JsonObject r = mine.join().getAsJsonObject();
                    h.assertBlockPresent(Blocks.AIR, stone);
                    check(r.getAsJsonArray("results").get(3).getAsJsonObject().get("blocks_broken").getAsInt() == 1, "broken: " + r);
                    check(bot.player().getMainHandItem().getDamageValue() == 1, "pickaxe did not wear: " + bot.player().getMainHandItem());
                    bot.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 4));
                    place.set(act(h, "GmTestMiner", "[{\"look_at\":" + block(h, 12, GROUND - 1, 10) + "},{\"click\":\"use\"}]"));
                })
                .thenWaitUntil(() -> finished(place.get()))
                .thenExecute(() -> {
                    JsonObject click = place.get().join().getAsJsonObject().getAsJsonArray("results").get(1).getAsJsonObject();
                    h.assertBlockPresent(Blocks.DIRT, dirt);
                    check(click.get("result").getAsString().equals("consume") || click.get("result").getAsString().equals("success"), "click: " + click);
                    check(bot.player().getMainHandItem().getCount() == 3, "dirt in hand: " + bot.player().getMainHandItem());
                    leave(h, "GmTestMiner");
                })
                .thenSucceed();
    }

    /** Chest: click opens the menu, Shift-transfer a slot to inventory, close; the inbox sees the screen. */
    @GameTest(template = "floor", batch = "gm_bot_menu", timeoutTicks = 200, skyAccess = true)
    public static void botUsesChest(GameTestHelper h) {
        BlockPos chest = new BlockPos(12, GROUND, 10);
        h.setBlock(chest, Blocks.CHEST);
        ChestBlockEntity be = Gt.blockEntity(h, chest, ChestBlockEntity.class);
        be.setItem(0, new ItemStack(Items.DIAMOND, 3));
        Bot bot = spawn(h, "GmTestLooter", 10.5, 10.5, ",\"gamemode\":\"survival\"");
        CompletableFuture<JsonElement> open = act(h, "GmTestLooter", "[{\"click\":\"use\",\"at\":" + block(h, 12, GROUND, 10) + "}]");
        AtomicReference<CompletableFuture<JsonElement>> take = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> finished(open))
                .thenExecute(() -> {
                    check(bot.player().containerMenu instanceof ChestMenu, "opened: " + bot.player().containerMenu);
                    JsonObject state = bot.describe(0);
                    check(state.getAsJsonObject("menu").get("type").getAsString().equals("minecraft:generic_9x3"), "menu: " + state.get("menu"));
                    boolean screen = false;
                    for (JsonElement m : state.getAsJsonArray("inbox")) screen |= m.getAsJsonObject().get("kind").getAsString().equals("screen");
                    check(screen, "inbox missing screen: " + state.get("inbox"));
                    take.set(act(h, "GmTestLooter", "[{\"menu\":0,\"mode\":\"quick_move\"},{\"close\":true}]"));
                })
                .thenWaitUntil(() -> finished(take.get()))
                .thenExecute(() -> {
                    check(be.getItem(0).isEmpty(), "remaining in chest: " + be.getItem(0));
                    check(bot.player().getInventory().countItem(Items.DIAMOND) == 3, "bot diamonds: " + bot.player().getInventory().countItem(Items.DIAMOND));
                    check(bot.player().containerMenu == bot.player().inventoryMenu, "menu did not close");
                    leave(h, "GmTestLooter");
                })
                .thenSucceed();
    }

    /**
     * Chat as the bot (player chat in the feed), {@code /gm}, whisper to the bot in its inbox and as a GM event.
     * Cyrillic chat fixtures check Unicode delivery. One message per second does not accumulate spam: 14 messages leave the bot online (vanilla disconnects at 11 consecutive messages).
     */
    @GameTest(template = "floor", batch = "gm_bot_chat", timeoutTicks = 500, skyAccess = true)
    public static void botChatsAndHearsWhispers(GameTestHelper h) {
        long after = feedEnd(h);
        Bot bot = spawn(h, "GmTestTalker", 10.5, 10.5, "");
        StringBuilder steps = new StringBuilder("[{\"chat\":\"привет всем\"},{\"chat\":\"/gm вижу пещеру\"}");
        for (int i = 1; i <= 12; i++) steps.append(",{\"wait\":20},{\"chat\":\"раз ").append(i).append("\"}");
        CompletableFuture<JsonElement> talk = act(h, "GmTestTalker", steps.append("]").toString());
        h.startSequence()
                .thenWaitUntil(() -> finished(talk))
                .thenExecute(() -> new ScriptApi(h.getLevel().getServer(), true, d -> {}).command("msg GmTestTalker тайное слово"))
                .thenIdle(2)
                .thenExecute(() -> {
                    check(hasEvent(h, after, "chat", "text", "привет всем"), "bot chat in the feed: " + events(h, after));
                    check(hasEvent(h, after, "gm", "text", "вижу пещеру"), "bot /gm in the feed: " + events(h, after));
                    check(hasEvent(h, after, "bot_chat", "text", "тайное слово"), "whisper to bot in the feed: " + events(h, after));
                    boolean whisper = false;
                    for (JsonElement m : bot.describe(0).getAsJsonArray("inbox")) {
                        JsonObject o = m.getAsJsonObject();
                        whisper |= o.get("kind").getAsString().equals("whisper") && o.get("text").getAsString().equals("тайное слово");
                    }
                    check(whisper, "bot inbox: " + bot.describe(0).get("inbox"));
                    check(bot.online() && hasEvent(h, after, "chat", "text", "раз 12"), "bot disconnected for spam: " + bot.describe(0).get("inbox"));
                    leave(h, "GmTestTalker");
                })
                .thenSucceed();
    }

    /** Death: the bot respawns automatically (new entity in the same connection), then walks again. */
    @GameTest(template = "floor", batch = "gm_bot_life", timeoutTicks = 300, skyAccess = true)
    public static void botDiesAndRespawns(GameTestHelper h) {
        Bot bot = spawn(h, "GmTestPhoenix", 10.5, 10.5, ",\"gamemode\":\"survival\"");
        // Respawn on this test's floor, facing east along it: without a spawn point a player respawns at a random spot
        // in the world spawn's radius, outside the test, where whatever stands in front of it can stop the walk below.
        BlockPos home = h.absolutePos(new BlockPos(10, GROUND, 10));
        // 1.21.1 takes an angle; 26.x a rotation (yaw and pitch).
        //? if >=26.1 {
        /*String facing = "-90 0";
        *///?} else
        String facing = "-90";
        String spawnpoint = String.format(java.util.Locale.ROOT, "spawnpoint GmTestPhoenix %d %d %d %s", home.getX(), home.getY(), home.getZ(), facing);
        com.google.gson.JsonArray set = ua.zentix.almighty.act.CommandRunner.run(h.getLevel().getServer(), h.getLevel(), Vec3.atBottomCenterOf(home),
                null, java.util.List.of(spawnpoint), true).getAsJsonArray();
        check(set.get(0).getAsJsonObject().get("ok").getAsBoolean(), "/" + spawnpoint + ": " + set);
        ServerPlayer first = bot.player();
        Gt.hurt(first, first.damageSources().genericKill(), Float.MAX_VALUE);
        AtomicReference<CompletableFuture<JsonElement>> walk = new AtomicReference<>();
        AtomicReference<Vec3> start = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> check(bot.player() != first && bot.player().isAlive(), "not respawned yet"))
                .thenExecute(() -> {
                    check(bot.online(), "bot left after death");
                    // The bot's class controls vehicles; respawn (a new entity) must retain that class.
                    check(first instanceof BotPlayer && bot.player() instanceof BotPlayer, "bot class after respawn: " + bot.player().getClass().getName());
                    check(bot.player().blockPosition().closerThan(home, 2), "respawned away from its spawn point " + home + ": " + bot.player().position());
                    start.set(bot.player().position());
                    walk.set(act(h, "GmTestPhoenix", "[{\"hold\":\"forward\"},{\"wait\":10},{\"release\":\"forward\"}]"));
                })
                .thenWaitUntil(() -> finished(walk.get()))
                .thenExecute(() -> {
                    check(bot.player().position().distanceTo(start.get()) > 1.0, "not walking after respawn: from " + start.get()
                            + " to " + bot.player().position() + ", on ground " + bot.player().onGround() + ", " + bot.describe(0));
                    leave(h, "GmTestPhoenix");
                })
                .thenSucceed();
    }

    /**
     * Enter the Nether by command: the bot acknowledges the teleport like a client; otherwise the server would
     * keep it "changing dimension" (invulnerable) until acknowledgment.
     */
    @GameTest(template = "floor", batch = "gm_bot_dim", timeoutTicks = 200, skyAccess = true)
    public static void botFollowsDimensionChange(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether == null) {
            h.succeed();
            return;
        }
        Bot bot = spawn(h, "GmTestTraveler", 10.5, 10.5, ",\"gamemode\":\"creative\"");
        new ScriptApi(server, true, d -> {}).command("execute in minecraft:the_nether run tp GmTestTraveler 0 120 0");
        // The bot acknowledges teleport once the destination chunk is ready; background loading cannot keep up with GameTest ticks.
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) nether.getChunk(i, j);
        h.startSequence()
                .thenWaitUntil(() -> check(bot.player().level() == nether && !bot.player().isChangingDimension(), "still changing dimension"))
                .thenExecute(() -> leave(h, "GmTestTraveler"))
                .thenSucceed();
    }

    /**
     * Riding: the bot gallops east on a horse (the server computes horse movement as the rider's client would)
     * and jumps: holding jump charges power; releasing jumps.
     */
    @GameTest(template = "floor", batch = "gm_bot_horse", timeoutTicks = 300, skyAccess = true)
    public static void botRidesHorse(GameTestHelper h) {
        Bot bot = spawn(h, "GmTestRider", 10.5, 10.5, ",\"gamemode\":\"survival\"");
        Horse horse = h.spawn(Gt.HORSE, new Vec3(10.5, GROUND, 10.5));
        horse.setTamed(true);
        Gt.saddle(horse);
        horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.3);
        horse.getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(0.7);
        horse.setYRot(-90);
        // 26.x: mounting turns the rider to the horse's view (AbstractHorse.addPassenger): its head as of last tick,
        // which a horse spawned this tick has not turned yet.
        //? if >=26.1
        /*horse.yHeadRotO = horse.yHeadRot = horse.getYRot();*/
        check(Gt.ride(bot.player(), horse), "did not mount the horse");
        Vec3 start = horse.position();
        CompletableFuture<JsonElement> ride = act(h, "GmTestRider", "[{\"hold\":\"forward\"},{\"wait\":20},{\"release\":\"all\"}]");
        AtomicReference<CompletableFuture<JsonElement>> jump = new AtomicReference<>();
        AtomicReference<Double> ground = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> finished(ride))
                .thenExecute(() -> {
                    Vec3 d = horse.position().subtract(start);
                    check(d.x > 2.0 && Math.abs(d.z) < 1.0, "horse after 20 ticks: " + d);
                    check(bot.player().getVehicle() == horse, "bot dismounted");
                    JsonObject vehicle = bot.describe(0).getAsJsonObject("vehicle");
                    check(vehicle != null && vehicle.get("type").getAsString().equals("minecraft:horse") && vehicle.get("driver").getAsBoolean(),
                            "vehicle in bot state: " + vehicle);
                })
                .thenWaitUntil(() -> check(horse.onGround(), "horse is not on the ground"))
                .thenExecute(() -> {
                    ground.set(horse.getY());
                    jump.set(act(h, "GmTestRider", "[{\"hold\":\"jump\"},{\"wait\":10},{\"release\":\"jump\"}]"));
                })
                .thenWaitUntil(() -> check(horse.getY() - ground.get() > 1.0, "horse did not jump: height above ground " + (horse.getY() - ground.get())))
                .thenExecute(() -> {
                    check(bot.player().getVehicle() == horse, "bot dismounted during jump");
                    leave(h, "GmTestRider");
                })
                .thenSucceed();
    }

    /**
     * Boat: the bot rows east, reaching client speed after 60 ticks (0.4·(1 − 0.9⁶⁰) blocks per tick;
     * steering after the boat tick without drag correction would give 0.36), then turns left.
     */
    @GameTest(template = "floor", batch = "gm_bot_boat", timeoutTicks = 400, skyAccess = true)
    public static void botRowsBoat(GameTestHelper h) {
        for (int x = 3; x <= 56; x++) {
            for (int z = 6; z <= 14; z++) {
                boolean shore = x == 3 || x == 56 || z == 6 || z == 14;
                h.setBlock(new BlockPos(x, GROUND, z), shore ? Blocks.STONE : Blocks.WATER);
            }
        }
        Bot bot = spawn(h, "GmTestRower", 8.5, 10.5, ",\"gamemode\":\"survival\"");
        Boat boat = h.spawn(Gt.BOAT, new Vec3(8.5, GROUND + 0.5, 10.5));
        boat.setYRot(-90);
        check(Gt.ride(bot.player(), boat), "did not board the boat");
        CompletableFuture<JsonElement> row = act(h, "GmTestRower", "[{\"hold\":\"forward\"},{\"wait\":60}]");
        AtomicReference<Vec3> at = new AtomicReference<>();
        AtomicReference<Float> yaw = new AtomicReference<>();
        AtomicReference<CompletableFuture<JsonElement>> turn = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> finished(row))
                .thenExecute(() -> at.set(boat.position()))
                .thenExecuteAfter(1, () -> {
                    Vec3 d = boat.position().subtract(at.get());
                    double speed = d.horizontalDistance();
                    check(speed > 0.395 && speed < 0.401 && Math.abs(d.z) < 0.01, "boat per tick: " + d + ", speed " + speed + " (client: 0.3993)");
                    yaw.set(boat.getYRot());
                    turn.set(act(h, "GmTestRower", "[{\"release\":\"forward\"},{\"hold\":\"left\"},{\"wait\":20},{\"release\":\"all\"}]"));
                })
                .thenWaitUntil(() -> finished(turn.get()))
                .thenExecute(() -> {
                    float turned = boat.getYRot() - yaw.get();
                    check(turned < -95 && turned > -140, "left turn over 20 ticks: " + turned + "° (client: about −121°)");
                    check(bot.player().getVehicle() == boat, "bot left the boat");
                    leave(h, "GmTestRower");
                })
                .thenSucceed();
    }

    // Immersive Aircraft (a node with its build, compat_ia): planes, airship, its client packet.
    //? if ia {
    /**
     * A bot that leaves while riding comes back in its vehicle (saved with its player data), as a player does: the
     * vehicle leaves with the bot and returns with it; it is never lost.
     */
    @GameTest(template = "floor", batch = "gm_bot_return", timeoutTicks = 200, skyAccess = true)
    public static void botReturnsInItsBoat(GameTestHelper h) {
        Bot bot = spawn(h, "GmTestReturner", 8.5, 10.5, ",\"gamemode\":\"survival\"");
        Boat boat = h.spawn(Gt.BOAT, new Vec3(8.5, GROUND + 0.5, 10.5));
        check(Gt.ride(bot.player(), boat), "did not board the boat");
        java.util.UUID first = boat.getUUID();
        h.startSequence()
                .thenExecuteAfter(2, () -> {
                    leave(h, "GmTestReturner");
                    check(boat.isRemoved(), "the boat stayed when its rider left");
                    // Without a position: the bot comes back where it was saved (a teleport would dismount it).
                    JsonObject r = now(call(h, "bot.spawn", params("{\"name\":\"GmTestReturner\",\"gamemode\":\"survival\"}"))).getAsJsonObject();
                    check(r.get("bot").getAsBoolean(), "bot.spawn: " + r);
                })
                .thenWaitUntil(() -> {
                    Bot back = gm(h).bots().find("GmTestReturner");
                    check(back != null && back.online(), "bot did not come back");
                    check(back.player().getVehicle() instanceof Boat b && b.getUUID().equals(first),
                            "came back without its boat: " + back.player().getVehicle());
                })
                .thenExecute(() -> leave(h, "GmTestReturner"))
                .thenSucceed();
    }

    /**
     * Immersive Aircraft plane: a creative bot (no fuel needed) raises throttle with jump, and the plane rolls
     * east; sneak lowers throttle and brakes; the IA dismount key dismounts on the ground.
     */
    @GameTest(template = "floor", batch = "gm_bot_plane", timeoutTicks = 400, skyAccess = true)
    public static void botFliesPlane(GameTestHelper h) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath("immersive_aircraft", "biplane")).orElse(null);
        check(type != null, "GameTest run is missing Immersive Aircraft");
        Bot bot = spawn(h, "GmTestPilot", 8.5, 10.5, ",\"gamemode\":\"creative\"");
        Entity plane = h.spawn(type, new Vec3(8.5, GROUND, 10.5));
        plane.setYRot(-90);
        check(Gt.ride(bot.player(), plane) && plane.getControllingPassenger() == bot.player(), "did not board the plane as pilot");
        JsonObject vehicle = bot.describe(0).getAsJsonObject("vehicle");
        check(vehicle != null && vehicle.get("type").getAsString().equals("immersive_aircraft:biplane") && vehicle.get("driver").getAsBoolean()
                && vehicle.getAsJsonArray("keys").toString().contains("key.immersive_aircraft.dismount"), "vehicle in bot state: " + vehicle);
        Vec3 start = plane.position();
        CompletableFuture<JsonElement> fly = act(h, "GmTestPilot", "[{\"hold\":\"jump\"},{\"wait\":12},{\"release\":\"jump\"},{\"wait\":40}]");
        AtomicReference<CompletableFuture<JsonElement>> brake = new AtomicReference<>(), out = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> finished(fly))
                .thenExecute(() -> {
                    Vec3 d = plane.position().subtract(start);
                    check(((EngineVehicle) plane).getEngineTarget() == 1.0F, "throttle: " + ((EngineVehicle) plane).getEngineTarget());
                    check(d.x > 4.0 && Math.abs(d.z) < 1.0, "plane takeoff roll: " + d + ", speed " + plane.getDeltaMovement());
                    brake.set(act(h, "GmTestPilot", "[{\"hold\":\"sneak\"},{\"wait\":12},{\"release\":\"all\"}]"));
                })
                .thenWaitUntil(() -> finished(brake.get()))
                .thenExecute(() -> check(((EngineVehicle) plane).getEngineTarget() == 0.0F && bot.player().getVehicle() == plane,
                        "after sneak: throttle " + ((EngineVehicle) plane).getEngineTarget() + ", in plane " + (bot.player().getVehicle() == plane)))
                .thenWaitUntil(() -> check(plane.onGround() && plane.getDeltaMovement().horizontalDistance() < 0.05, "plane is still rolling: " + plane.getDeltaMovement()))
                .thenExecute(() -> out.set(act(h, "GmTestPilot", "[{\"press\":\"key.immersive_aircraft.dismount\"}]")))
                .thenWaitUntil(() -> finished(out.get()))
                .thenExecute(() -> {
                    JsonObject r = out.get().join().getAsJsonObject().getAsJsonArray("results").get(0).getAsJsonObject();
                    check(r.get("result").getAsString().equals("dismounted") && !bot.player().isPassenger(), "dismount: " + r + ", in plane " + bot.player().isPassenger());
                    leave(h, "GmTestPilot");
                })
                .thenSucceed();
    }

    /**
     * Airborne dismount from an IA plane, like the client: the first press warns, the second dismounts; another
     * aircraft starts with a warning again (it is an aircraft property, not a bot property).
     */
    @GameTest(template = "floor", batch = "gm_bot_plane_air", timeoutTicks = 100, skyAccess = true)
    public static void botLeavesPlaneInAir(GameTestHelper h) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath("immersive_aircraft", "biplane")).orElse(null);
        check(type != null, "GameTest run is missing Immersive Aircraft");
        Bot bot = spawn(h, "GmTestJumper", 8.5, 10.5, ",\"gamemode\":\"creative\"");
        Entity first = h.spawn(type, new Vec3(8.5, GROUND + 8, 10.5));
        check(Gt.ride(bot.player(), first) && !first.onGround(), "did not board the airborne plane");
        CompletableFuture<JsonElement> twice = act(h, "GmTestJumper",
                "[{\"press\":\"key.immersive_aircraft.dismount\"},{\"press\":\"key.immersive_aircraft.dismount\"}]");
        AtomicReference<Entity> second = new AtomicReference<>();
        AtomicReference<CompletableFuture<JsonElement>> once = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> finished(twice))
                .thenExecute(() -> {
                    JsonArray results = twice.join().getAsJsonObject().getAsJsonArray("results");
                    check(results.get(0).getAsJsonObject().get("result").getAsString().startsWith("in the air")
                            && results.get(1).getAsJsonObject().get("result").getAsString().equals("dismounted") && !bot.player().isPassenger(),
                            "two airborne presses: " + results + ", in plane " + bot.player().isPassenger());
                    second.set(h.spawn(type, new Vec3(20.5, GROUND + 8, 10.5)));
                    check(Gt.ride(bot.player(), second.get()) && !second.get().onGround(), "did not board the second airborne plane");
                    once.set(act(h, "GmTestJumper", "[{\"press\":\"key.immersive_aircraft.dismount\"}]"));
                })
                .thenWaitUntil(() -> finished(once.get()))
                .thenExecute(() -> {
                    JsonObject r = once.get().join().getAsJsonObject().getAsJsonArray("results").get(0).getAsJsonObject();
                    check(r.get("result").getAsString().startsWith("in the air") && bot.player().getVehicle() == second.get(),
                            "first press in another plane: " + r + ", in plane " + (bot.player().getVehicle() == second.get()));
                    leave(h, "GmTestJumper");
                })
                .thenSucceed();
    }

    /**
     * Long flight: the bot flies an IA airship 500 blocks west toward the platform. The world ticks entities only
     * in the bot's chunk (GameTest simulation distance is 0) and platform chunks retained by GameTest itself;
     * the airship starts 600 blocks east, and the bot remotely boards as pilot, like {@code ride}. Every tick the
     * bot stays seated, its chunks follow the airship, and the world ticks the airship in more than half the ticks.
     * Without seating after the pilot step, only the world airship tick would seat the bot, at the airship's
     * pre-step position in the previous chunk: on the first chunk crossing, the world would stop ticking the
     * airship, the bot would fall out of place, and the airship would fly away without it. Fly west: eastbound,
     * IA's float-computed rider position at large GameTest coordinates rounds across a chunk border before the
     * airship, so the test would pass accidentally even without seating.
     */
    @GameTest(template = "floor", batch = "gm_bot_flight", timeoutTicks = 12000, skyAccess = true)
    public static void botStaysOnFlyingVehicle(GameTestHelper h) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath("immersive_aircraft", "airship")).orElse(null);
        check(type != null, "GameTest run is missing Immersive Aircraft");
        ServerLevel level = h.getLevel();
        Bot bot = spawn(h, "GmTestCaptain", 8.5, 10.5, ",\"gamemode\":\"creative\"");
        // The test generates the airship path and surrounding bot chunks itself (GameTest view distance 2; entity ticking
        // needs two more ready chunks around it): background generation cannot keep up with GameTest ticks, so the world
        // would not tick the airship for a long time, and IA's engine spins up in the world tick. Platform x is 0..63, its chunks end at most at 79;
        // no platforms farther east yet (placed eastward and southward in order).
        ChunkPos path = Gt.chunk(h.absolutePos(new BlockPos(80, GROUND, 10)));
        int px = Mc.chunkX(path), pz = Mc.chunkZ(path);
        for (int x = px; x <= px + 40; x++) for (int z = pz - 4; z <= pz + 4; z++) level.getChunk(x, z);
        // The airship is centered in a chunk: IA seats the pilot half a block to the side; if the bot were in a neighboring chunk,
        // it would never world-tick the airship (and the engine would not spin up); climb first, then fly straight west.
        BlockPos base = h.absolutePos(BlockPos.ZERO), at = h.absolutePos(new BlockPos(680, GROUND, 10));
        Entity ship = h.spawn(type, new Vec3((at.getX() & ~15) + 8.5 - base.getX(), GROUND + 14, (at.getZ() & ~15) + 8.5 - base.getZ()));
        ship.setYRot(90);
        check(Gt.ride(bot.player(), ship) && ship.getControllingPassenger() == bot.player(), "did not board the airship as pilot");
        Vec3 start = ship.position();
        act(h, "GmTestCaptain", "[{\"hold\":\"jump\"},{\"hold\":\"forward\"},{\"wait\":300},{\"release\":\"jump\"},{\"wait\":6000}]");
        AtomicReference<String> broken = new AtomicReference<>();
        int[] outside = {0}, ticked = {0}, lastTick = {ship.tickCount};
        h.startSequence()
                // The bot is seated at tick end (pilot step).
                .thenIdle(1)
                .thenWaitUntil(() -> {
                    if (broken.get() == null) broken.set(offSeat(bot.player(), ship));
                    if (!Gt.forced(level, ship.chunkPosition())) {
                        outside[0]++;
                        if (ship.tickCount != lastTick[0]) ticked[0]++;
                    }
                    lastTick[0] = ship.tickCount;
                    if (broken.get() == null) check(start.x - ship.getX() > 500, "airship traveled " + Math.round(start.x - ship.getX()) + " blocks");
                })
                .thenExecute(() -> {
                    check(broken.get() == null, broken.get());
                    check(ticked[0] * 2 > outside[0], "world ticked the airship for " + ticked[0] + " ticks out of " + outside[0]);
                    leave(h, "GmTestCaptain");
                    ship.discard();
                })
                .thenSucceed();
    }

    /** Why the rider is misplaced: not riding, over 2 blocks from the vehicle, or its player-ticket chunks are not nearby; null means seated. */
    private static String offSeat(ServerPlayer p, Entity vehicle) {
        if (p.getVehicle() != vehicle) return "bot is not riding";
        double d = p.position().distanceTo(vehicle.position());
        if (d > 2.0) return String.format(Locale.ROOT, "bot is %.1f blocks from vehicle: %s, vehicle %s", d, p.position(), vehicle.position());
        ChunkPos tickets = p.getLastSectionPos().chunk(), at = vehicle.chunkPosition();
        if (tickets.getChessboardDistance(at) > 1) return "bot chunks " + tickets + " lagged behind vehicle " + at;
        return null;
    }

    /**
     * Client mod packet: the bot sends Immersive Aircraft dismount bytes like the IA client; the mod dismounts it
     * through its own handler. Unknown packets and trailing bytes are step errors.
     */
    @GameTest(template = "floor", batch = "gm_bot_payload", timeoutTicks = 200, skyAccess = true)
    public static void botSendsModPayload(GameTestHelper h) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath("immersive_aircraft", "biplane")).orElse(null);
        check(type != null, "GameTest run is missing Immersive Aircraft");
        // Bytes as written by the IA client: serverbound packet codec, id supplied separately.
        RegistryFriendlyByteBuf buf = ua.zentix.almighty.platform.Platform.get().registryBuffer(Unpooled.buffer(), h.getLevel().registryAccess());
        ServerboundCustomPayloadPacket.STREAM_CODEC.encode(buf, new ServerboundCustomPayloadPacket(iaDismount()));
        String id = buf.readResourceLocation().toString();
        byte[] body = new byte[buf.readableBytes()];
        buf.readBytes(body);
        buf.release();
        String data = Base64.getEncoder().encodeToString(body);
        byte[] longer = Arrays.copyOf(body, body.length + 1);
        Bot bot = spawn(h, "GmTestCourier", 8.5, 10.5, ",\"gamemode\":\"creative\"");
        Entity plane = h.spawn(type, new Vec3(8.5, GROUND, 10.5));
        check(Gt.ride(bot.player(), plane), "did not board the plane");
        CompletableFuture<JsonElement> unknown = act(h, "GmTestCourier", "[{\"payload\":\"almighty:no_such_payload\",\"data\":\"\"}]");
        AtomicReference<CompletableFuture<JsonElement>> extra = new AtomicReference<>(), send = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> check(unknown.isDone(), "program is still running"))
                .thenExecute(() -> {
                    JsonObject r = unknown.join().getAsJsonObject();
                    check(r.get("state").getAsString().equals("failed") && r.get("error").getAsString().contains("no client packet"), "unknown packet: " + r);
                    extra.set(act(h, "GmTestCourier", "[{\"payload\":\"" + id + "\",\"data\":\"" + Base64.getEncoder().encodeToString(longer) + "\"}]"));
                })
                .thenWaitUntil(() -> check(extra.get().isDone(), "program is still running"))
                .thenExecute(() -> {
                    JsonObject r = extra.get().join().getAsJsonObject();
                    check(r.get("state").getAsString().equals("failed") && r.get("error").getAsString().contains("extra bytes"), "trailing byte: " + r);
                    check(bot.player().getVehicle() == plane, "packet with a trailing byte reached the mod");
                    send.set(act(h, "GmTestCourier", "[{\"payload\":\"" + id + "\",\"data\":\"" + data + "\"}]"));
                })
                .thenWaitUntil(() -> finished(send.get()))
                .thenWaitUntil(() -> check(!bot.player().isPassenger(), "mod did not dismount the bot from packet " + id))
                .thenExecute(() -> leave(h, "GmTestCourier"))
                .thenSucceed();
    }
    //?}

    //? if ia && >=26.1 {
    /*// IA 26.x messages are not payloads themselves: its NeoForge network handler wraps each one (createPayload), as its client does.
    private static net.minecraft.network.protocol.common.custom.CustomPacketPayload iaDismount() {
        Object message = new CommandMessage(CommandMessage.Key.DISMOUNT, Vec3.ZERO);
        try {
            Object handler = ua.zentix.almighty.platform.Reflect.field(immersive_aircraft.cobalt.network.NetworkHandler.class, "INSTANCE").get(null);
            return (net.minecraft.network.protocol.common.custom.CustomPacketPayload) ua.zentix.almighty.platform.Reflect
                    .method(handler.getClass(), "createPayload", immersive_aircraft.cobalt.network.Message.class).invoke(handler, message);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Immersive Aircraft payload: " + e, e);
        }
    }
    *///?} else if ia {
    private static net.minecraft.network.protocol.common.custom.CustomPacketPayload iaDismount() {
        return new CommandMessage(CommandMessage.Key.DISMOUNT, Vec3.ZERO);
    }
    //?}

    /**
     * Vision: distant crosshair hits a gold wall, zombie to the right in front of the wall is visible and in
     * frame, pig behind the wall is hidden; image center is gold, with a zombie box to the right.
     */
    @GameTest(template = "floor", batch = "gm_see", timeoutTicks = 200, skyAccess = true)
    public static void botSeesWallAndMobs(GameTestHelper h) {
        // The picture is lit by the time of day and the weather, and both run through the whole GameTest run (26.x batch
        // order varies from run to run): take it at clear noon, sky brightness updated at once (rain and thunder fade
        // out over ticks, so their levels are cleared too).
        MinecraftServer server = h.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set 6000");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "weather clear");
        h.getLevel().setRainLevel(0);
        h.getLevel().setThunderLevel(0);
        h.getLevel().updateSkyBrightness();
        check(h.getLevel().getSkyDarken() == 0, "noon: sky darkening " + h.getLevel().getSkyDarken());
        for (int y = GROUND; y <= GROUND + 4; y++) for (int z = 4; z <= 16; z++) h.setBlock(new BlockPos(16, y, z), Blocks.GOLD_BLOCK);
        Zombie zombie = h.spawn(Gt.ZOMBIE, new Vec3(13.5, GROUND, 12.5));
        zombie.setNoAi(true);
        Pig pig = h.spawn(Gt.PIG, new Vec3(19.5, GROUND, 10.5));
        pig.setNoAi(true);
        spawn(h, "GmTestWatcher", 10.5, 10.5, ",\"gamemode\":\"creative\"");
        JsonObject quick = now(call(h, "see", params("{\"name\":\"GmTestWatcher\",\"image\":false}"))).getAsJsonObject();
        check(quick.getAsJsonObject("aim_far").get("block").getAsString().equals("minecraft:gold_block"), "far crosshair target: " + quick.get("aim_far"));
        boolean zombieSeen = false, pigHidden = false;
        for (JsonElement e : quick.getAsJsonArray("entities")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("uuid").getAsString().equals(zombie.getUUID().toString())) zombieSeen = o.get("visible").getAsBoolean() && o.get("in_view").getAsBoolean() && o.has("hostile");
            if (o.get("uuid").getAsString().equals(pig.getUUID().toString())) pigHidden = !o.get("visible").getAsBoolean();
        }
        check(zombieSeen, "zombie in front of wall is hidden: " + quick.get("entities"));
        check(pigHidden, "pig behind wall is visible: " + quick.get("entities"));
        // The picture is lit by the world's light: wait until the light engine has lit the air in front of the wall (26.x
        // places tests in freshly generated chunks, where light can lag the structure by a few ticks).
        BlockPos face = h.absolutePos(new BlockPos(15, GROUND + 2, 10));
        AtomicReference<CompletableFuture<JsonElement>> shot = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> check(h.getLevel().getBrightness(net.minecraft.world.level.LightLayer.SKY, face) == 15,
                        "sky light in front of the wall: " + h.getLevel().getBrightness(net.minecraft.world.level.LightLayer.SKY, face)))
                .thenExecute(() -> shot.set(call(h, "see", params("{\"name\":\"GmTestWatcher\",\"width\":64,\"height\":36}"))))
                .thenWaitUntil(() -> eyeEnded(h))
                .thenExecute(() -> {
                    JsonObject r = settled(shot.get());
                    check(r.get("state").getAsString().equals("done"), "snapshot: " + r);
                    BufferedImage img = decode(r.get("png_base64").getAsString());
                    int k = r.getAsJsonObject("legend").get("pixels_per_cell").getAsInt();
                    int centre = img.getRGB(32 * k + k / 2, 18 * k + k / 2) & 0xFFFFFF;
                    check(yellow(centre), "center is not gold: " + Integer.toHexString(centre));
                    // Zombie three blocks ahead and two to the right: purple box in the frame's right half.
                    boolean purple = false;
                    for (int x = 36; x < 64 && !purple; x++) {
                        for (int y = 10; y < 36 && !purple; y++) {
                            int c = img.getRGB(x * k + k / 2, y * k + k / 2) & 0xFFFFFF;
                            purple = (c >> 16 & 255) > (c >> 8 & 255) + 30 && (c & 255) > (c >> 8 & 255) + 30;
                        }
                    }
                    check(purple, "missing zombie box on the right");
                    check(r.getAsJsonObject("legend").getAsJsonObject("seen").has("sounds"), "response is missing bot hearing");
                    leave(h, "GmTestWatcher");
                })
                .thenSucceed();
    }

    /** Eye snapshot in the job queue has finished. */
    private static void eyeEnded(GameTestHelper h) {
        for (JsonElement e : gm(h).jobs().describe()) {
            JsonObject job = e.getAsJsonObject();
            String state = job.get("state").getAsString();
            check(!job.get("kind").getAsString().equals("eye") || !(state.equals("waiting") || state.equals("running")), "snapshot is still running: " + job);
        }
    }

    private static boolean yellow(int c) {
        int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
        return r > b + 40 && g > b + 40;
    }

    private static BufferedImage decode(String base64) {
        try {
            return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        } catch (IOException e) {
            throw new GameTestAssertException("cannot read PNG: " + e);
        }
    }

    /** Snapshot response tail is appended off the server thread (PNG): await it here by wall-clock time. */
    private static JsonObject settled(CompletableFuture<JsonElement> f) {
        try {
            return f.get(30, TimeUnit.SECONDS).getAsJsonObject();
        } catch (TimeoutException e) {
            throw new GameTestAssertException("no response 30 s after job completion");
        } catch (ExecutionException e) {
            throw new GameTestAssertException("call failed: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameTestAssertException("response wait interrupted");
        }
    }

    /**
     * Login far from everyone: load the destination in the background before login (NeoForge teleportation would
     * load the destination chunk immediately); respond once the bot is there. A returning bot without {@code pos}
     * logs in at its saved position: acknowledge its login teleport only once the chunk is ready (acknowledgment
     * moves the player and would also load it immediately).
     */
    @GameTest(template = "floor", batch = "gm_bot_far", timeoutTicks = 6000, skyAccess = true)
    public static void botWaitsForFarChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 base = h.absoluteVec(new Vec3(10.5, GROUND + 20, 10.5));
        double x = base.x + 4000.5, y = base.y, z = base.z;
        int cx = Mth.floor(x) >> 4, cz = Mth.floor(z) >> 4;
        check(level.getChunkSource().getChunkNow(cx, cz) == null, "chunk was already loaded before the test");
        CompletableFuture<JsonElement> spawn = call(h, "bot.spawn", params("{\"name\":\"GmTestFar\",\"pos\":[%.1f,%.1f,%.1f],\"gamemode\":\"creative\"}", x, y, z));
        check(!spawn.isDone() && level.getChunkSource().getChunkNow(cx, cz) == null, "bot login loaded the chunk synchronously");
        // Background loading cannot keep up with GameTest (hundreds of ticks per second): the test loads chunks and ring immediately.
        h.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    check(!spawn.isDone(), "bot logged in before destination loading: " + (spawn.isDone() ? spawn.getNow(null) : ""));
                    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) level.getChunk(cx + i, cz + j);
                })
                .thenWaitUntil(() -> check(spawn.isDone(), "login destination is loading"))
                .thenExecute(() -> {
                    JsonObject r = settled(spawn);
                    check(r.get("bot").getAsBoolean(), "login: " + r);
                    Bot bot = gm(h).bots().find("GmTestFar");
                    check(bot != null && bot.player().position().distanceTo(new Vec3(x, y, z)) < 0.01, "bot is not at login destination: " + (bot == null ? null : bot.player().position()));
                    leave(h, "GmTestFar");
                })
                // Login lease and player ticket removed: the chunk unloads.
                .thenWaitUntil(() -> check(level.getChunkSource().getChunkNow(cx, cz) == null, "chunk is still loaded"))
                .thenExecute(() -> {
                    JsonObject back = now(call(h, "bot.spawn", params("{\"name\":\"GmTestFar\"}"))).getAsJsonObject();
                    check(level.getChunkSource().getChunkNow(cx, cz) == null, "login at saved position loaded the chunk synchronously");
                    check(back.has("loading"), "bot is not awaiting destination loading: " + back);
                    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) level.getChunk(cx + i, cz + j);
                })
                .thenWaitUntil(() -> check(!gm(h).bots().find("GmTestFar").describe(0).has("loading"), "login teleport was not acknowledged"))
                .thenExecute(() -> {
                    Bot bot = gm(h).bots().find("GmTestFar");
                    check(Math.abs(bot.player().getX() - x) < 0.01 && Math.abs(bot.player().getZ() - z) < 0.01, "returned to the wrong position: " + bot.player().position());
                    leave(h, "GmTestFar");
                })
                .thenSucceed();
    }

    /**
     * Player protection: without {@code bots.allow_disguise}, a bot cannot take a whitelisted player's name or
     * log in unmarked; with it, unmarked login works. A real player with the bot's name logs in; the bot yields the name.
     */
    @GameTest(template = "floor", batch = "gm_bot_guard", timeoutTicks = 100, skyAccess = true)
    public static void disguiseNeedsOwnerSwitch(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        GameProfile friend = new GameProfile(UUID.randomUUID(), "GmTestFriend");
        Gt.whitelist(server, friend, true);
        boolean disguise = GmConfig.BOTS_ALLOW_DISGUISE.get();
        ServerPlayer real = null;
        try {
            check(refused(h, "{\"name\":\"GmTestFriend\"}"), "bot took a whitelisted player name");
            check(refused(h, "{\"name\":\"GmTestGhost\",\"marker\":false}"), "bot logged in unmarked");
            // A server player skin's textures property (as Mojang services provide it): owner in profileName/profileId.
            String skin = Base64.getEncoder().encodeToString(("{\"profileId\":\"" + UndashedUuid.toString(Mc.id(friend))
                    + "\",\"profileName\":\"Someone\",\"textures\":{}}").getBytes(StandardCharsets.UTF_8));
            check(refused(h, "{\"name\":\"GmTestGhost\",\"skin\":{\"value\":\"" + skin + "\"}}"), "bot took a whitelisted player skin");
            check(refused(h, "{\"name\":\"GmTestGhost\",\"skin\":{\"value\":\"не base64\"}}"), "unreadable skin property accepted");
            GmConfig.BOTS_ALLOW_DISGUISE.set(true);
            Bot bot = spawn(h, "GmTestFriend", 10.5, 10.5, ",\"marker\":false");
            check(bot.player().getTabListDisplayName() == null, "disguised bot marker: " + bot.player().getTabListDisplayName());
            check(!bot.player().getUUID().equals(Mc.id(friend)), "bot took a player UUID");
            // A real player with the same name logs in: the bot leaves.
            GameProfile profile = new GameProfile(UUID.randomUUID(), "GmTestFriend");
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
            real = new ServerPlayer(server, h.getLevel(), profile, cookie.clientInformation());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            // Client with all server mods: mods send their packets on login (Immersive Aircraft).
            ua.zentix.almighty.platform.Platform.get().configureMockConnection(connection);
            server.getPlayerList().placeNewPlayer(connection, real, cookie);
            // 26.x: as the client's ServerboundPlayerLoadedPacket.
            //? if >=26.1
            /*real.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());*/
            check(gm(h).bots().find("GmTestFriend") == null && !bot.online(), "bot did not yield the name to a player");
        } finally {
            GmConfig.BOTS_ALLOW_DISGUISE.set(disguise);
            Gt.whitelist(server, friend, false);
            if (real != null) server.getPlayerList().remove(real);
            leave(h, "GmTestFriend");
            leave(h, "GmTestGhost");
        }
        h.succeed();
    }

    private static boolean refused(GameTestHelper h, String json) {
        CompletableFuture<JsonElement> f;
        try {
            f = gm(h).dispatch().invoke(ua.zentix.almighty.bridge.Caller.INTERNAL, "bot.spawn", params(json));
        } catch (RpcException e) {
            return true;
        }
        return f.isCompletedExceptionally();
    }
}
