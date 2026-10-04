package ua.zentix.almighty.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static ua.zentix.almighty.gametest.GmGameTests.call;
import static ua.zentix.almighty.gametest.GmGameTests.check;
import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.now;
import static ua.zentix.almighty.gametest.GmGameTests.params;

/**
 * Ведущий не грузит чанки за границей мира: аренда (район, вход бота) и тикеты скриптов — отказ с ошибкой, команды и
 * задачи сервера, выполненные ожиданием скрипта, — как в игре, код не ведущего — как в ванили. Граница на время проверки — пять чанков вокруг площадки, потом прежняя
 * (в {@code finally}: оставленная, она отказала бы следующим партиям); вся проверка — в одном тике.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmBorderGameTests {
    private GmBorderGameTests() {}

    @GameTest(template = "floor", batch = "gm_border", skyAccess = true)
    public static void agentLoadsOnlyInsideBorder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        WorldBorder border = level.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        // внутри — чанки cx−2…cx+2 (блоки целиком), с кольцом можно cx−3…cx+3
        border.setCenter(cx * 16 + 8, cz * 16 + 8);
        border.setSize(80);
        try {
            inside(h, level, cx, cz);
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
            // партия одна: район, ошибочно взятый упавшей проверкой, иначе остался бы в счёте тикетов следующих партий
            gm(h).areas().releaseAll();
        }
        h.succeed();
    }

    private static void inside(GameTestHelper h, ServerLevel level, int cx, int cz) {
        GmServer gm = gm(h);
        int x0 = cx * 16, z0 = cz * 16;

        // район у самого края: его кольцо за границей, но в пределе
        JsonObject edge = now(call(h, "area.prepare", params("{\"from\":[%d,%d],\"to\":[%d,%d]}", x0 + 32, z0, x0 + 47, z0 + 15))).getAsJsonObject();
        check(edge.get("chunks").getAsInt() == 9, "район у края: " + edge);
        now(call(h, "area.release", params("{\"id\":%d}", edge.get("id").getAsLong())));
        int held = gm.areas().held(), ticketed = gm.areas().ticketed(), tickets = GmTickets.count(level);
        String area = failure(call(h, "area.prepare", params("{\"from\":[%d,%d],\"to\":[%d,%d]}", x0 + 48, z0, x0 + 63, z0 + 15)));
        check(area.contains("за границей мира"), "район за краем: " + area);
        check(gm.areas().held() == held && gm.areas().ticketed() == ticketed && GmTickets.count(level) == tickets, "отказ оставил аренды или тикеты");
        String bot = failure(call(h, "bot.spawn", params("{\"name\":\"GmTestBorder\",\"pos\":[%d,3,%d]}", x0 + 200, z0)));
        check(bot.contains("за границей мира") && gm.bots().find("GmTestBorder") == null, "бот за границей: " + bot);

        // скрипт: тикет и синхронная загрузка за пределом — ошибка, тикет не записан; внутри — можно
        int fx = cx - 40;
        check(level.getChunkSource().getChunkNow(fx, cz) == null, "чанк за границей загружен до проверки");
        String ticket = "server.overworld().chunkSource.addRegionTicket(net.minecraft.server.level.TicketType.PORTAL, "
                + "new net.minecraft.world.level.ChunkPos(args.x, args.z), args.r, BlockPos.ZERO)";
        JsonObject far = script(h, ticket, fx, cz);
        check(!far.get("ok").getAsBoolean() && far.get("error").getAsString().contains("за границей мира"), "тикет скрипта за границей: " + far);
        check(GmTickets.at(level, TicketType.PORTAL, fx, cz) == 0, "тикет за границей записан");
        // чанк падения 04.10.2026: 37 млн блоков от центра
        JsonObject crash = script(h, ticket, 2_322_806, -9);
        check(!crash.get("ok").getAsBoolean() && crash.get("error").getAsString().contains("за границей мира")
                && GmTickets.at(level, TicketType.PORTAL, 2_322_806, -9) == 0, "тикет в 37 млн блоков: " + crash);
        JsonObject load = script(h, "server.overworld().getChunk(args.x, args.z)", fx, cz);
        check(!load.get("ok").getAsBoolean() && load.get("error").getAsString().contains("за границей мира"), "загрузка скриптом за границей: " + load);
        check(level.getChunkSource().getChunkNow(fx, cz) == null, "чанк за границей загружен");
        // тикет с радиусом 3 грузит полными и соседей в 3 чанка: от cx — до кольца, от cx+1 — за него (сильнее
        // тикетов, которые GameTest ставит на чанки площадок, — уровень 31)
        JsonObject near = script(h, ticket, cx, cz, 3);
        check(near.get("ok").getAsBoolean() && GmTickets.at(level, TicketType.PORTAL, cx, cz) == 1, "тикет скрипта до кольца: " + near);
        level.getChunkSource().removeRegionTicket(TicketType.PORTAL, new ChunkPos(cx, cz), 3, BlockPos.ZERO);
        JsonObject wide = script(h, ticket, cx + 1, cz, 3);
        check(!wide.get("ok").getAsBoolean() && wide.get("error").getAsString().contains("за границей мира")
                && GmTickets.at(level, TicketType.PORTAL, cx + 1, cz) == 0, "тикет скрипта с соседями за кольцом: " + wide);

        // setChunkForced пишет чанк в сохраняемый список до загрузки: отказ убирает его оттуда (иначе — при запуске)
        int gz = cz - 40;
        check(level.getChunkSource().getChunkNow(fx, gz) == null, "чанк за границей загружен до проверки");
        JsonObject forced = script(h, "server.overworld().setChunkForced(args.x, args.z, true)", fx, gz);
        check(!forced.get("ok").getAsBoolean() && forced.get("error").getAsString().contains("за границей мира")
                && !level.getForcedChunks().contains(ChunkPos.asLong(fx, gz)), "setChunkForced скрипта за границей: " + forced);

        // команда из скрипта — как в игре (/forceload сам не идёт за предел мира); после неё проверка снова действует
        JsonObject command = script(h, """
                def r = gm.command("forceload add ${args.x * 16} ${args.z * 16}", "forceload remove ${args.x * 16} ${args.z * 16}")
                try {
                    server.overworld().chunkSource.addRegionTicket(net.minecraft.server.level.TicketType.PORTAL, new net.minecraft.world.level.ChunkPos(args.x, args.z), 1, BlockPos.ZERO)
                    return [r, 'тикет взят']
                } catch (e) {
                    return [r, e.message]
                }
                """, fx, cz);
        check(command.get("ok").getAsBoolean(), "скрипт с командой: " + command);
        JsonElement commands = command.getAsJsonArray("value").get(0);
        check(commands.getAsJsonArray().get(0).getAsJsonObject().get("ok").getAsBoolean()
                && commands.getAsJsonArray().get(1).getAsJsonObject().get("ok").getAsBoolean(), "forceload из скрипта: " + commands);
        check(level.getChunkSource().getChunkNow(fx, cz) != null && !level.getForcedChunks().contains(ChunkPos.asLong(fx, cz)), "forceload не загрузил или не снял чанк");
        String after = command.getAsJsonArray("value").get(1).getAsString();
        check(after.contains("за границей мира") && GmTickets.at(level, TicketType.PORTAL, fx, cz) == 0, "после команды тикет скрипта: " + after);

        // задача сервера, которую выполнило ожидание скрипта (здесь — запрос чанка из другого потока), — не его тикет
        int qz = cz + 40;
        check(level.getChunkSource().getChunkNow(fx, qz) == null, "чанк за границей загружен до проверки");
        CompletableFuture<?> queued = CompletableFuture.supplyAsync(() -> level.getChunkSource().getChunkFuture(fx, qz, ChunkStatus.EMPTY, true)).join();
        JsonObject wait = script(h, "def cs = server.overworld().chunkSource; int n = 0; while (n < 10000 && cs.pollTask()) n++; n", fx, qz);
        check(wait.get("ok").getAsBoolean() && !queued.isCompletedExceptionally() && GmTickets.at(level, TicketType.UNKNOWN, fx, qz) == 1,
                "задача сервера в ожидании скрипта: " + wait + ", запрос " + queued);

        // не ведущий (игра, другие моды) — как в ванили
        level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(fx, cz), 1, BlockPos.ZERO);
        check(GmTickets.at(level, TicketType.PORTAL, fx, cz) == 1, "тикет игры за границей не записан");
        level.getChunkSource().removeRegionTicket(TicketType.PORTAL, new ChunkPos(fx, cz), 1, BlockPos.ZERO);
    }

    private static JsonObject script(GameTestHelper h, String code, int x, int z) {
        return script(h, code, x, z, 1);
    }

    /** Скрипт с {@code args.x}, {@code args.z} и {@code args.r} — радиусом тикета. */
    private static JsonObject script(GameTestHelper h, String code, int x, int z, int r) {
        JsonObject p = new JsonObject();
        p.addProperty("code", code);
        JsonObject args = new JsonObject();
        args.addProperty("x", x);
        args.addProperty("z", z);
        args.addProperty("r", r);
        p.add("args", args);
        return now(call(h, "script", p)).getAsJsonObject();
    }

    /** Вызов, который сразу кончился ошибкой: её текст. */
    private static String failure(CompletableFuture<JsonElement> f) {
        check(f.isDone(), "вызов из потока сервера не выполнился сразу");
        try {
            throw new GameTestAssertException("вызов не отказал: " + f.join());
        } catch (CompletionException e) {
            return e.getCause().getMessage();
        }
    }
}
