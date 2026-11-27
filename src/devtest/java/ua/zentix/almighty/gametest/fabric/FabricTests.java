package ua.zentix.almighty.gametest.fabric;

//? if fabric {
/*import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.ResourceKey;
import ua.zentix.almighty.gametest.v26.GmTestRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The 26.x tests on Fabric. Fabric API's own @GameTest wants instance methods of an entry point and environments that
// already exist as data; ours are the shared static methods with their 1.21.1 attributes, so this registers them as
// NeoForgeTests does: functions at init, batches (empty environments) and instances while the test registries load,
// right after Fabric API registers its own (FabricGameTestMixin).
public final class FabricTests implements ModInitializer {
    @Override
    public void onInitialize() {
        for (GmTestRegistry.Entry entry : GmTestRegistry.entries()) Registry.register(BuiltInRegistries.TEST_FUNCTION, entry.id(), entry.function());
    }

    @SuppressWarnings("unchecked")
    public static void register(List<RegistryLoadTask<?>> tasks) {
        Registry<GameTestInstance> instances = null;
        Registry<TestEnvironmentDefinition<?>> environments = null;
        for (RegistryLoadTask<?> task : tasks) {
            Registry<?> registry;
            try {
                // Fabric API opens this field at run time for the same purpose; tests read it by reflection.
                registry = (Registry<?>) ua.zentix.almighty.platform.Reflect.field(RegistryLoadTask.class, "registry").get(task);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            if (registry.key() == Registries.TEST_INSTANCE) instances = (Registry<GameTestInstance>) registry;
            if (registry.key() == Registries.TEST_ENVIRONMENT) environments = (Registry<TestEnvironmentDefinition<?>>) registry;
        }
        if (instances == null || environments == null) return;
        Map<Identifier, Holder<TestEnvironmentDefinition<?>>> batches = new HashMap<>();
        for (GmTestRegistry.Entry entry : GmTestRegistry.entries()) {
            Registry<TestEnvironmentDefinition<?>> envs = environments;
            Holder<TestEnvironmentDefinition<?>> batch = batches.computeIfAbsent(entry.batch(), id -> Registry.registerForHolder(envs,
                    ResourceKey.create(Registries.TEST_ENVIRONMENT, id), new TestEnvironmentDefinition.AllOf(List.of())));
            Registry.register(instances, entry.id(), entry.instance(batch));
        }
    }
}
*///?}
