package ua.zentix.airstrikegm.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.airstrikegm.AirstrikeGm;
import ua.zentix.airstrikegm.Api;
import ua.zentix.airstrikegm.GmConfig;
import ua.zentix.airstrikegm.GmServer;
import ua.zentix.airstrikegm.bridge.Args;
import ua.zentix.airstrikegm.bridge.RpcException;
import ua.zentix.airstrikegm.view.Png;
import ua.zentix.airstrikegm.world.Areas;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Мод ведущего в мире: методы моста так, как их зовёт мост (параметры JSON), но из потока сервера — без HTTP. Каждая
 * проверка — своей партией: часы работ и тикеты ведущего общие на сервер.
 */
@GameTestHolder(AirstrikeGm.ID)
@PrefixGameTestTemplate(false)
public final class GmGameTests {
    private GmGameTests() {}

    static GmServer gm(GameTestHelper h) {
        GmServer gm = GmServer.of(h.getLevel().getServer());
        if (gm == null) throw new GameTestAssertException("ведущий не запущен");
        return gm;
    }

    static CompletableFuture<JsonElement> call(GameTestHelper h, String method, JsonObject params) {
        try {
            return Api.methods(gm(h)).get(method).call(new Args(params));
        } catch (RpcException e) {
            throw new GameTestAssertException(method + ": " + e.getMessage());
        }
    }

    static JsonObject params(String json, Object... args) {
        return JsonParser.parseString(String.format(Locale.ROOT, json, args)).getAsJsonObject();
    }

    private static String at(GameTestHelper h, int x, int y, int z) {
        BlockPos p = h.absolutePos(new BlockPos(x, y, z));
        return "[" + p.getX() + "," + p.getY() + "," + p.getZ() + "]";
    }

    private static void ready(CompletableFuture<JsonElement> f) {
        if (!f.isDone()) throw new GameTestAssertException("ещё не готово");
    }

    /** Вызов, который выполнился сразу (поток сервера зовёт метод из теста — без очереди задач). */
    static JsonElement now(CompletableFuture<JsonElement> f) {
        check(f.isDone(), "вызов из потока сервера не выполнился сразу");
        return f.join();
    }

    private static JsonObject result(CompletableFuture<JsonElement> f) {
        check(f.isDone(), "вызов ещё не кончился");
        try {
            return f.join().getAsJsonObject();
        } catch (CompletionException e) {
            throw new GameTestAssertException("вызов упал: " + e.getCause());
        }
    }

    /**
     * Ответ, который дописывает поток вне сервера (картинку снимка кодирует {@code thenApplyAsync}): сервер GameTest
     * пробегает тики без пауз, и срок теста в тиках выходил раньше, чем этот поток успевал. Работу ждать по тикам
     * ({@link #viewsEnded}), а этот хвост — здесь, с запасом по часам: потока сервера он не ждёт.
     */
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

    /** Снимки ({@code map}, {@code look}) в очереди работ кончились. */
    private static void viewsEnded(GameTestHelper h) {
        for (JsonElement e : gm(h).jobs().describe()) {
            JsonObject job = e.getAsJsonObject();
            String kind = job.get("kind").getAsString(), state = job.get("state").getAsString();
            boolean view = kind.equals("map") || kind.equals("look");
            check(!view || !(state.equals("waiting") || state.equals("running")), "снимок ещё идёт: " + job);
        }
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new GameTestAssertException(message);
    }

    /** Чанки рамки с кольцом соседей — сразу полными: фоновая загрузка не успевает к тикам GameTest. */
    private static void loadNow(GameTestHelper h, int x1, int z1, int x2, int z2) {
        BlockPos a = h.absolutePos(new BlockPos(x1, 0, z1)), b = h.absolutePos(new BlockPos(x2, 0, z2));
        for (int cx = (Math.min(a.getX(), b.getX()) >> 4) - 1; cx <= (Math.max(a.getX(), b.getX()) >> 4) + 1; cx++) {
            for (int cz = (Math.min(a.getZ(), b.getZ()) >> 4) - 1; cz <= (Math.max(a.getZ(), b.getZ()) >> 4) + 1; cz++) {
                h.getLevel().getChunk(cx, cz);
            }
        }
    }

