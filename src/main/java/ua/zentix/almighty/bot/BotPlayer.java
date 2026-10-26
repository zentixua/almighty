package ua.zentix.almighty.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A bot player. The game moves player-controlled vehicles (boat, horse) where the driver is "local"
 * ({@code Entity.isControlledByLocalInstance} to {@code Player.isLocalPlayer}): a real player's client,
 * while the server waits for client coordinates. The bot's client is the server itself, so the bot is "local"
 * ({@link Vehicles#local}) for vehicles driven by rider input ({@code almighty:driven_by_rider}), and the server
 * computes their movement from bot input. Not for other vehicles: mod code behind that check may read client
 * keys absent on the server; these need a mod compatibility driver ({@link VehicleDriver}).
 * Respawn recreates the player with the same class ({@code mixin/PlayerListMixin}).
 */
public final class BotPlayer extends ServerPlayer {
    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
        super(server, level, profile, info);
    }

    @Override
    public boolean isLocalPlayer() {
        return Vehicles.local(this);
    }
}
