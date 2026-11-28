package ua.zentix.almighty.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import ua.zentix.almighty.building.BuildJob;

/**
 * Lead's N9: while a build, undo or revert writes ({@link BuildJob#writing}), a block that breaks because of it (a
 * lantern hanging from a ceiling the undo removed, a torch on a removed wall) drops no item: vanilla clears
 * {@code UPDATE_SUPPRESS_DROPS} for the shape updates of neighbours, so the job's own flag does not reach them.
 * Anything else that destroys a block (a player, an explosion, another mod) is untouched: one thread-local read.
 * Not covered: blocks that pop off in {@code neighborChanged} through {@code dropResources} (rails, redstone wire,
 * repeaters) rather than {@code destroyBlock}.
 */
@Mixin(Level.class)
abstract class LevelMixin {
    @ModifyVariable(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"),
            argsOnly = true, ordinal = 0)
    private boolean almighty$noDropWhileWriting(boolean drop) {
        return drop && !BuildJob.writing();
    }
}
