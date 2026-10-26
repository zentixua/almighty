package ua.zentix.almighty.compat;

import net.neoforged.fml.ModList;
import ua.zentix.almighty.bot.VehicleDriver;
import ua.zentix.almighty.compat.immersiveaircraft.ImmersiveAircraftDriver;
import ua.zentix.almighty.compat.sable.SableShips;
import ua.zentix.almighty.world.Ships;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod compatibility only for what common paths (client packets, player input, tags, notes) cannot do.
 * A mod's module lives in {@code compat/<mod>}; the mod is {@code compileOnly}: load the module class only when installed.
 */
public final class Compat {
    private Compat() {}

    /** Drivers for installed vehicle mods, as new instances for the bot. */
    public static List<VehicleDriver> vehicleDrivers() {
        List<VehicleDriver> drivers = new ArrayList<>(1);
        if (ModList.get().isLoaded(ImmersiveAircraftDriver.MOD_ID)) drivers.add(new ImmersiveAircraftDriver());
        return drivers;
    }

    /** Physics-mod ships: Sable (Create Aeronautics); null without the mod. */
    public static Ships.Source ships() {
        return ModList.get().isLoaded(SableShips.MOD_ID) ? new SableShips() : null;
    }
}
