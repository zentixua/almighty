package ua.zentix.almighty.gametest;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.world.Areas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static ua.zentix.almighty.gametest.GmGameTests.check;

/**
 * Ведущий и корабли Sable (Create Aeronautics): блоки собранного корабля живут на его участке («плоте») далеко от мира
 * (x ≈ 20,48 млн блоков), чанки участка ведёт Sable, а не ванильная загрузка. 04.10.2026 постройка ведущего по
 * координатам участка лайнера поставила там ванильные тикеты — генерация чанка на участке, NPE в
 * {@code ChunkMap.acquireGeneration}, сервер встал.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmSableGameTests {
    private GmSableGameTests() {}

    /**
     * Аренда на участке корабля (чанк с его блоками и кольцо соседей) тикетов не ставит: Sable не пускает тикеты на
     * участки ({@code addRegionTicket}), а держатели чанков участка ставит в карту чанков ванили и убирает оттуда сам.
     * Чанк корабля аренда видит готовым (его отдаёт Sable), пустых соседей на участке не дождётся — постройка там
     * отказывает сразу, а не через 2 минуты ожидания. Отпуск аренды при живом корабле держатель его чанка не трогает:
     * снятие тикета, которого нет, пересчитало бы уровень чанка без тикетов (31 → 45), и ваниль выгрузила бы чанк
     * корабля. Потом корабль убирается при стоящей аренде — Sable снимает держатель его чанка, как при пробоине или
     * ремонте, — и рядом берётся вторая аренда: с ванильными тикетами там шла генерация, и задача генерации не находила
     * держатель соседа (NPE в {@code ChunkMap.acquireGeneration}, падение 04.10.2026).
     */
    @GameTest(template = "floor", batch = "gm_sable", timeoutTicks = 200, skyAccess = true)
    public static void leaseOnShipPlotLoadsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        GmServer gm = GmGameTests.gm(h);
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        List<SubLevel> before = new ArrayList<>(container.getAllSubLevels());
        BlockPos a = h.absolutePos(new BlockPos(2, 2, 2)), b = h.absolutePos(new BlockPos(4, 3, 4));
        for (BlockPos p : BlockPos.betweenClosed(a, b)) level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
                String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        AtomicReference<SubLevel> ship = new AtomicReference<>();
        AtomicReference<Areas.Lease> lease = new AtomicReference<>(), next = new AtomicReference<>();
        AtomicReference<ChunkPos> chunk = new AtomicReference<>();
        int tickets = GmTickets.count(level);
        AtomicReference<PlotChunkHolder> holder = new AtomicReference<>();
        AtomicReference<CompletableFuture<JsonElement>> build = new AtomicReference<>();
        int[] level0 = {0};
        h.startSequence()
                .thenWaitUntil(() -> {
                    for (SubLevel s : container.getAllSubLevels()) if (!before.contains(s)) ship.set(s);
                    if (ship.get() == null) throw new GameTestAssertException("корабль не собрался");
                })
                .thenExecute(() -> {
                    LevelPlot plot = ship.get().getPlot();
                    holder.set(plot.getLoadedChunks().stream().findFirst()
                            .orElseThrow(() -> new GameTestAssertException("у корабля нет чанков на участке")));
                    ChunkPos c = holder.get().getPos();
                    chunk.set(c);
                    level0[0] = holder.get().getTicketLevel();
                    check(container.inBounds(c) && c.getMinBlockX() > 20_000_000, "чанк корабля не на участке: " + c);
                    lease.set(acquire(gm, level, c, 0));
                    check(GmTickets.count(level) == tickets, "аренда поставила тикеты на участке корабля: " + (GmTickets.count(level) - tickets));
                    JsonObject lease1 = lease.get().describe();
                    check(lease1.get("refused").getAsInt() == lease.get().chunks() && lease1.has("refusal"), "отказанные чанки аренды: " + lease1);
                    build.set(GmGameTests.call(h, "build", GmGameTests.params("{\"ops\":[{\"op\":\"set\",\"pos\":[%d,100,%d],\"block\":\"minecraft:stone\"}],\"wait\":50}",
                            c.getMinBlockX(), c.getMinBlockZ())));
                })
                .thenExecuteAfter(5, () -> {
                    check(lease.get().ready() >= 1, "чанк корабля аренда не видит: " + lease.get().describe());
                    check(build.get().isDone(), "постройка на участке корабля ждёт чанки, которых не будет");
                    JsonObject job = build.get().join().getAsJsonObject();
                    check(job.get("state").getAsString().equals("failed") && job.get("error").getAsString().contains("не принимают загрузку"),
                            "постройка на участке корабля: " + job);
                    lease.get().release();
                })
                .thenExecuteAfter(10, () -> {
                    PlotChunkHolder now = container.getChunkHolder(chunk.get());
                    check(now == holder.get() && now.getTicketLevel() == level0[0],
                            "отпуск аренды тронул чанк живого корабля: уровень " + level0[0] + " → " + (now == null ? "нет держателя" : now.getTicketLevel()));
                    lease.set(acquire(gm, level, chunk.get(), 0));
                })
                .thenExecuteAfter(5, () -> container.removeSubLevel(ship.get(), SubLevelRemovalReason.REMOVED))
                // ещё аренда рядом: новые чанки в радиусе генерации (8 чанков) от убранного держателя
                .thenExecuteAfter(5, () -> next.set(acquire(gm, level, chunk.get(), 48)))
                // генерация на участке роняла сервер в следующих тиках
                .thenIdle(40)
                .thenExecute(() -> {
                    int left = GmTickets.count(level) - tickets;
                    lease.get().release();
                    next.get().release();
                    check(left == 0, "аренда поставила тикеты на участке корабля: " + left);
                })
                .thenSucceed();
    }

    /** Аренда на чанк {@code c}, сдвинутый на {@code dx} блоков по x. */
    private static Areas.Lease acquire(GmServer gm, ServerLevel level, ChunkPos c, int dx) {
        try {
            return gm.areas().acquire(level, "проверка", c.getMinBlockX() + dx, c.getMinBlockZ(), c.getMaxBlockX() + dx, c.getMaxBlockZ(), 0);
        } catch (RpcException e) {
            throw new GameTestAssertException(e.getMessage());
        }
    }
}