    /**
     * Постройка 16×4×16 идёт порциями по тикам (часы считают по 1 мс на вызов: 3 порции за тик), ставит места как
     * {@code /fill}: сундук заменён без выпавших предметов; откат возвращает и блоки, и содержимое сундука; второй откат
     * той же постройки — ошибка.
     */
    @GameTest(template = "floor", batch = "gm_build", timeoutTicks = 400, skyAccess = true)
    public static void buildRunsAcrossTicksAndUndoRestores(GameTestHelper h) {
        LongSupplier clock = GmServer.clock;
        AtomicLong t = new AtomicLong();
        GmServer.clock = () -> t.addAndGet(1_000_000L);
        BlockPos chest = new BlockPos(20, 2, 20), gold = new BlockPos(21, 2, 20);
        h.setBlock(chest, Blocks.CHEST);
        ChestBlockEntity be = h.getBlockEntity(chest);
        be.setItem(0, new ItemStack(Items.DIAMOND, 3));
        h.setBlock(gold, Blocks.GOLD_BLOCK);
        loadNow(h, 16, 16, 31, 31);
        CompletableFuture<JsonElement> build = call(h, "build", params(
                "{\"ops\":[{\"op\":\"fill\",\"from\":%s,\"to\":%s,\"block\":\"minecraft:stone\"}],\"wait\":50}",
                at(h, 16, 2, 16), at(h, 31, 5, 31)));
        AtomicReference<CompletableFuture<JsonElement>> undo = new AtomicReference<>();
        AtomicLong id = new AtomicLong();
        h.startSequence()
                .thenExecuteAfter(2, () -> {
                    JsonObject job = gm(h).jobs().describe().get(0).getAsJsonObject();
                    check(job.get("kind").getAsString().equals("build"), "первая работа — не постройка: " + job);
                    long done = job.get("done").getAsLong();
                    check(job.get("state").getAsString().equals("running") && done > 0 && done < 1024,
                            "постройка не идёт порциями: " + job);
                })
                .thenWaitUntil(() -> ready(build))
                .thenExecute(() -> {
                    JsonObject r = result(build);
                    check(r.get("state").getAsString().equals("done"), "постройка: " + r);
                    check(r.get("changed").getAsLong() == 1024, "изменено не 1024: " + r);
                    id.set(r.get("id").getAsLong());
                    for (int x = 16; x <= 31; x++) for (int y = 2; y <= 5; y++) for (int z = 16; z <= 31; z++) h.assertBlockPresent(Blocks.STONE, x, y, z);
                    AABB box = new AABB(h.absolutePos(new BlockPos(15, 1, 15)).getCenter(), h.absolutePos(new BlockPos(32, 7, 32)).getCenter());
                    check(h.getLevel().getEntitiesOfClass(ItemEntity.class, box).isEmpty(), "из сундука выпали предметы");
                    undo.set(call(h, "undo", params("{\"job\":%d,\"wait\":50}", id.get())));
                })
                .thenWaitUntil(() -> ready(undo.get()))
                .thenExecute(() -> {
                    JsonObject r = result(undo.get());
                    check(r.get("state").getAsString().equals("done") && r.get("undo_of").getAsLong() == id.get(), "откат: " + r);
                    // в истории — только снимок самого отката: снимок постройки он вернул и отпустил
                    long undoRetained = gm(h).jobs().get(r.get("id").getAsLong()).retained();
                    check(gm(h).jobs().get(id.get()).retained() == 0 && undoRetained == r.get("changed").getAsLong(),
                            "держится мест: постройка " + gm(h).jobs().get(id.get()).retained() + ", откат " + undoRetained + ", ждали 0 и " + r.get("changed"));
                    h.assertBlockPresent(Blocks.CHEST, chest);
                    ChestBlockEntity back = h.getBlockEntity(chest);
                    check(back.getItem(0).is(Items.DIAMOND) && back.getItem(0).getCount() == 3, "в сундуке после отката: " + back.getItem(0));
                    h.assertBlockPresent(Blocks.GOLD_BLOCK, gold);
                    h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 2, 16));
                    h.assertBlockPresent(Blocks.AIR, new BlockPos(31, 5, 31));
                    CompletableFuture<JsonElement> again = call(h, "undo", params("{\"job\":%d}", id.get()));
                    check(again.isCompletedExceptionally(), "второй откат той же постройки прошёл");
                    try {
                        again.join();
                    } catch (CompletionException e) {
                        check(e.getCause() instanceof RpcException rpc && rpc.getMessage().contains("откачена"), "второй откат: " + e.getCause());
                    }
                    GmServer.clock = clock;
                })
                .thenSucceed();
    }

    /**
     * Нетронутые места (воздух на воздух) тоже идут в счёт порции: пустая коробка 16×4×16 не ставится одной порцией
     * в одном тике.
     */
    @GameTest(template = "floor", batch = "gm_build_air", timeoutTicks = 400, skyAccess = true)
    public static void unchangedPlacesCountTowardUnits(GameTestHelper h) {
        LongSupplier clock = GmServer.clock;
        AtomicLong t = new AtomicLong();
        GmServer.clock = () -> t.addAndGet(1_000_000L);
        loadNow(h, 16, 16, 31, 31);
        CompletableFuture<JsonElement> build = call(h, "build", params(
                "{\"ops\":[{\"op\":\"fill\",\"from\":%s,\"to\":%s,\"block\":\"minecraft:air\"}],\"wait\":50}",
                at(h, 16, 6, 16), at(h, 31, 9, 31)));
        h.startSequence()
                .thenExecuteAfter(2, () -> {
                    JsonObject job = gm(h).jobs().describe().get(0).getAsJsonObject();
                    long done = job.get("done").getAsLong();
                    check(job.get("kind").getAsString().equals("build") && done > 0 && done < 1024, "пустая коробка не идёт порциями: " + job);
                })
                .thenWaitUntil(() -> ready(build))
                .thenExecute(() -> {
                    JsonObject r = result(build);
                    check(r.get("state").getAsString().equals("done") && r.get("unchanged").getAsLong() == 1024 && r.get("changed").getAsLong() == 0,
                            "постройка воздуха: " + r);
                    GmServer.clock = clock;
                })
                .thenSucceed();
    }

    /** keep — только на место воздуха; hollow — оболочка блоком, внутри воздух; outline внутренность не трогает. */
    @GameTest(template = "floor", batch = "gm_modes", timeoutTicks = 200, skyAccess = true)
    public static void fillModes(GameTestHelper h) {
        h.setBlock(new BlockPos(10, 3, 10), Blocks.DIRT);
        h.setBlock(new BlockPos(32, 4, 32), Blocks.DIRT);
        loadNow(h, 8, 8, 34, 34);
        CompletableFuture<JsonElement> build = call(h, "build", params("""
                {"ops":[
                  {"op":"fill","from":%s,"to":%s,"block":"minecraft:stone","mode":"keep"},
                  {"op":"fill","from":%s,"to":%s,"block":"minecraft:glass","mode":"hollow"},
                  {"op":"fill","from":%s,"to":%s,"block":"minecraft:oak_planks","mode":"outline"}
                ],"wait":50}""",
                at(h, 8, 3, 8), at(h, 12, 3, 12), at(h, 20, 2, 20), at(h, 24, 6, 24), at(h, 30, 2, 30), at(h, 34, 6, 34)));
        h.startSequence()
                .thenWaitUntil(() -> ready(build))
                .thenExecute(() -> {
                    JsonObject r = result(build);
                    check(r.get("state").getAsString().equals("done") && r.get("kept").getAsLong() == 1, "постройка: " + r);
                    h.assertBlockPresent(Blocks.DIRT, new BlockPos(10, 3, 10));
                    h.assertBlockPresent(Blocks.STONE, new BlockPos(8, 3, 8));
                    h.assertBlockPresent(Blocks.GLASS, new BlockPos(20, 4, 22));
                    h.assertBlockPresent(Blocks.AIR, new BlockPos(22, 4, 22));
                    h.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(34, 4, 32));
                    h.assertBlockPresent(Blocks.DIRT, new BlockPos(32, 4, 32));
                })
                .thenSucceed();
    }

    /** Блоки текстом и обратно: {@code blocks} → операция {@code layers} в другом месте → тот же текст. */
    @GameTest(template = "floor", batch = "gm_layers", timeoutTicks = 200, skyAccess = true)
    public static void blocksRoundTripThroughLayers(GameTestHelper h) {
        h.setBlock(new BlockPos(8, 2, 8), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        h.setBlock(new BlockPos(9, 2, 8), Blocks.STONE);
        h.setBlock(new BlockPos(10, 3, 10), Blocks.GLASS);
        h.setBlock(new BlockPos(9, 2, 10), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        JsonObject source = result(call(h, "blocks", params("{\"from\":%s,\"to\":%s}", at(h, 8, 2, 8), at(h, 10, 3, 10))));
        check(source.getAsJsonObject("palette").size() == 4, "палитра: " + source.get("palette"));
        JsonObject palette = source.getAsJsonObject("palette").deepCopy();
        palette.addProperty(".", "minecraft:air");
        JsonObject layers = new JsonObject();
        layers.addProperty("op", "layers");
        layers.add("origin", JsonParser.parseString(at(h, 40, 2, 40)));
        layers.add("palette", palette);
        layers.add("layers", source.get("layers"));
        JsonArray ops = new JsonArray();
        ops.add(layers);
        JsonObject buildParams = new JsonObject();
        buildParams.add("ops", ops);
        buildParams.addProperty("wait", 50);
        loadNow(h, 40, 40, 42, 42);
        CompletableFuture<JsonElement> build = call(h, "build", buildParams);
        h.startSequence()
                .thenWaitUntil(() -> ready(build))
                .thenExecute(() -> {
                    check(result(build).get("state").getAsString().equals("done"), "постройка: " + result(build));
                    JsonObject copy = result(call(h, "blocks", params("{\"from\":%s,\"to\":%s}", at(h, 40, 2, 40), at(h, 42, 3, 42))));
                    check(copy.get("palette").equals(source.get("palette")), "палитра копии: " + copy.get("palette") + " ≠ " + source.get("palette"));
                    check(copy.get("layers").equals(source.get("layers")), "слои копии: " + copy.get("layers") + " ≠ " + source.get("layers"));
                })
                .thenSucceed();
    }

    /**
     * Команды с выводом (ошибка — {@code ok: false} и текст), функция из строк без файла и без {@code /reload} с
     * макросом; ошибка разбора называет строку.
     */
    @GameTest(template = "floor", batch = "gm_commands", skyAccess = true)
    public static void commandsAndFunctions(GameTestHelper h) {
        JsonArray out = now(call(h, "command", params("{\"commands\":[\"time query daytime\",\"/setblock %s minecraft:emerald_block\",\"no_such_command\"]}",
                at(h, 4, 2, 4).replaceAll("[\\[\\],]", " ").strip()))).getAsJsonArray();
        JsonObject time = out.get(0).getAsJsonObject();
        check(time.get("ok").getAsBoolean() && time.has("output"), "time query: " + time);
        check(out.get(1).getAsJsonObject().get("ok").getAsBoolean(), "setblock: " + out.get(1));
        h.assertBlockPresent(Blocks.EMERALD_BLOCK, new BlockPos(4, 2, 4));
        JsonObject bad = out.get(2).getAsJsonObject();
        check(!bad.get("ok").getAsBoolean() && bad.has("errors"), "неизвестная команда: " + bad);

        JsonObject fn = now(call(h, "function", params(
                "{\"lines\":[\"# комментарий\",\"setblock ~ ~ ~ minecraft:gold_block\",\"$setblock ~1 ~ ~ $(block)\"],\"pos\":%s,\"args\":{\"block\":\"minecraft:diamond_block\"}}",
                at(h, 6, 2, 6)))).getAsJsonObject();
        check(fn.get("ok").getAsBoolean(), "функция: " + fn);
        h.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(6, 2, 6));
        h.assertBlockPresent(Blocks.DIAMOND_BLOCK, new BlockPos(7, 2, 6));

        CompletableFuture<JsonElement> broken = call(h, "function", params("{\"lines\":[\"say раз\",\"setblok ~ ~ ~ stone\"]}"));
        check(broken.isCompletedExceptionally(), "ошибка разбора не замечена");
        try {
            broken.join();
        } catch (CompletionException e) {
            check(e.getCause() instanceof RpcException rpc && rpc.getMessage().contains("line 2"), "ошибка разбора: " + e.getCause());
        }
        h.succeed();
    }

    private static BufferedImage image(JsonObject result) {
        try {
            return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(result.get("png_base64").getAsString())));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Цвет середины клетки (i, j) картинки снимка. */
    private static int cell(BufferedImage image, JsonObject result, int i, int j) {
        int k = result.getAsJsonObject("legend").get("pixels_per_cell").getAsInt();
        return image.getRGB(i * k + k / 2, j * k + k / 2) & 0xFFFFFF;
    }

    /**
     * Карта сверху: пол — цвет камня, верх золотой колонны — золото (светлее: выше соседа с севера), за ней к югу — тень;
     * незагруженные чанки — шахматка. Вид с юга на север: колонна — золото темнее по глубине, пустой луч — небо, небо
     * за стеклом — с налётом. Картинка выдаётся один раз: работа после этого её не держит.
     */
    @GameTest(template = "floor", batch = "gm_view", timeoutTicks = 200, skyAccess = true)
    public static void mapAndLookViews(GameTestHelper h) {
        for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(32, y, 32), Blocks.GOLD_BLOCK);
        h.setBlock(new BlockPos(31, 3, 34), Blocks.GLASS);
        CompletableFuture<JsonElement> map = call(h, "map", params("{\"from\":[%d,%d],\"to\":[%d,%d]}",
                h.absolutePos(BlockPos.ZERO).getX(), h.absolutePos(BlockPos.ZERO).getZ(),
                h.absolutePos(new BlockPos(63, 0, 63)).getX(), h.absolutePos(new BlockPos(63, 0, 63)).getZ()));
        BlockPos far = h.absolutePos(new BlockPos(40_000, 0, 40_000));
        CompletableFuture<JsonElement> unloaded = call(h, "map", params("{\"center\":[%d,%d],\"size\":32}", far.getX(), far.getZ()));
        CompletableFuture<JsonElement> look = call(h, "look", params("{\"from\":%s,\"to\":%s,\"look\":\"north\"}", at(h, 30, 2, 35), at(h, 34, 4, 32)));
        h.startSequence()
                .thenWaitUntil(() -> viewsEnded(h))
                .thenExecute(() -> {
                    JsonObject m = settled(map);
                    BufferedImage top = image(m);
                    int stone = Png.shade(MapColor.STONE.col, MapColor.Brightness.NORMAL.modifier / 255.0);
                    check(cell(top, m, 10, 10) == stone, String.format(Locale.ROOT, "пол: #%06X, а не #%06X", cell(top, m, 10, 10), stone));
                    check(cell(top, m, 32, 32) == MapColor.GOLD.col, String.format(Locale.ROOT, "верх колонны: #%06X", cell(top, m, 32, 32)));
                    int shadow = Png.shade(MapColor.STONE.col, MapColor.Brightness.LOW.modifier / 255.0);
                    check(cell(top, m, 32, 33) == shadow, String.format(Locale.ROOT, "к югу от колонны: #%06X", cell(top, m, 32, 33)));

                    JsonObject u = settled(unloaded);
                    BufferedImage none = image(u);
                    for (int i = 0; i < 32; i += 5) {
                        int c = cell(none, u, i, i);
                        check(c == 0x303030 || c == 0x484848, String.format(Locale.ROOT, "незагруженная клетка %d: #%06X", i, c));
                    }

                    JsonObject l = settled(look);
                    BufferedImage side = image(l);
                    // колонна x = 32 — третья клетка слева; ближняя грань z = 35, колонна на глубине 3 из 4
                    check(cell(side, l, 2, 0) == Png.shade(MapColor.GOLD.col, 1.0 - 0.6 * 3 / 3), String.format(Locale.ROOT, "колонна сбоку: #%06X", cell(side, l, 2, 0)));
                    check(cell(side, l, 0, 0) == 0x87AEDB, String.format(Locale.ROOT, "пустой луч: #%06X", cell(side, l, 0, 0)));
                    int glass = cell(side, l, 1, 1);
                    check(glass != 0x87AEDB && (glass >> 16 & 255) > 0x87, String.format(Locale.ROOT, "небо за стеклом без налёта: #%06X", glass));
                    JsonObject r = settled(call(h, "job", params("{\"id\":%d}", m.get("id").getAsLong())));
                    check(r.get("state").getAsString().equals("done") && !r.has("png_base64"), "картинка снимка выдана второй раз: " + r.keySet());
                })
                .thenSucceed();
    }

    /** Лента: вход, смерть с причиной и выход игрока. */
    @GameTest(template = "floor", batch = "gm_feed", skyAccess = true)
    public static void feedSeesJoinDeathLeave(GameTestHelper h) throws InterruptedException {
        GmServer gm = gm(h);
        // кольцо целиком: номер последнего события
        long after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
        // как GameTestHelper.makeMockServerPlayerInLevel (у ванили помечен к удалению): игрок со встроенным каналом
        GameProfile profile = new GameProfile(UUID.randomUUID(), "gm-feed-player");
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
        h.getLevel().getServer().getPlayerList().remove(player);
        JsonArray events = gm.feed().read(after, 100, 0).getAsJsonArray("events");
        StringBuilder types = new StringBuilder();
        boolean death = false;
        for (JsonElement e : events) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("player") || !o.get("player").getAsString().equals("gm-feed-player")) continue;
            types.append(o.get("type").getAsString()).append(' ');
            if (o.get("type").getAsString().equals("death")) death = o.get("message").getAsString().contains("gm-feed-player");
        }
        check(types.toString().equals("join death leave "), "события: " + types + "в " + events);
        check(death, "у смерти нет причины: " + events);
        h.succeed();
    }

    /**
     * Тикеты ведущего: район держит чанки с кольцом (1 чанк → 9 тикетов), отпуск снимает их в следующем тике, остановка
     * ({@code releaseAll}) — сразу; постройка отпускает свои чанки, когда кончилась; сверх предела — ошибка.
     */
    @GameTest(template = "floor", batch = "gm_tickets", timeoutTicks = 200, skyAccess = true)
    public static void ticketsHeldAndReleased(GameTestHelper h) {
        GmServer gm = gm(h);
        ServerLevel level = h.getLevel();
        check(GmTickets.count(level) == 0, "тикеты ведущего до проверки: " + GmTickets.count(level));
        BlockPos c = h.absolutePos(new BlockPos(24, 0, 24));
        int x0 = c.getX() & ~15, z0 = c.getZ() & ~15;
        JsonObject area = result(call(h, "area.prepare", params("{\"from\":[%d,%d],\"to\":[%d,%d]}", x0, z0, x0 + 15, z0 + 15)));
        check(area.get("chunks").getAsInt() == 9 && GmTickets.count(level) == 9, "район 1 чанк: " + area + ", тикетов " + GmTickets.count(level));
        result(call(h, "area.release", params("{\"id\":%d}", area.get("id").getAsLong())));
        check(GmTickets.count(level) == 9, "тикеты сняты раньше очереди отпуска");
        CompletableFuture<JsonElement> tooBig = call(h, "area.prepare", params("{\"from\":[0,0],\"to\":[%d,%d]}", 16 * 60, 16 * 60));
        check(tooBig.isCompletedExceptionally(), "район больше предела " + GmConfig.MAX_CHUNKS.get() + " взят");
        loadNow(h, 20, 20, 21, 21);
        AtomicReference<CompletableFuture<JsonElement>> build = new AtomicReference<>();
        h.startSequence()
                .thenExecuteAfter(1, () -> {
                    check(GmTickets.count(level) == 0, "отпущенный район держит тикеты: " + GmTickets.count(level));
                    Areas areas = gm.areas();
                    try {
                        areas.acquire(level, "проверка", x0, z0, x0 + 40, z0 + 40, 0);
                    } catch (RpcException e) {
                        throw new GameTestAssertException(e.getMessage());
                    }
                    check(GmTickets.count(level) == 25, "район 3×3 чанка с кольцом: " + GmTickets.count(level));
                    areas.releaseAll();
                    check(GmTickets.count(level) == 0, "releaseAll оставил тикеты: " + GmTickets.count(level));
                    build.set(call(h, "build", params("{\"ops\":[{\"op\":\"set\",\"pos\":%s,\"block\":\"minecraft:stone\"}],\"wait\":50}", at(h, 20, 2, 20))));
                })
                .thenWaitUntil(() -> ready(build.get()))
                .thenExecuteAfter(1, () -> {
                    check(result(build.get()).get("state").getAsString().equals("done"), "постройка: " + result(build.get()));
                    check(GmTickets.count(level) == 0, "кончившаяся постройка держит тикеты: " + GmTickets.count(level));
                })
                .thenSucceed();
    }
}
