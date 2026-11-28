package ua.zentix.almighty.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.zentix.almighty.audit.Journal;
import ua.zentix.almighty.platform.Mc;

/**
 * The change journal's only hook (plan 4.9): no event reports every block write, and commands, functions and scripts
 * resolve their targets only at run time. Records a write only while GM work runs on this thread
 * ({@link Journal#current}); otherwise it costs one volatile read. The pattern Ledger uses on Fabric.
 *
 * <p>Recorded at the start of the write, in the order writes really happen: a block that rewrites its own position
 * while being placed (redstone wire, rails) records the outer write first, so a revert in reverse order is exact.
 * Never throws into the game's write.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    //? if >=26.1 {
    /*private void almighty$record(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
    *///?} else {
    private void almighty$record(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
    //?}
        if (!Journal.recording()) return;
        Journal.Change c = Journal.current();
        if (c == null) return;
        try {
            LevelChunk self = (LevelChunk) (Object) this;
            BlockState before = self.getBlockState(pos);
            if (before == state) return;
            BlockEntity be = self.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
            String dim = Mc.id(self.getLevel().dimension()).toString();
            c.record(dim, pos, before, state, c.before(dim, pos, be == null ? null : be.saveWithFullMetadata(self.getLevel().registryAccess())));
        } catch (RuntimeException e) {
            LogUtils.getLogger().warn("Almighty journal: could not record a write at {}: {}", pos, e.toString());
        }
    }
}
