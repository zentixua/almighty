package ua.zentix.almighty.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.almighty.audit.Journal;
import ua.zentix.almighty.platform.Mc;

/**
 * The journal keeps a container's contents when a GM command empties it before writing there: on 1.21.1
 * {@code /fill}, {@code /setblock} and {@code /clone} call {@code Clearable.tryClear} first, so the write's own hook
 * ({@link LevelChunkMixin}) would see an empty chest and an undo would bring it back empty. 26.x commands no longer
 * clear (the write skips dropping contents instead), so there is nothing to hook there ({@code require = 0}).
 */
@Mixin(Clearable.class)
interface ClearableMixin {
    @Inject(method = "tryClear", at = @At("HEAD"), require = 0)
    private static void almighty$keep(Object object, CallbackInfo ci) {
        if (!Journal.recording() || !(object instanceof BlockEntity be) || be.getLevel() == null) return;
        Journal.Change c = Journal.current();
        if (c == null) return;
        try {
            c.cleared(Mc.id(be.getLevel().dimension()).toString(), be.getBlockPos(), be.saveWithFullMetadata(be.getLevel().registryAccess()));
        } catch (RuntimeException e) {
            LogUtils.getLogger().warn("Almighty journal: could not keep contents at {}: {}", be.getBlockPos(), e.toString());
        }
    }
}
