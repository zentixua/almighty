package ua.zentix.almighty;

import net.minecraft.server.MinecraftServer;
import ua.zentix.almighty.platform.Platform;

/**
 * Game master mod: a bridge through which an AI agent sees the world and acts on the server.
 * Server only: no custom blocks, items or packets; clients need not install it. Each loader's entry point
 * ({@code neoforge/AlmightyNeoForge}) sets the {@link Platform} and calls the lifecycle hooks below.
 */
public final class Almighty {
    public static final String ID = "almighty";

    private Almighty() {}

    /** This mod's version, as in its jar. */
    public static String version() {
        return Platform.get().modVersion();
    }

    public static void started(MinecraftServer server) {
        GmServer.start(server, Platform.get().configDir().resolve(ID));
    }

    public static void tickStart(MinecraftServer server) {
        GmServer gm = GmServer.of(server);
        if (gm != null) gm.tickStart();
    }

    public static void tickEnd(MinecraftServer server) {
        GmServer gm = GmServer.of(server);
        if (gm != null) gm.tickEnd();
    }

    /** Before mods that wait for generation at shutdown: GM tickets go first (Incoming's {@code StopDrain}). */
    public static void stopping(MinecraftServer server) {
        GmServer gm = GmServer.of(server);
        if (gm != null) gm.stop();
    }

    public static void stopped(MinecraftServer server) {
        GmServer.stopped(server);
    }
}
