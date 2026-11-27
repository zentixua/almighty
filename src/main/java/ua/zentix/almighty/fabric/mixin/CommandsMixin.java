package ua.zentix.almighty.fabric.mixin;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.almighty.feed.FeedListeners;

/** The feed's player commands (NeoForge: {@code CommandEvent}): every parsed command before it runs. */
@Mixin(Commands.class)
abstract class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"))
    private void almighty$command(ParseResults<CommandSourceStack> command, String commandString, CallbackInfo ci) {
        FeedListeners.onCommand(command);
    }
}
