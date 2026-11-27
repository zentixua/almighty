package ua.zentix.almighty.compat;

import ua.zentix.almighty.platform.Platform;
import ua.zentix.almighty.bot.VehicleDriver;
import ua.zentix.almighty.compat.immersiveaircraft.ImmersiveAircraftDriver;
//? if sable
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
        if (Platform.get().isModLoaded(ImmersiveAircraftDriver.MOD_ID)) drivers.add(new ImmersiveAircraftDriver());
        return drivers;
    }

    /** Claim mods installed, each queried through its own API (plan 4.7). */
    public static ua.zentix.almighty.world.Claims claims() {
        List<ua.zentix.almighty.world.Claims.Source> s = new ArrayList<>(2);
        if (Platform.get().isModLoaded(ua.zentix.almighty.compat.opac.OpacClaims.MOD_ID)) s.add(new ua.zentix.almighty.compat.opac.OpacClaims());
        if (Platform.get().isModLoaded(ua.zentix.almighty.compat.ftbchunks.FtbChunksClaims.MOD_ID)) s.add(new ua.zentix.almighty.compat.ftbchunks.FtbChunksClaims());
        return new ua.zentix.almighty.world.Claims(s);
    }

    /** Physics-mod ships: Sable (Create Aeronautics); null without the mod (or on a game version Sable has no build for). */
    public static Ships.Source ships() {
        //? if sable {
        return Platform.get().isModLoaded(SableShips.MOD_ID) ? new SableShips() : null;
        //?} else {
        /*return null;
        *///?}
    }
}
