package ua.zentix.almighty.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ua.zentix.almighty.bot.BotPlayer;

/**
 * Respawn recreates the player ({@code new ServerPlayer} in {@code PlayerList.respawn}); no NeoForge event
 * can select its class. A bot must remain {@link BotPlayer} after death, or the server stops simulating
 * its vehicle. Bots only: the original call creates all other players. Carpet does the same.
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @WrapOperation(method = "respawn", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer almighty$respawnBot(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info,
                                             Operation<ServerPlayer> original, @Local(argsOnly = true) ServerPlayer old) {
        return old instanceof BotPlayer ? new BotPlayer(server, level, profile, info) : original.call(server, level, profile, info);
    }
}
