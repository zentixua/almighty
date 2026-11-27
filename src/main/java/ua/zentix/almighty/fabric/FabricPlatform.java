package ua.zentix.almighty.fabric;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
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
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.platform.GameEvents;
import ua.zentix.almighty.platform.Platform;
import ua.zentix.almighty.policy.Node;
import ua.zentix.almighty.policy.Nodes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** {@link Platform} on Fabric. */
final class FabricPlatform implements Platform {
    /** lucko's fabric-permissions-api (LuckPerms implements it); without it the role ladder answers. */
    static final String PERMISSIONS_MOD = "fabric-permissions-api-v0";
    private final GameEvents events = new FabricEvents();

    @Override
    public String loader() {
        return "fabric";
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer(Almighty.ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    @Override
    public boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    @Override
    public List<ModInfo> mods() {
        List<ModInfo> out = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            ModMetadata m = mod.getMetadata();
            out.add(new ModInfo(m.getId(), m.getName(), m.getVersion().getFriendlyString(), m.getDescription(),
                    m.getContact().get("homepage").orElse(null)));
        }
        return out;
    }

    /** Fabric has no shared config system: other mods' settings are their own files, not listed. */
    @Override
    public List<ConfigValue> modConfigValues() {
        return List.of();
    }

    @Override
    public GameEvents events() {
        return events;
    }

    @Override
    public Boolean permission(UUID player, ServerPlayer online, Node node) {
        if (!FabricLoader.getInstance().isModLoaded(PERMISSIONS_MOD)) return null;
        String name = Almighty.ID + "." + node.nodeName();
        boolean fallback = Nodes.byDefault(player, node);
        try {
            if (online != null) return me.lucko.fabric.api.permissions.v0.Permissions.check(online, name, fallback);
            return me.lucko.fabric.api.permissions.v0.Permissions.check(player, name, fallback).get(2, TimeUnit.SECONDS);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** Fabric negotiates mod channels per connection; a bot's in-memory connection takes only vanilla packets. */
    @Override
    public void configureMockConnection(Connection connection) {}

    @Override
    public CommonListenerCookie cookie(GameProfile profile, ClientInformation info) {
        return new CommonListenerCookie(profile, 0, info, false);
    }

    @Override
    public RegistryFriendlyByteBuf registryBuffer(ByteBuf buf, RegistryAccess access) {
        return new RegistryFriendlyByteBuf(buf, access);
    }

    /** Fabric API's entity-use callback, as the server runs it for a client's "interact at". */
    @Override
    public InteractionResult interactEntityAt(Player player, Entity entity, Vec3 at, InteractionHand hand) {
        InteractionResult r = UseEntityCallback.EVENT.invoker().interact(player, player.level(), hand, entity, new EntityHitResult(entity, at));
        return r == InteractionResult.PASS ? null : r;
    }

    /** The server's chat decorator (Fabric API's message decorator events hook into it). */
    @Override
    public Component decorateChat(ServerPlayer player, Component text) {
        return player.level().getServer().getChatDecorator().decorate(player, text);
    }
}
