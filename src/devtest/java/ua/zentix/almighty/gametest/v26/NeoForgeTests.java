package ua.zentix.almighty.gametest.v26;

//? if neoforge && >=26.1 {
/*import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import ua.zentix.almighty.Almighty;

import java.util.HashMap;
import java.util.Map;

// The 26.x tests on NeoForge: functions through RegisterEvent, batches and instances through RegisterGameTestsEvent.
@EventBusSubscriber(modid = Almighty.ID)
public final class NeoForgeTests {
    private NeoForgeTests() {}

    @SubscribeEvent
    static void functions(RegisterEvent e) {
        e.register(Registries.TEST_FUNCTION, helper -> {
            for (GmTestRegistry.Entry entry : GmTestRegistry.entries()) helper.register(entry.id(), entry.function());
        });
    }

    @SubscribeEvent
    static void tests(RegisterGameTestsEvent e) {
        Map<net.minecraft.resources.Identifier, Holder<TestEnvironmentDefinition<?>>> batches = new HashMap<>();
        for (GmTestRegistry.Entry entry : GmTestRegistry.entries()) {
            Holder<TestEnvironmentDefinition<?>> batch = batches.computeIfAbsent(entry.batch(), e::registerEnvironment);
            e.registerTest(entry.id(), entry.instance(batch));
        }
    }
}
*///?}
