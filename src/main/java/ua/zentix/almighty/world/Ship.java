package ua.zentix.almighty.world;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.List;
import java.util.UUID;

/**
 * A physics-mod ship (currently Sable, used by Create Aeronautics craft): its blocks live on a distant plot
 * in the same world, with a projection into the world carrying position, rotation and velocity. Values are current
 * at call time; server thread. Scripts use {@code gm.ship(name)}, the bridge uses {@code ships};
 * the source is {@code Compat.ships()}.
 */
public interface Ship {
    /** Player-assigned ship name; null if unnamed. */
    String name();

    UUID uuid();

    ServerLevel level();

    /** Current world position of the ship's pivot ({@link #pivot()} on the plot). */
    Vec3 pos();

    /** Pivot on the plot. */
    Vec3 pivot();

    /** Rotation since assembly: plot direction to world direction. */
    Quaterniond orientation();

    /** World velocity of the pivot, in blocks per second. */
    Vec3 velocity();

    /** Velocity of a world point moving with the ship, in blocks per second. */
    Vec3 velocityAt(Vec3 world);

    /** Angular velocity, in radians per second, along world axes (right-hand rule). */
    Vec3 angularVelocity();

    /** Ship bounds in the world. */
    AABB box();

    /** Bounds of its blocks on the plot (with a margin: derived from world bounds). */
    BoundingBox plotBox();

    /** Plot point to its world position. */
    Vec3 toWorld(Vec3 plot);

    /** World point to the plot position currently occupying it. */
    Vec3 toPlot(Vec3 world);

    /** Plot direction (the bow as built) to world direction. */
    Vec3 toWorldDir(Vec3 plotDir);

    /**
     * Ship block entities in ready chunks of its plot (engines, burners, rudders): type is an id
     * ({@code create:propeller_bearing}); null means all types.
     */
    List<BlockEntity> blockEntities(String type);

    /** For the bridge response: name, position, rotation, velocities and bounds. */
    JsonObject describe();
}
