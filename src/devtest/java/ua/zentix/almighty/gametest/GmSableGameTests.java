package ua.zentix.almighty.gametest;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.world.Areas;
import ua.zentix.almighty.world.Ship;
import ua.zentix.almighty.world.Ships;

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

    /**
     * Корабль для автопилота ({@code ships}, {@code gm.ship}): собранный в воздухе корабль с бочкой падает. Имя, место
     * и рамки — как у Sable; скорость — в блоках в секунду (сходится с ходом за тик × 20), вращения нет; бочка на
     * участке находится по типу, участок ↔ мир переводятся туда и обратно.
     */
    @GameTest(template = "floor", batch = "gm_sable_ships", timeoutTicks = 200, skyAccess = true)
    public static void shipsForAutopilot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        List<SubLevel> before = new ArrayList<>(container.getAllSubLevels());
        BlockPos a = h.absolutePos(new BlockPos(2, 16, 2)), b = h.absolutePos(new BlockPos(4, 16, 4));
        for (BlockPos p : BlockPos.betweenClosed(a, b)) level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos barrel = a.offset(1, 1, 1);
        level.setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
                String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY() + 1, b.getZ()));
        AtomicReference<Ship> ship = new AtomicReference<>();
        AtomicReference<ServerSubLevel> sub = new AtomicReference<>();
        AtomicReference<Double> yaw = new AtomicReference<>();
        List<Vec3> pos = new ArrayList<>(), vel = new ArrayList<>();
        var seq = h.startSequence()
                .thenWaitUntil(() -> {
                    for (SubLevel s : container.getAllSubLevels()) {
                        if (!before.contains(s)) {
                            sub.set((ServerSubLevel) s);
                            sub.get().setName("GmTestShip");
                        }
                    }
                    ship.set(Ships.find(level.getServer(), "GmTestShip"));
                    if (ship.get() == null) throw new GameTestAssertException("корабль не собрался: " + Ships.all(level).size());
                    // рамку Sable считает в своём тике
                    if (ship.get().box().getSize() == 0) throw new GameTestAssertException("у корабля ещё нет рамки");
                })
                .thenExecute(() -> {
                    JsonArray listed = GmGameTests.call(h, "ships", GmGameTests.params("{\"name\":\"GmTestShip\"}")).join().getAsJsonArray();
                    check(listed.size() == 1, "ships по имени: " + listed);
                    JsonObject d = listed.get(0).getAsJsonObject();
                    check(d.get("uuid").getAsString().equals(ship.get().uuid().toString()) && d.has("orientation") && d.has("velocity")
                            && d.has("angular_velocity") && d.getAsJsonObject("plot").has("from"), "описание корабля: " + d);
                    Vec3 w = ship.get().pos();
                    check(w.distanceTo(Vec3.atCenterOf(barrel)) < 4, "корабль не там, где собран: " + w + ", бочка " + barrel);
                    check(ship.get().box().inflate(0.5).contains(Vec3.atCenterOf(barrel)), "рамка корабля без бочки: " + ship.get().box());
                    List<BlockEntity> found = ship.get().blockEntities("minecraft:barrel");
                    check(found.size() == 1, "бочек на участке: " + found.size() + ", участок " + ship.get().plotBox());
                    BlockPos plot = found.get(0).getBlockPos();
                    check(plot.getX() > 20_000_000, "бочка не на участке: " + plot);
                    Vec3 inWorld = ship.get().toWorld(Vec3.atCenterOf(plot));
                    check(inWorld.distanceTo(Vec3.atCenterOf(barrel)) < 1.5, "бочка участка в мире: " + inWorld + ", собрана в " + barrel);
                    check(ship.get().toPlot(inWorld).distanceTo(Vec3.atCenterOf(plot)) < 1e-6, "участок → мир → участок");
                    check(ship.get().blockEntities("minecraft:chest").isEmpty(), "сундук на корабле без сундука");
                    // скрипт видит корабль через gm.ship: внутренние классы Sable Groovy не грузит (подписи с ClientLevel)
                    JsonObject r = GmGameTests.now(GmGameTests.call(h, "script", GmGameTests.params("{\"code\":"
                            + "\"def s = gm.ship('GmTestShip'); return [s.name(), s.blockEntities('minecraft:barrel').size(), s.velocity().y < 1]\"}")))
                            .getAsJsonObject();
                    check(r.get("ok").getAsBoolean() && r.get("value").toString().equals("[\"GmTestShip\",1,true]"), "gm.ship из скрипта: " + r);
                });
        for (int i = 0; i < 8; i++) {
            seq = seq.thenExecuteAfter(1, () -> {
                pos.add(ship.get().pos());
                vel.add(ship.get().velocity());
            });
        }
        seq.thenExecute(() -> {
            Vec3 moved = pos.get(pos.size() - 1).subtract(pos.get(1)).scale(20.0 / (pos.size() - 2));
            Vec3 mean = Vec3.ZERO;
            for (Vec3 v : vel.subList(1, vel.size() - 1)) mean = mean.add(v);
            mean = mean.scale(1.0 / (vel.size() - 2));
            check(moved.y < -1, "корабль не падает: ход " + moved + " блоков/с, места " + pos);
            check(Math.abs(mean.y - moved.y) < 0.3 * Math.abs(moved.y) + 0.2, "скорость " + mean + " — не блоков в секунду: ход " + moved + ", скорости " + vel);
            check(ship.get().angularVelocity().length() < 0.2, "падение без вращения: ω " + ship.get().angularVelocity());
            // закрутить вокруг вертикали: скорость Sable берёт точку участка, а не мира (иначе ошибка — ω × 2·10^7 блоков)
            yaw.set(yaw(ship.get()));
            RigidBodyHandle.of(sub.get()).addLinearAndAngularVelocity(new Vector3d(), new Vector3d(0, 1, 0));
        }).thenExecuteAfter(5, () -> {
            RigidBodyHandle body = RigidBodyHandle.of(sub.get());
            Vec3 lin = vec(body.getLinearVelocity()), omega = vec(body.getAngularVelocity());
            check(omega.y > 0.5, "корабль не закрутился: ω Sable " + omega);
            double turned = Math.abs(Math.toDegrees(yaw(ship.get()) - yaw.get()));
            check(turned > 5, "корабль не повернулся: " + turned + "°");
            check(ship.get().velocity().distanceTo(lin) < 1e-3, "скорость вращающегося корабля " + ship.get().velocity() + ", у Sable " + lin);
            check(ship.get().angularVelocity().distanceTo(omega) < 1e-3, "угловая скорость " + ship.get().angularVelocity() + ", у Sable " + omega);
            Vec3 arm = new Vec3(2, 0, 1);
            Vec3 expected = lin.add(omega.cross(arm));
            check(ship.get().velocityAt(ship.get().pos().add(arm)).distanceTo(expected) < 1e-3,
                    "скорость точки сбоку " + ship.get().velocityAt(ship.get().pos().add(arm)) + ", ждали " + expected);
        }).thenExecute(() -> {
            for (SubLevel s : new ArrayList<>(container.getAllSubLevels())) {
                if (!before.contains(s)) container.removeSubLevel(s, SubLevelRemovalReason.REMOVED);
            }
        }).thenSucceed();
    }

    private static double yaw(Ship ship) {
        return ship.orientation().getEulerAnglesYXZ(new Vector3d()).y;
    }

    private static Vec3 vec(Vector3dc v) {
        return new Vec3(v.x(), v.y(), v.z());
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
