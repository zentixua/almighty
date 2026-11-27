package ua.zentix.almighty.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ua.zentix.almighty.audit.Journal;

/**
 * The change journal's only hook (plan 4.9): no event reports every block write, and commands, functions and scripts
 * resolve their targets only at run time. Records a write only while GM work runs on this thread
 * ({@link Journal#current}); otherwise it costs one volatile read. The pattern Ledger uses on Fabric.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void almighty$before(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        if (!Journal.recording()) return;
        Journal.Change c = Journal.current();
        if (c == null) return;
        LevelChunk self = (LevelChunk) (Object) this;
        BlockEntity be = self.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        c.before(be == null ? null : be.saveWithFullMetadata(self.getLevel().registryAccess()));
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void almighty$after(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        if (!Journal.recording()) return;
        Journal.Change c = Journal.current();
        if (c == null) return;
        BlockState old = cir.getReturnValue();
        LevelChunk self = (LevelChunk) (Object) this;
        // Null: nothing changed (same state). Block-entity-only changes are not recorded (inventories are not undoable).
        if (old != null) c.record(self.getLevel().dimension().location().toString(), pos, old, state, c.takeBefore());
        else c.takeBefore();
    }
}
