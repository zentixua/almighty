package ua.zentix.almighty;

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
import ua.zentix.almighty.bot.Bots;
import ua.zentix.almighty.feed.FeedListeners;
import ua.zentix.almighty.feed.GmCommand;

/**
 * Game master mod: a bridge through which an AI agent sees the world and acts on the server.
 * Server only: no custom blocks, items or packets; clients need not install it.
 */
@Mod(Almighty.ID)
public final class Almighty {
    public static final String ID = "almighty";

    public Almighty(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, GmConfig.SPEC);
        IEventBus bus = NeoForge.EVENT_BUS;
        FeedListeners.register(bus);
        GmCommand.register(bus);
        Bots.register(bus);
        bus.addListener(Almighty::onStarted);
        bus.addListener(Almighty::onTickStart);
        bus.addListener(Almighty::onTickEnd);
        bus.addListener(Almighty::onStopping);
        bus.addListener(Almighty::onStopped);
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

    /** Normal priority: GM tickets are removed before Incoming's {@code StopDrain} ({@code LOWEST}) waits for generation. */
    private static void onStopping(ServerStoppingEvent e) {
        GmServer gm = GmServer.of(e.getServer());
        if (gm != null) gm.stop();
    }

    private static void onStopped(ServerStoppedEvent e) {
        GmServer.stopped(e.getServer());
    }
}
