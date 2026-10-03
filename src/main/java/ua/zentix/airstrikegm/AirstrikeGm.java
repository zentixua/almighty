package ua.zentix.airstrikegm;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrikegm.feed.FeedListeners;

/**
 * Мод ведущего: мост, через который Claude видит мир и действует на сервере. Только сервер: своих блоков, предметов и
 * пакетов нет, клиентам ставить не нужно.
 */
@Mod(AirstrikeGm.ID)
public final class AirstrikeGm {
    public static final String ID = "airstrike_gm";

    public AirstrikeGm(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, GmConfig.SPEC);
        IEventBus bus = NeoForge.EVENT_BUS;
        FeedListeners.register(bus);
        bus.addListener(AirstrikeGm::onStarted);
        bus.addListener(AirstrikeGm::onTickStart);
        bus.addListener(AirstrikeGm::onTickEnd);
        bus.addListener(AirstrikeGm::onStopping);
        bus.addListener(AirstrikeGm::onStopped);
    }

    private static void onStarted(ServerStartedEvent e) {
        GmServer.start(e.getServer(), FMLPaths.CONFIGDIR.get().resolve(ID).resolve("token"));
    }

    private static void onTickStart(ServerTickEvent.Pre e) {
        GmServer gm = GmServer.of(e.getServer());
        if (gm != null) gm.tickStart();
    }

    private static void onTickEnd(ServerTickEvent.Post e) {
        GmServer gm = GmServer.of(e.getServer());
        if (gm != null) gm.tickEnd();
    }

    /** Обычный приоритет: тикеты ведущего сняты раньше, чем {@code StopDrain} Airstrike ({@code LOWEST}) ждёт генерацию. */
    private static void onStopping(ServerStoppingEvent e) {
        GmServer gm = GmServer.of(e.getServer());
        if (gm != null) gm.stop();
    }

    private static void onStopped(ServerStoppedEvent e) {
        GmServer.stopped(e.getServer());
    }
}
