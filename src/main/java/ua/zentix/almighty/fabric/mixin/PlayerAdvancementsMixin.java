package ua.zentix.almighty.fabric.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.zentix.almighty.feed.FeedListeners;

/** The feed's advancements (NeoForge: {@code AdvancementEarnEvent}): an advancement that a criterion completed. */
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    @Unique
    private boolean almighty$wasDone;

    @Shadow
    public abstract net.minecraft.advancements.AdvancementProgress getOrStartProgress(AdvancementHolder advancement);

    @Inject(method = "award", at = @At("HEAD"))
    private void almighty$before(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        almighty$wasDone = getOrStartProgress(holder).isDone();
    }

    @Inject(method = "award", at = @At("RETURN"))
    private void almighty$after(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !almighty$wasDone && getOrStartProgress(holder).isDone()) FeedListeners.onAdvancement(player, holder);
    }
}
