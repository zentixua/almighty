package ua.zentix.almighty.gametest.fabric.mixin;

//? if fabric {
/*import net.fabricmc.fabric.impl.gametest.FabricGameTestModInitializer;
import net.minecraft.resources.RegistryLoadTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.almighty.gametest.fabric.FabricTests;

import java.util.List;

// Fabric API registers its test instances while the game loads the test registries (before they freeze); ours join there.
@Mixin(FabricGameTestModInitializer.class)
abstract class FabricGameTestMixin {
    @Inject(method = "registerDynamicEntries", at = @At("TAIL"), remap = false)
    private static void almighty$register(List<RegistryLoadTask<?>> tasks, CallbackInfo ci) {
        FabricTests.register(tasks);
    }
}
*///?}
