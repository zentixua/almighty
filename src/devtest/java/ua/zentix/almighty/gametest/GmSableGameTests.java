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
 * The GM and Sable (Create Aeronautics) ships: assembled ship blocks live on a plot far from the world
 * (x approximately 20.48 million blocks); Sable manages plot chunks, outside vanilla loading. Vanilla tickets
 * on a plot (a GM build at plot coordinates) start chunk generation there, causing an NPE in
 * {@code ChunkMap.acquireGeneration} and stopping the server.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmSableGameTests {
    private GmSableGameTests() {}

    /**
     * A lease on a ship plot (its block chunk and neighbor ring) adds no tickets: Sable rejects plot tickets
     * ({@code addRegionTicket}) and inserts/removes plot chunk holders in vanilla's chunk map itself.
     * The lease sees the ship chunk ready (provided by Sable), but cannot await empty plot neighbors: building there
     * refuses immediately, instead of after 2 minutes. Releasing a lease on a live ship leaves its chunk holder
     * untouched: removing a nonexistent ticket would recalculate the ticketless chunk level (31 to 45), causing
     * vanilla to unload the ship chunk. Then remove the ship with an active lease: Sable removes its holder as for
     * a hole or repair; acquire another lease nearby. Vanilla tickets would cause generation there, and the
     * generation task would fail to find the neighbor holder (NPE in {@code ChunkMap.acquireGeneration}).
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
                    if (ship.get() == null) throw new GameTestAssertException("ship did not assemble");
                })
                .thenExecute(() -> {
                    LevelPlot plot = ship.get().getPlot();
                    holder.set(plot.getLoadedChunks().stream().findFirst()
                            .orElseThrow(() -> new GameTestAssertException("ship has no plot chunks")));
                    ChunkPos c = holder.get().getPos();
                    chunk.set(c);
                    level0[0] = holder.get().getTicketLevel();
                    check(container.inBounds(c) && c.getMinBlockX() > 20_000_000, "ship chunk is not on the plot: " + c);
                    lease.set(acquire(gm, level, c, 0));
                    check(GmTickets.count(level) == tickets, "lease added tickets on ship plot: " + (GmTickets.count(level) - tickets));
                    JsonObject lease1 = lease.get().describe();
                    check(lease1.get("refused").getAsInt() == lease.get().chunks() && lease1.has("refusal"), "refused lease chunks: " + lease1);
                    build.set(GmGameTests.call(h, "build", GmGameTests.params("{\"ops\":[{\"op\":\"set\",\"pos\":[%d,100,%d],\"block\":\"minecraft:stone\"}],\"wait\":50}",
                            c.getMinBlockX(), c.getMinBlockZ())));
                })
                .thenExecuteAfter(5, () -> {
                    check(lease.get().ready() >= 1, "lease cannot see ship chunk: " + lease.get().describe());
                    check(build.get().isDone(), "ship-plot build awaits chunks that will never arrive");
                    JsonObject job = build.get().join().getAsJsonObject();
                    check(job.get("state").getAsString().equals("failed") && job.get("error").getAsString().contains("refuse loading"),
                            "ship-plot build: " + job);
                    lease.get().release();
                })
                .thenExecuteAfter(10, () -> {
                    PlotChunkHolder now = container.getChunkHolder(chunk.get());
                    check(now == holder.get() && now.getTicketLevel() == level0[0],
                            "lease release affected a live ship chunk: level " + level0[0] + " → " + (now == null ? "missing holder" : now.getTicketLevel()));
                    lease.set(acquire(gm, level, chunk.get(), 0));
                })
                .thenExecuteAfter(5, () -> container.removeSubLevel(ship.get(), SubLevelRemovalReason.REMOVED))
                // Another nearby lease: new chunks within generation radius (8 chunks) of the removed holder.
                .thenExecuteAfter(5, () -> next.set(acquire(gm, level, chunk.get(), 48)))
                // Generation on the plot would crash the server in subsequent ticks.
                .thenIdle(40)
                .thenExecute(() -> {
                    int left = GmTickets.count(level) - tickets;
                    lease.get().release();
                    next.get().release();
                    check(left == 0, "lease added tickets on ship plot: " + left);
                })
                .thenSucceed();
    }

    /**
     * Autopilot ship ({@code ships}, {@code gm.ship}): a ship assembled in midair with a barrel falls. Name,
     * position and bounds match Sable; velocity is in blocks per second (matches per-tick displacement × 20), without
     * rotation; find the barrel on the plot by type; convert plot to world and back.
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
                    if (ship.get() == null) throw new GameTestAssertException("ship did not assemble: " + Ships.all(level).size());
                    // Sable computes bounds in its tick.
                    if (ship.get().box().getSize() == 0) throw new GameTestAssertException("ship has no bounds yet");
                })
                .thenExecute(() -> {
                    JsonArray listed = GmGameTests.call(h, "ships", GmGameTests.params("{\"name\":\"GmTestShip\"}")).join().getAsJsonArray();
                    check(listed.size() == 1, "ships by name: " + listed);
                    JsonObject d = listed.get(0).getAsJsonObject();
                    check(d.get("uuid").getAsString().equals(ship.get().uuid().toString()) && d.has("orientation") && d.has("velocity")
                            && d.has("angular_velocity") && d.getAsJsonObject("plot").has("from"), "ship description: " + d);
                    Vec3 w = ship.get().pos();
                    check(w.distanceTo(Vec3.atCenterOf(barrel)) < 4, "ship is not at its assembly position: " + w + ", barrel " + barrel);
                    check(ship.get().box().inflate(0.5).contains(Vec3.atCenterOf(barrel)), "ship bounds exclude barrel: " + ship.get().box());
                    List<BlockEntity> found = ship.get().blockEntities("minecraft:barrel");
                    check(found.size() == 1, "barrels on plot: " + found.size() + ", plot " + ship.get().plotBox());
                    BlockPos plot = found.get(0).getBlockPos();
                    check(plot.getX() > 20_000_000, "barrel is not on plot: " + plot);
                    Vec3 inWorld = ship.get().toWorld(Vec3.atCenterOf(plot));
                    check(inWorld.distanceTo(Vec3.atCenterOf(barrel)) < 1.5, "plot barrel in world: " + inWorld + ", assembled at " + barrel);
                    check(ship.get().toPlot(inWorld).distanceTo(Vec3.atCenterOf(plot)) < 1e-6, "plot → world → plot");
                    check(ship.get().blockEntities("minecraft:chest").isEmpty(), "chest on a ship without a chest");
                    // The script accesses ships through gm.ship: Groovy cannot load Sable internals (signatures with ClientLevel).
                    JsonObject r = GmGameTests.now(GmGameTests.call(h, "script", GmGameTests.params("{\"code\":"
                            + "\"def s = gm.ship('GmTestShip'); return [s.name(), s.blockEntities('minecraft:barrel').size(), s.velocity().y < 1]\"}")))
                            .getAsJsonObject();
                    check(r.get("ok").getAsBoolean() && r.get("value").toString().equals("[\"GmTestShip\",1,true]"), "gm.ship from script: " + r);
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
            check(moved.y < -1, "ship is not falling: displacement " + moved + " blocks/s, positions " + pos);
            check(Math.abs(mean.y - moved.y) < 0.3 * Math.abs(moved.y) + 0.2, "velocity " + mean + " is not in blocks per second: displacement " + moved + ", velocities " + vel);
            check(ship.get().angularVelocity().length() < 0.2, "fall without rotation: ω " + ship.get().angularVelocity());
            // Spin around the vertical axis: Sable velocity takes a plot point, not a world point (otherwise error is ω × 2·10^7 blocks).
            yaw.set(yaw(ship.get()));
            RigidBodyHandle.of(sub.get()).addLinearAndAngularVelocity(new Vector3d(), new Vector3d(0, 1, 0));
        }).thenExecuteAfter(5, () -> {
            RigidBodyHandle body = RigidBodyHandle.of(sub.get());
            Vec3 lin = vec(body.getLinearVelocity()), omega = vec(body.getAngularVelocity());
            check(omega.y > 0.5, "ship did not spin: Sable ω " + omega);
            double turned = Math.abs(Math.toDegrees(yaw(ship.get()) - yaw.get()));
            check(turned > 5, "ship did not rotate: " + turned + "°");
            check(ship.get().velocity().distanceTo(lin) < 1e-3, "spinning ship velocity " + ship.get().velocity() + ", Sable " + lin);
            check(ship.get().angularVelocity().distanceTo(omega) < 1e-3, "angular velocity " + ship.get().angularVelocity() + ", Sable " + omega);
            Vec3 arm = new Vec3(2, 0, 1);
            Vec3 expected = lin.add(omega.cross(arm));
            check(ship.get().velocityAt(ship.get().pos().add(arm)).distanceTo(expected) < 1e-3,
                    "velocity of side point " + ship.get().velocityAt(ship.get().pos().add(arm)) + ", expected " + expected);
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

    /** Lease for chunk {@code c}, shifted {@code dx} blocks along x. */
    private static Areas.Lease acquire(GmServer gm, ServerLevel level, ChunkPos c, int dx) {
        try {
            return gm.areas().acquire(level, "проверка", c.getMinBlockX() + dx, c.getMinBlockZ(), c.getMaxBlockX() + dx, c.getMaxBlockZ(), 0);
        } catch (RpcException e) {
            throw new GameTestAssertException(e.getMessage());
        }
    }
}
