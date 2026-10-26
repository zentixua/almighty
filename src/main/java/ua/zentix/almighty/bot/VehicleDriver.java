package ua.zentix.almighty.bot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * Driver for mod vehicles controlled by the driver's client code (reading client keys, computing pilot flight),
 * which the common path cannot handle (player input, {@code almighty:driven_by_rider} tag). Only in mod
 * compatibility ({@code compat/}); bots obtain drivers from {@code Compat}. One instance per bot; server thread.
 */
public interface VehicleDriver {
    /** Whether this driver controls the vehicle. */
    boolean drives(Entity vehicle);

    /**
     * Bot pilot tick after the vehicle tick: x left (+1) or right (-1), y jump (+1) or sneak (-1),
     * z forward (+1) or backward (-1).
     */
    void tick(ServerPlayer pilot, Entity vehicle, float x, float y, float z);

    /** Mod keys beyond vanilla controls: names in control settings. */
    List<String> keys();

    /** Mod key: returns what was done; null if this driver has no such key. */
    String press(ServerPlayer pilot, Entity vehicle, String key);
}
