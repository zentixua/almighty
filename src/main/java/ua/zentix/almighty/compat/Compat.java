package ua.zentix.almighty.compat;

import net.neoforged.fml.ModList;
import ua.zentix.almighty.bot.VehicleDriver;
import ua.zentix.almighty.compat.immersiveaircraft.ImmersiveAircraftDriver;

import java.util.ArrayList;
import java.util.List;

/**
 * Совместимость с модами — только то, чего общими путями (пакеты клиента, ввод игрока, метки, памятки) не сделать.
 * Модуль мода — в {@code compat/<мод>}, мод — {@code compileOnly}: класс модуля грузится, только когда мод стоит.
 */
public final class Compat {
    private Compat() {}

    /** Приводы транспорта модов, которые стоят, — новые экземпляры для бота. */
    public static List<VehicleDriver> vehicleDrivers() {
        List<VehicleDriver> drivers = new ArrayList<>(1);
        if (ModList.get().isLoaded(ImmersiveAircraftDriver.MOD_ID)) drivers.add(new ImmersiveAircraftDriver());
        return drivers;
    }
}
