package ua.zentix.almighty.gametest;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
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
     * Чанк корабля аренда видит готовым (его отдаёт Sable). Потом корабль убирается, пока аренда стоит, — Sable снимает
     * держатель его чанка, как при пробоине или ремонте: с ванильными тикетами там шла генерация, и задача генерации
     * не находила держатель соседа (NPE в {@code ChunkMap.acquireGeneration}, падение 04.10.2026).
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
        h.startSequence()
                .thenWaitUntil(() -> {
                    for (SubLevel s : container.getAllSubLevels()) if (!before.contains(s)) ship.set(s);
                    if (ship.get() == null) throw new GameTestAssertException("корабль не собрался");
                })
                .thenExecute(() -> {
                    LevelPlot plot = ship.get().getPlot();
                    PlotChunkHolder holder = plot.getLoadedChunks().stream().findFirst()
                            .orElseThrow(() -> new GameTestAssertException("у корабля нет чанков на участке"));
                    ChunkPos c = holder.getPos();
                    chunk.set(c);
                    check(container.inBounds(c) && c.getMinBlockX() > 20_000_000, "чанк корабля не на участке: " + c);
                    try {
                        lease.set(gm.areas().acquire(level, "проверка", c.getMinBlockX(), c.getMinBlockZ(), c.getMaxBlockX(), c.getMaxBlockZ(), 0));
                    } catch (RpcException e) {
                        throw new GameTestAssertException(e.getMessage());
                    }
                })
                .thenExecuteAfter(20, () -> {
                    check(lease.get().ready() >= 1, "чанк корабля аренда не видит: " + lease.get().describe());
                    container.removeSubLevel(ship.get(), SubLevelRemovalReason.REMOVED);
                })
                .thenExecuteAfter(5, () -> {
                    // ещё аренда рядом: новые чанки в радиусе генерации (8 чанков) от убранного держателя
                    ChunkPos c = chunk.get();
                    try {
                        next.set(gm.areas().acquire(level, "проверка", c.getMinBlockX() + 48, c.getMinBlockZ(), c.getMaxBlockX() + 48, c.getMaxBlockZ(), 0));
                    } catch (RpcException e) {
                        throw new GameTestAssertException(e.getMessage());
                    }
                })
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
}
