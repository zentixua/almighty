package ua.zentix.almighty.platform;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import ua.zentix.almighty.policy.Node;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * What the common core needs from the mod loader (NeoForge, Fabric): paths, the mod list, the event bus for rules,
 * permission nodes and the few loader hooks bots replay. One implementation per loader, set once by its entry point
 * before anything else runs ({@link #set}). Everything else in the mod is vanilla Minecraft and plain Java.
 */
public interface Platform {
    /** Loader id: {@code neoforge} or {@code fabric}. */
    String loader();

    /** The server's {@code config} directory. */
    Path configDir();

    /** This mod's version, as in its jar. */
    String modVersion();

    boolean isModLoaded(String id);

    /** A mod as the loader lists it. */
    record ModInfo(String id, String name, String version, String description, String url) {}

    List<ModInfo> mods();

    /** One value of a mod's loaded common or server config (secret-looking keys are filtered by the caller). */
    record ConfigValue(String mod, String file, String key, Object value) {}

    List<ConfigValue> modConfigValues();

    /** The game event bus that rules subscribe to. */
    GameEvents events();

    /**
     * The server's permission handler for a player and node: {@code true}/{@code false}, or null when no handler
     * answers (the node is unregistered or there is no handler); the caller then uses the role ladder.
     */
    Boolean permission(UUID player, ServerPlayer online, Node node);

    // --- Bots: the loader's parts of a connection and of the server's packet handling ---

    /** Give a bot's in-memory connection every channel a real client of this loader negotiates. */
    void configureMockConnection(Connection connection);

    /** A login cookie as for a client of this loader. */
    CommonListenerCookie cookie(GameProfile profile, ClientInformation info);

    /**
     * The loader's "player data loaded" event for a player about to log in (NeoForge: {@code PlayerEvent.LoadFromFile}).
     * 1.21.1's login fires it itself; 26.x fires it in its pre-login step, which bots skip ({@code Bots}).
     */
    void playerLoaded(ServerPlayer player);

    /** A registry-aware buffer as the loader's network layer creates it. */
    RegistryFriendlyByteBuf registryBuffer(ByteBuf buf, RegistryAccess access);

    /** The loader's entity-interaction hook, as the server runs it for a client's "interact at"; null = not handled. */
    InteractionResult interactEntityAt(Player player, Entity entity, Vec3 at, InteractionHand hand);

    /** Chat text after the loader's chat decorator (as for a player's chat message); null if a listener canceled it. */
    Component decorateChat(ServerPlayer player, Component text);

    // --- The one instance ---

    static Platform get() {
        Platform p = Holder.instance;
        if (p == null) throw new IllegalStateException("No mod loader platform (the loader's entry point sets it)");
        return p;
    }

    static boolean present() {
        return Holder.instance != null;
    }

    static void set(Platform platform) {
        Holder.instance = platform;
    }

    final class Holder {
        private static volatile Platform instance;

        private Holder() {}
    }
}
