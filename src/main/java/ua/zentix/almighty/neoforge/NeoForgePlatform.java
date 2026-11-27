package ua.zentix.almighty.neoforge;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
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
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.exceptions.UnregisteredPermissionException;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import net.neoforged.neoforgespi.language.IModInfo;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.platform.GameEvents;
import ua.zentix.almighty.platform.Platform;
import ua.zentix.almighty.policy.Node;
import ua.zentix.almighty.policy.Nodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@link Platform} on NeoForge. */
public final class NeoForgePlatform implements Platform {
    private final GameEvents events = new NeoForgeEvents();
    /** The {@link Node}s as NeoForge permission nodes; the default resolver is the role ladder. */
    private final Map<Node, PermissionNode<Boolean>> nodes = new EnumMap<>(Node.class);

    NeoForgePlatform() {
        for (Node n : Node.values()) {
            nodes.put(n, new PermissionNode<>(Almighty.ID, n.nodeName(), PermissionTypes.BOOLEAN,
                    (player, id, context) -> Nodes.byDefault(id, n)));
        }
    }

    Collection<PermissionNode<Boolean>> nodes() {
        return nodes.values();
    }

    @Override
    public String loader() {
        return "neoforge";
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public String modVersion() {
        return ModList.get().getModContainerById(Almighty.ID).map(c -> c.getModInfo().getVersion().toString()).orElse("dev");
    }

    @Override
    public boolean isModLoaded(String id) {
        return ModList.get().isLoaded(id);
    }

    @Override
    public List<ModInfo> mods() {
        List<ModInfo> out = new ArrayList<>();
        for (IModInfo mod : ModList.get().getMods()) {
            out.add(new ModInfo(mod.getModId(), mod.getDisplayName(), mod.getVersion().toString(), mod.getDescription(),
                    mod.getModURL().map(Object::toString).orElse(null)));
        }
        return out;
    }

    @Override
    public List<ConfigValue> modConfigValues() {
        List<ConfigValue> out = new ArrayList<>();
        for (IModInfo mod : ModList.get().getMods()) {
            for (ModConfig config : ModConfigs.getModConfigs(mod.getModId())) {
                if (config.getType() == ModConfig.Type.CLIENT) continue;
                IConfigSpec.ILoadedConfig loaded = config.getLoadedConfig();
                if (loaded == null) continue;
                flatten(mod.getModId(), config.getFileName(), "", loaded.config(), out);
            }
        }
        return out;
    }

    /** A NightConfig tree as dotted keys. */
    public static void flatten(String mod, String file, String prefix, UnmodifiableConfig config, List<ConfigValue> out) {
        for (UnmodifiableConfig.Entry e : config.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof UnmodifiableConfig sub) flatten(mod, file, key, sub, out);
            else out.add(new ConfigValue(mod, file, key, value));
        }
    }

    @Override
    public GameEvents events() {
        return events;
    }

    @Override
    public Boolean permission(UUID player, ServerPlayer online, Node node) {
        PermissionNode<Boolean> pn = nodes.get(node);
        try {
            Boolean v = online != null ? PermissionAPI.getPermission(online, pn) : PermissionAPI.getOfflinePermission(player, pn);
            return Boolean.TRUE.equals(v);
        } catch (UnregisteredPermissionException | IllegalStateException e) {
            // No handler yet or nodes not gathered: the ladder answers.
            return null;
        }
    }

    @Override
    public void configureMockConnection(Connection connection) {
        NetworkRegistry.configureMockConnection(connection);
    }

    @Override
    public CommonListenerCookie cookie(GameProfile profile, ClientInformation info) {
        return new CommonListenerCookie(profile, 0, info, false, ConnectionType.NEOFORGE);
    }

    @Override
    public void playerLoaded(ServerPlayer player) {
        //? if >=26.1 {
        /*net.neoforged.neoforge.event.EventHooks.firePlayerLoadingEvent(player, player.level().getServer().getPlayerList(), player.getStringUUID());
        *///?} else {
        net.neoforged.neoforge.event.EventHooks.firePlayerLoadingEvent(player,
                player.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).toFile(), player.getStringUUID());
        //?}
    }

    @Override
    public RegistryFriendlyByteBuf registryBuffer(ByteBuf buf, RegistryAccess access) {
        return new RegistryFriendlyByteBuf(buf, access, ConnectionType.NEOFORGE);
    }

    @Override
    public InteractionResult interactEntityAt(Player player, Entity entity, Vec3 at, InteractionHand hand) {
        return CommonHooks.onInteractEntityAt(player, entity, at, hand);
    }

    @Override
    public Component decorateChat(ServerPlayer player, Component text) {
        return CommonHooks.getServerChatSubmittedDecorator().decorate(player, text);
    }
}
