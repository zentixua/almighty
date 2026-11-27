package ua.zentix.almighty.fabric;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Unit tests on Fabric: game classes that read the registries at class init (commands) need the game bootstrapped,
 * which NeoForge's unit-test launcher does and plain JUnit on Fabric does not. Registered for Fabric nodes only
 * (src/fabric/test, META-INF/services).
 */
public final class GameBootstrap implements LauncherSessionListener {
    @Override
    public void launcherSessionOpened(LauncherSession session) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
