package ua.zentix.airstrikegm.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.util.UndashedUuid;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrikegm.AirstrikeGm;
import ua.zentix.airstrikegm.Api;
import ua.zentix.airstrikegm.GmConfig;
import ua.zentix.airstrikegm.bot.Bot;
import ua.zentix.airstrikegm.bot.Bots;
import ua.zentix.airstrikegm.bridge.Args;
import ua.zentix.airstrikegm.bridge.RpcException;
import ua.zentix.airstrikegm.script.ScriptApi;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static ua.zentix.airstrikegm.gametest.GmGameTests.call;
import static ua.zentix.airstrikegm.gametest.GmGameTests.check;
import static ua.zentix.airstrikegm.gametest.GmGameTests.gm;
import static ua.zentix.airstrikegm.gametest.GmGameTests.now;
import static ua.zentix.airstrikegm.gametest.GmGameTests.params;

/**
 * Боты ведущего в мире: вход, ходьба, копание и постройка, сундук, чат и шёпот, смерть и возрождение, смена
 * измерения, зрение, защита имён игроков. Бот уходит в конце каждой проверки ({@code finally}/последний шаг): боты общие
 * на сервер. Каждая проверка — своей партией.
 */
@GameTestHolder(AirstrikeGm.ID)
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
     * Высота, на которой стоят на площадке: блок структуры GameTest — под ней, отсчёт {@code absolutePos} — от него,
     * поэтому камень пола — на 1, стоять — на 2.
     */
    private static final int GROUND = 2;

    /** Бот у точки площадки; yaw −90 — лицом на восток (+x). */
    private static Bot spawn(GameTestHelper h, String name, double x, double z, String extra) {
        JsonObject r = now(call(h, "bot.spawn", params("{\"name\":\"%s\",\"pos\":%s,\"yaw\":-90%s}", name, vec(h, x, GROUND, z), extra))).getAsJsonObject();
        check(r.get("bot").getAsBoolean(), "bot.spawn: " + r);
        Bot bot = gm(h).bots().find(name);
        check(bot != null && bot.online(), "бот не в списке");
        return bot;
    }

    private static void leave(GameTestHelper h, String name) {
        if (gm(h).bots().find(name) != null) now(call(h, "bot.remove", params("{\"name\":\"%s\"}", name)));
    }

    private static CompletableFuture<JsonElement> act(GameTestHelper h, String name, String actions) {
        return call(h, "bot.act", params("{\"name\":\"%s\",\"actions\":%s,\"wait\":50}", name, actions));
    }

    private static JsonObject finished(CompletableFuture<JsonElement> f) {
        // null — шаг, который её начинает, упал (тест уже провален, последовательность идёт дальше)
        if (f == null || !f.isDone()) throw new GameTestAssertException("программа бота ещё идёт");
        JsonObject r = f.join().getAsJsonObject();
        check(r.get("state").getAsString().equals("done"), "программа: " + r);
        return r;
    }

    private static JsonObject events(GameTestHelper h, long after) {
        try {
            return gm(h).feed().read(after, 1000, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameTestAssertException("лента прервана");
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
     * Вход с пометкой в табе и чате, ходьба бегом на восток физикой сервера, выход: в ленте вход и выход с отметкой бота,
     * в списке игроков бота нет.
     */
    @GameTest(template = "floor", batch = "gm_bot_walk", timeoutTicks = 200, skyAccess = true)
    public static void botWalksAndLeaves(GameTestHelper h) {
        long after = feedEnd(h);
        Bot bot = spawn(h, "GmTestWalker", 10.5, 10.5, ",\"gamemode\":\"survival\"");
        ServerPlayer p = bot.player();
        check(p.getTabListDisplayName() != null && p.getTabListDisplayName().getString().equals("[бот] GmTestWalker"), "таб: " + p.getTabListDisplayName());
        check(p.getDisplayName().getString().contains("[бот]"), "имя в чате: " + p.getDisplayName().getString());
        check(p.getUUID().equals(Bots.uuid("GmTestWalker")) && !p.getUUID().equals(UUIDUtil.createOfflinePlayerUUID("GmTestWalker")), "UUID бота: " + p.getUUID());
        Vec3 start = p.position();
        CompletableFuture<JsonElement> walk = act(h, "GmTestWalker", "[{\"hold\":[\"forward\",\"sprint\"]},{\"wait\":20},{\"release\":\"all\"}]");
        h.startSequence()
                .thenWaitUntil(() -> finished(walk))
                .thenExecute(() -> {
                    Vec3 end = bot.player().position();
                    ServerPlayer q = bot.player();
                    check(end.x - start.x > 3.0 && Math.abs(end.z - start.z) < 0.5 && Math.abs(end.y - start.y) < 0.1,
                            "бег на восток 20 тиков: " + start + " → " + end + ", на земле " + q.onGround() + ", режим " + q.gameMode.getGameModeForPlayer()
                                    + ", под ногами в начале " + q.level().getBlockState(BlockPos.containing(start).below()) + ", в конце " + q.level().getBlockState(BlockPos.containing(end))
                                    + ", бег " + q.isSprinting() + ", программа " + walk.join());
                    check(bot.player().getFoodData().getExhaustionLevel() > 0, "бег не тратит сытость");
                    leave(h, "GmTestWalker");
                    check(h.getLevel().getServer().getPlayerList().getPlayerByName("GmTestWalker") == null, "бот остался в списке игроков");
                    check(hasEvent(h, after, "join", "player", "GmTestWalker") && hasEvent(h, after, "leave", "player", "GmTestWalker"), "вход и выход в ленте");
                    check(hasEvent(h, after, "bot", "bot", "GmTestWalker"), "событие bot при выходе");
                    for (JsonElement e : events(h, after).getAsJsonArray("events")) {
                        JsonObject o = e.getAsJsonObject();
                        if (o.get("type").getAsString().equals("join")) check(o.has("bot"), "вход без отметки бота: " + o);
                    }
                })
                // отпуск аренды снимает тикеты в конце тика
                .thenExecuteAfter(1, () -> check(GmTickets.count(h.getLevel()) == 0, "аренда места входа после выхода бота: тикетов " + GmTickets.count(h.getLevel())))
                .thenSucceed();
    }

    /**
     * Копание камня на уровне глаз алмазной киркой удержанием атаки (за камнем в досягаемости рук пусто — ломается
     * один блок) и постройка земли щелчком использования по полу.
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
                    check(r.getAsJsonArray("results").get(3).getAsJsonObject().get("blocks_broken").getAsInt() == 1, "сломано: " + r);
                    check(bot.player().getMainHandItem().getDamageValue() == 1, "кирка не износилась: " + bot.player().getMainHandItem());
                    bot.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 4));
                    place.set(act(h, "GmTestMiner", "[{\"look_at\":" + block(h, 12, GROUND - 1, 10) + "},{\"click\":\"use\"}]"));
                })
                .thenWaitUntil(() -> finished(place.get()))
                .thenExecute(() -> {
                    JsonObject click = place.get().join().getAsJsonObject().getAsJsonArray("results").get(1).getAsJsonObject();
                    h.assertBlockPresent(Blocks.DIRT, dirt);
                    check(click.get("result").getAsString().equals("consume") || click.get("result").getAsString().equals("success"), "щелчок: " + click);
                    check(bot.player().getMainHandItem().getCount() == 3, "земли в руке: " + bot.player().getMainHandItem());
                    leave(h, "GmTestMiner");
                })
                .thenSucceed();
    }

    /** Сундук: щелчок открывает меню, перенос ячейки с Shift в инвентарь, закрытие; почта видит экран. */
    @GameTest(template = "floor", batch = "gm_bot_menu", timeoutTicks = 200, skyAccess = true)
    public static void botUsesChest(GameTestHelper h) {
        BlockPos chest = new BlockPos(12, GROUND, 10);
        h.setBlock(chest, Blocks.CHEST);
        ChestBlockEntity be = h.getBlockEntity(chest);
        be.setItem(0, new ItemStack(Items.DIAMOND, 3));
        Bot bot = spawn(h, "GmTestLooter", 10.5, 10.5, ",\"gamemode\":\"survival\"");
        CompletableFuture<JsonElement> open = act(h, "GmTestLooter", "[{\"click\":\"use\",\"at\":" + block(h, 12, GROUND, 10) + "}]");
        AtomicReference<CompletableFuture<JsonElement>> take = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> finished(open))
                .thenExecute(() -> {
                    check(bot.player().containerMenu instanceof ChestMenu, "открыто: " + bot.player().containerMenu);
                    JsonObject state = bot.describe(0);
                    check(state.getAsJsonObject("menu").get("type").getAsString().equals("minecraft:generic_9x3"), "меню: " + state.get("menu"));
                    boolean screen = false;
                    for (JsonElement m : state.getAsJsonArray("inbox")) screen |= m.getAsJsonObject().get("kind").getAsString().equals("screen");
                    check(screen, "почта без экрана: " + state.get("inbox"));
                    take.set(act(h, "GmTestLooter", "[{\"menu\":0,\"mode\":\"quick_move\"},{\"close\":true}]"));
                })
                .thenWaitUntil(() -> finished(take.get()))
                .thenExecute(() -> {
                    check(be.getItem(0).isEmpty(), "в сундуке осталось: " + be.getItem(0));
                    check(bot.player().getInventory().countItem(Items.DIAMOND) == 3, "алмазов у бота: " + bot.player().getInventory().countItem(Items.DIAMOND));
                    check(bot.player().containerMenu == bot.player().inventoryMenu, "меню не закрыто");
                    leave(h, "GmTestLooter");
                })
                .thenSucceed();
    }

    /**
     * Чат от имени бота (в ленте как чат игрока), команда {@code /gm}, шёпот боту — в его почте и событием ведущему.
     * Сообщение раз в секунду не копит счётчик спама: 14 сообщений — бот в игре (ваниль отключает на 11-м подряд).
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
                    check(hasEvent(h, after, "chat", "text", "привет всем"), "чат бота в ленте: " + events(h, after));
                    check(hasEvent(h, after, "gm", "text", "вижу пещеру"), "/gm бота в ленте: " + events(h, after));
                    check(hasEvent(h, after, "bot_chat", "text", "тайное слово"), "шёпот боту в ленте: " + events(h, after));
                    boolean whisper = false;
                    for (JsonElement m : bot.describe(0).getAsJsonArray("inbox")) {
                        JsonObject o = m.getAsJsonObject();
                        whisper |= o.get("kind").getAsString().equals("whisper") && o.get("text").getAsString().equals("тайное слово");
                    }
                    check(whisper, "почта бота: " + bot.describe(0).get("inbox"));
                    check(bot.online() && hasEvent(h, after, "chat", "text", "раз 12"), "бота отключили за спам: " + bot.describe(0).get("inbox"));
                    leave(h, "GmTestTalker");
                })
                .thenSucceed();
    }

    /** Смерть: бот возрождается сам (новая сущность в том же соединении) и снова ходит. */
    @GameTest(template = "floor", batch = "gm_bot_life", timeoutTicks = 300, skyAccess = true)
    public static void botDiesAndRespawns(GameTestHelper h) {
        Bot bot = spawn(h, "GmTestPhoenix", 10.5, 10.5, ",\"gamemode\":\"survival\"");
        ServerPlayer first = bot.player();
        first.hurt(first.damageSources().genericKill(), Float.MAX_VALUE);
        AtomicReference<CompletableFuture<JsonElement>> walk = new AtomicReference<>();
        AtomicReference<Vec3> start = new AtomicReference<>();
        h.startSequence()
                .thenWaitUntil(() -> check(bot.player() != first && bot.player().isAlive(), "ещё не возродился"))
                .thenExecute(() -> {
                    check(bot.online(), "бот ушёл после смерти");
                    start.set(bot.player().position());
                    walk.set(act(h, "GmTestPhoenix", "[{\"hold\":\"forward\"},{\"wait\":10},{\"release\":\"forward\"}]"));
                })
                .thenWaitUntil(() -> finished(walk.get()))
                .thenExecute(() -> {
                    check(bot.player().position().distanceTo(start.get()) > 1.0, "после возрождения не ходит");
                    leave(h, "GmTestPhoenix");
                })
                .thenSucceed();
    }

    /**
     * Переход в Незер командой: бот подтверждает телепорт, как клиент, — иначе сервер держал бы его «в переходе»
     * (неуязвимым) до подтверждения.
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
        // телепорт бот подтверждает, когда чанк места готов; фоновая загрузка не успевает к тикам GameTest
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) nether.getChunk(i, j);
        h.startSequence()
                .thenWaitUntil(() -> check(bot.player().level() == nether && !bot.player().isChangingDimension(), "ещё в переходе"))
                .thenExecute(() -> leave(h, "GmTestTraveler"))
                .thenSucceed();
    }

    /**
     * Зрение: прицел вдаль — золотая стена, зомби справа перед стеной виден и в кадре, свинья за стеной не видна;
     * картинка — в центре цвет золота, справа — рамка зомби.
     */
    @GameTest(template = "floor", batch = "gm_see", timeoutTicks = 200, skyAccess = true)
    public static void botSeesWallAndMobs(GameTestHelper h) {
        for (int y = GROUND; y <= GROUND + 4; y++) for (int z = 4; z <= 16; z++) h.setBlock(new BlockPos(16, y, z), Blocks.GOLD_BLOCK);
        Zombie zombie = h.spawn(EntityType.ZOMBIE, new Vec3(13.5, GROUND, 12.5));
        zombie.setNoAi(true);
        Pig pig = h.spawn(EntityType.PIG, new Vec3(19.5, GROUND, 10.5));
        pig.setNoAi(true);
        spawn(h, "GmTestWatcher", 10.5, 10.5, ",\"gamemode\":\"creative\"");
        JsonObject quick = now(call(h, "see", params("{\"name\":\"GmTestWatcher\",\"image\":false}"))).getAsJsonObject();
        check(quick.getAsJsonObject("aim_far").get("block").getAsString().equals("minecraft:gold_block"), "дальний прицел: " + quick.get("aim_far"));
        boolean zombieSeen = false, pigHidden = false;
        for (JsonElement e : quick.getAsJsonArray("entities")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("uuid").getAsString().equals(zombie.getUUID().toString())) zombieSeen = o.get("visible").getAsBoolean() && o.get("in_view").getAsBoolean() && o.has("hostile");
            if (o.get("uuid").getAsString().equals(pig.getUUID().toString())) pigHidden = !o.get("visible").getAsBoolean();
        }
        check(zombieSeen, "зомби перед стеной не виден: " + quick.get("entities"));
        check(pigHidden, "свинья за стеной видна: " + quick.get("entities"));
        CompletableFuture<JsonElement> picture = call(h, "see", params("{\"name\":\"GmTestWatcher\",\"width\":64,\"height\":36}"));
        h.startSequence()
                .thenWaitUntil(() -> eyeEnded(h))
                .thenExecute(() -> {
                    JsonObject r = settled(picture);
                    check(r.get("state").getAsString().equals("done"), "снимок: " + r);
                    BufferedImage img = decode(r.get("png_base64").getAsString());
                    int k = r.getAsJsonObject("legend").get("pixels_per_cell").getAsInt();
                    int centre = img.getRGB(32 * k + k / 2, 18 * k + k / 2) & 0xFFFFFF;
                    check(yellow(centre), "в центре не золото: " + Integer.toHexString(centre));
                    // зомби — в трёх блоках впереди и в двух справа: фиолетовая рамка в правой половине кадра
                    boolean purple = false;
                    for (int x = 36; x < 64 && !purple; x++) {
                        for (int y = 10; y < 36 && !purple; y++) {
                            int c = img.getRGB(x * k + k / 2, y * k + k / 2) & 0xFFFFFF;
                            purple = (c >> 16 & 255) > (c >> 8 & 255) + 30 && (c & 255) > (c >> 8 & 255) + 30;
                        }
                    }
                    check(purple, "рамки зомби справа нет");
                    check(r.getAsJsonObject("legend").getAsJsonObject("seen").has("sounds"), "у бота нет слуха в ответе");
                    leave(h, "GmTestWatcher");
                })
                .thenSucceed();
    }

    /** Снимок глаза в очереди работ кончился. */
    private static void eyeEnded(GameTestHelper h) {
        for (JsonElement e : gm(h).jobs().describe()) {
            JsonObject job = e.getAsJsonObject();
            String state = job.get("state").getAsString();
            check(!job.get("kind").getAsString().equals("eye") || !(state.equals("waiting") || state.equals("running")), "снимок ещё идёт: " + job);
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
            throw new GameTestAssertException("PNG не читается: " + e);
        }
    }

    /** Хвост ответа снимка дописывает поток вне сервера (PNG): ждать его здесь, по часам. */
    private static JsonObject settled(CompletableFuture<JsonElement> f) {
        try {
            return f.get(30, TimeUnit.SECONDS).getAsJsonObject();
        } catch (TimeoutException e) {
            throw new GameTestAssertException("ответа нет 30 с после конца работы");
        } catch (ExecutionException e) {
            throw new GameTestAssertException("вызов упал: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameTestAssertException("ожидание ответа прервано");
        }
    }

    /**
     * Вход вдали от всех: место загружается в фоне до входа (перенос игрока в NeoForge грузил бы чанк назначения
     * сразу), ответ — когда бот уже там. Вернувшийся без {@code pos} бот входит на сохранённое место: телепорт входа он
     * подтверждает, только когда чанк там готов (подтверждение переносит игрока и тоже грузило бы его сразу).
     */
    @GameTest(template = "floor", batch = "gm_bot_far", timeoutTicks = 6000, skyAccess = true)
    public static void botWaitsForFarChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 base = h.absoluteVec(new Vec3(10.5, GROUND + 20, 10.5));
        double x = base.x + 4000.5, y = base.y, z = base.z;
        int cx = Mth.floor(x) >> 4, cz = Mth.floor(z) >> 4;
        check(level.getChunkSource().getChunkNow(cx, cz) == null, "чанк уже загружен до проверки");
        CompletableFuture<JsonElement> spawn = call(h, "bot.spawn", params("{\"name\":\"GmTestFar\",\"pos\":[%.1f,%.1f,%.1f],\"gamemode\":\"creative\"}", x, y, z));
        check(!spawn.isDone() && level.getChunkSource().getChunkNow(cx, cz) == null, "вход бота загрузил чанк сразу");
        // фоновая загрузка не успевает к тикам GameTest (сотни тиков в секунду): чанки с кольцом — тестом сразу
        h.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    check(!spawn.isDone(), "бот вошёл до загрузки места: " + (spawn.isDone() ? spawn.getNow(null) : ""));
                    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) level.getChunk(cx + i, cz + j);
                })
                .thenWaitUntil(() -> check(spawn.isDone(), "место входа грузится"))
                .thenExecute(() -> {
                    JsonObject r = settled(spawn);
                    check(r.get("bot").getAsBoolean(), "вход: " + r);
                    Bot bot = gm(h).bots().find("GmTestFar");
                    check(bot != null && bot.player().position().distanceTo(new Vec3(x, y, z)) < 0.01, "бот не на месте входа: " + (bot == null ? null : bot.player().position()));
                    leave(h, "GmTestFar");
                })
                // аренда места и тикет игрока сняты — чанк выгружается
                .thenWaitUntil(() -> check(level.getChunkSource().getChunkNow(cx, cz) == null, "чанк ещё загружен"))
                .thenExecute(() -> {
                    JsonObject back = now(call(h, "bot.spawn", params("{\"name\":\"GmTestFar\"}"))).getAsJsonObject();
                    check(level.getChunkSource().getChunkNow(cx, cz) == null, "вход на сохранённое место загрузил чанк сразу");
                    check(back.has("loading"), "бот не ждёт загрузки места: " + back);
                    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) level.getChunk(cx + i, cz + j);
                })
                .thenWaitUntil(() -> check(!gm(h).bots().find("GmTestFar").describe(0).has("loading"), "телепорт входа не подтверждён"))
                .thenExecute(() -> {
                    Bot bot = gm(h).bots().find("GmTestFar");
                    check(Math.abs(bot.player().getX() - x) < 0.01 && Math.abs(bot.player().getZ() - z) < 0.01, "вернулся не туда: " + bot.player().position());
                    leave(h, "GmTestFar");
                })
                .thenSucceed();
    }

    /**
     * Защита игроков: без {@code bots.allow_disguise} бот не берёт имя игрока из белого списка и не входит без пометки;
     * с ним — входит без пометки. Настоящий игрок с именем бота заходит — бот уступает имя.
     */
    @GameTest(template = "floor", batch = "gm_bot_guard", timeoutTicks = 100, skyAccess = true)
    public static void disguiseNeedsOwnerSwitch(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        GameProfile friend = new GameProfile(UUID.randomUUID(), "GmTestFriend");
        server.getPlayerList().getWhiteList().add(new UserWhiteListEntry(friend));
        boolean disguise = GmConfig.BOTS_ALLOW_DISGUISE.get();
        ServerPlayer real = null;
        try {
            check(refused(h, "{\"name\":\"GmTestFriend\"}"), "бот взял имя игрока из белого списка");
            check(refused(h, "{\"name\":\"GmTestGhost\",\"marker\":false}"), "бот вошёл без пометки");
            // свойство textures скина игрока сервера (так его отдают службы Mojang): владелец — в profileName/profileId
            String skin = Base64.getEncoder().encodeToString(("{\"profileId\":\"" + UndashedUuid.toString(friend.getId())
                    + "\",\"profileName\":\"Someone\",\"textures\":{}}").getBytes(StandardCharsets.UTF_8));
            check(refused(h, "{\"name\":\"GmTestGhost\",\"skin\":{\"value\":\"" + skin + "\"}}"), "бот надел скин игрока из белого списка");
            check(refused(h, "{\"name\":\"GmTestGhost\",\"skin\":{\"value\":\"не base64\"}}"), "непонятное свойство скина принято");
            GmConfig.BOTS_ALLOW_DISGUISE.set(true);
            Bot bot = spawn(h, "GmTestFriend", 10.5, 10.5, ",\"marker\":false");
            check(bot.player().getTabListDisplayName() == null, "пометка у переодетого бота: " + bot.player().getTabListDisplayName());
            check(!bot.player().getUUID().equals(friend.getId()), "бот взял UUID игрока");
            // настоящий игрок с тем же именем заходит: бот уходит
            GameProfile profile = new GameProfile(UUID.randomUUID(), "GmTestFriend");
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
            real = new ServerPlayer(server, h.getLevel(), profile, cookie.clientInformation());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, real, cookie);
            check(gm(h).bots().find("GmTestFriend") == null && !bot.online(), "бот не уступил имя игроку");
        } finally {
            GmConfig.BOTS_ALLOW_DISGUISE.set(disguise);
            server.getPlayerList().getWhiteList().remove(friend);
            if (real != null) server.getPlayerList().remove(real);
            leave(h, "GmTestFriend");
            leave(h, "GmTestGhost");
        }
        h.succeed();
    }

    private static boolean refused(GameTestHelper h, String json) {
        CompletableFuture<JsonElement> f;
        try {
            f = Api.methods(gm(h)).get("bot.spawn").call(new Args(params(json)));
        } catch (RpcException e) {
            return true;
        }
        return f.isCompletedExceptionally();
    }
}
