package ua.zentix.almighty.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.bot.Bots;
import ua.zentix.almighty.command.AlmightyCommand;
import ua.zentix.almighty.feed.FeedListeners;
import ua.zentix.almighty.feed.GmCommand;
import ua.zentix.almighty.mcp.signin.SignInCommand;
import ua.zentix.almighty.platform.Platform;
import ua.zentix.almighty.world.Areas;

/**
 * Fabric entry point: sets the {@link Platform}, loads {@code config/almighty-common.toml}, registers the GM chunk
 * ticket type and routes Fabric API callbacks to the common core ({@link Almighty}). What Fabric API has no callback
 * for comes from this package's mixins: advancements ({@code PlayerAdvancementsMixin}) and player commands
 * ({@code CommandsMixin}); bot name markers are the bot's own ({@code BotPlayer}).
 */
public final class AlmightyFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Platform.set(new FabricPlatform());
        FabricConfig.load(FabricLoader.getInstance().getConfigDir().resolve(Almighty.ID + "-common.toml"));
        Registry.register(BuiltInRegistries.TICKET_TYPE, Areas.TICKET_ID, Areas.TICKET);

        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            GmCommand.register(dispatcher);
            SignInCommand.register(dispatcher);
            AlmightyCommand.register(dispatcher);
        });
        // A real player's login takes a bot's name back first, then the feed sees the join (NeoForge: HIGHEST, LOWEST).
        // Fabric API fires these where NeoForge fires its logged-in/out events: the end of PlayerList.placeNewPlayer and
        // the start of PlayerList.remove (bots and test players included, which have no network connection to close).
        ServerPlayerEvents.JOIN.register(player -> {
            Bots.onLogin(player);
            FeedListeners.onLogin(player);
        });
        ServerPlayerEvents.LEAVE.register(FeedListeners::onLogout);
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> FeedListeners.onChat(sender, message.signedContent()));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer p) FeedListeners.onDeath(p, source);
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) ->
                FeedListeners.onDimension(player, from.dimension(), to.dimension()));

        ServerLifecycleEvents.SERVER_STARTED.register(Almighty::started);
        ServerTickEvents.START_SERVER_TICK.register(Almighty::tickStart);
        ServerTickEvents.END_SERVER_TICK.register(Almighty::tickEnd);
        ServerLifecycleEvents.SERVER_STOPPING.register(Almighty::stopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(Almighty::stopped);
    }
}
