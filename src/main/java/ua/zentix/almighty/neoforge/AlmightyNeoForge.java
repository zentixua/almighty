package ua.zentix.almighty.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.bot.Bots;
import ua.zentix.almighty.command.AlmightyCommand;
import ua.zentix.almighty.feed.FeedListeners;
import ua.zentix.almighty.feed.GmCommand;
import ua.zentix.almighty.mcp.signin.SignInCommand;
import ua.zentix.almighty.platform.Platform;

/**
 * NeoForge entry point: sets the {@link Platform}, registers the config and routes NeoForge events to the common
 * core ({@link Almighty}).
 */
@Mod(Almighty.ID)
public final class AlmightyNeoForge {
    public AlmightyNeoForge(IEventBus modBus, ModContainer container) {
        NeoForgePlatform platform = new NeoForgePlatform();
        Platform.set(platform);
        container.registerConfig(ModConfig.Type.COMMON, NeoForgeConfig.build());
        IEventBus bus = NeoForge.EVENT_BUS;
        // Feed: observe only, last (the bus does not deliver events canceled by another mod here).
        bus.addListener(EventPriority.LOWEST, (ServerChatEvent e) -> FeedListeners.onChat(e.getPlayer(), e.getRawText()));
        bus.addListener(EventPriority.LOWEST, (PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) FeedListeners.onLogin(p);
        });
        bus.addListener(EventPriority.LOWEST, (PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) FeedListeners.onLogout(p);
        });
        bus.addListener(EventPriority.LOWEST, (LivingDeathEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) FeedListeners.onDeath(p, e.getSource());
        });
        bus.addListener(EventPriority.LOWEST, (AdvancementEvent.AdvancementEarnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) FeedListeners.onAdvancement(p, e.getAdvancement());
        });
        bus.addListener(EventPriority.LOWEST, (PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) FeedListeners.onDimension(p, e.getFrom(), e.getTo());
        });
        bus.addListener(EventPriority.LOWEST, (CommandEvent e) -> FeedListeners.onCommand(e.getParseResults()));
        bus.addListener((RegisterCommandsEvent e) -> {
            GmCommand.register(e.getDispatcher());
            SignInCommand.register(e.getDispatcher());
            AlmightyCommand.register(e.getDispatcher());
        });
        bus.addListener((PermissionGatherEvent.Nodes e) -> e.addNodes(platform.nodes().toArray(new PermissionNode<?>[0])));
        // Bots: marker before the name, and a real player's login takes the name back.
        bus.addListener(EventPriority.LOW, (PlayerEvent.TabListNameFormat e) -> {
            var name = Bots.tabName(e.getEntity(), e.getDisplayName());
            if (name != null) e.setDisplayName(name);
        });
        bus.addListener(EventPriority.LOW, (PlayerEvent.NameFormat e) -> {
            var name = Bots.displayName(e.getEntity(), e.getDisplayname());
            if (name != null) e.setDisplayname(name);
        });
        bus.addListener(EventPriority.HIGHEST, (PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) Bots.onLogin(p);
        });
        // Lifecycle.
        bus.addListener((ServerStartedEvent e) -> Almighty.started(e.getServer()));
        bus.addListener((ServerTickEvent.Pre e) -> Almighty.tickStart(e.getServer()));
        bus.addListener((ServerTickEvent.Post e) -> Almighty.tickEnd(e.getServer()));
        // Normal priority: GM tickets are removed before Incoming's StopDrain (LOWEST) waits for generation.
        bus.addListener((ServerStoppingEvent e) -> Almighty.stopping(e.getServer()));
        bus.addListener((ServerStoppedEvent e) -> Almighty.stopped(e.getServer()));
    }
}
