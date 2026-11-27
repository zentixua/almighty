package ua.zentix.almighty.gametest.v26;

//? if >=26.1 {
/*import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.gametest.GmAuditGameTests;
import ua.zentix.almighty.gametest.GmBotGameTests;
import ua.zentix.almighty.gametest.GmConfirmGameTests;
import ua.zentix.almighty.gametest.GmGameTests;
import ua.zentix.almighty.gametest.GmGateGameTests;
import ua.zentix.almighty.gametest.GmInboxGameTests;
import ua.zentix.almighty.gametest.GmJournalGameTests;
import ua.zentix.almighty.gametest.GmLadderGameTests;
import ua.zentix.almighty.gametest.GmLimitsGameTests;
import ua.zentix.almighty.gametest.GmRuleGameTests;
import ua.zentix.almighty.gametest.GmSafetyGameTests;
import ua.zentix.almighty.gametest.GmScriptGameTests;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

// 26.x GameTest registration. 1.21.1 NeoForge scans @GameTestHolder classes for @GameTest methods; 26.x tests are
// registry entries (a test function in TEST_FUNCTION, a GameTestInstance in the test-instance registry, its batch an
// environment). This registers each @GameTest method of the listed classes with its 1.21.1 attributes: template
// (namespace:template, as @PrefixGameTestTemplate(false)), timeout, setup ticks, required, sky access, and its
// batch as an empty environment of the same name, so tests share a batch exactly as on 1.21.1 (26.x batches by
// environment). Ids: <namespace>:<class>.<method>, lower case, as NeoForge 1.21.1 names them.
//
// Positions: 1.21.1 counts relative positions from the structure block under the template (floor stone at y=1);
// 26.x from the template's corner (floor at y=0). Each test gets a GameTestHelper whose origin is one block lower,
// so the shared test bodies keep the 1.21.1 coordinates.
@EventBusSubscriber(modid = Almighty.ID)
public final class GmTestRegistry {
    private GmTestRegistry() {}

    // Sable has no 26.x build: GmSableGameTests is a 1.21.1 class only.
    private static final List<Class<?>> CLASSES = List.of(GmGameTests.class, GmAuditGameTests.class, GmBotGameTests.class,
            GmConfirmGameTests.class, GmGateGameTests.class, GmInboxGameTests.class, GmJournalGameTests.class,
            GmLadderGameTests.class, GmLimitsGameTests.class, GmRuleGameTests.class, GmSafetyGameTests.class,
            GmScriptGameTests.class);

    private record Entry(Identifier id, Method method, GameTest test, String namespace) {}

    private static List<Entry> entries;

    private static synchronized List<Entry> entries() {
        if (entries != null) return entries;
        List<Entry> out = new ArrayList<>();
        for (Class<?> c : CLASSES) {
            GameTestHolder holder = c.getAnnotation(GameTestHolder.class);
            String namespace = holder == null ? Almighty.ID : holder.value();
            List<Method> methods = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) if (m.isAnnotationPresent(GameTest.class)) methods.add(m);
            methods.sort(Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1 || m.getParameterTypes()[0] != GameTestHelper.class) {
                    throw new IllegalStateException("GameTest " + c.getName() + "." + m.getName() + " must be static void (GameTestHelper)");
                }
                m.setAccessible(true);
                String path = (c.getSimpleName() + "." + m.getName()).toLowerCase(Locale.ROOT);
                out.add(new Entry(Identifier.fromNamespaceAndPath(namespace, path), m, m.getAnnotation(GameTest.class), namespace));
            }
        }
        entries = List.copyOf(out);
        return entries;
    }

    @SubscribeEvent
    static void functions(RegisterEvent e) {
        e.register(Registries.TEST_FUNCTION, helper -> {
            for (Entry entry : entries()) helper.register(entry.id(), run(entry.method()));
        });
    }

    @SubscribeEvent
    static void tests(RegisterGameTestsEvent e) {
        Map<String, Holder<TestEnvironmentDefinition<?>>> batches = new HashMap<>();
        for (Entry entry : entries()) {
            GameTest t = entry.test();
            Holder<TestEnvironmentDefinition<?>> batch = batches.computeIfAbsent(entry.namespace() + ":" + t.batch(),
                    k -> e.registerEnvironment(Identifier.fromNamespaceAndPath(entry.namespace(), "batch/" + t.batch().toLowerCase(Locale.ROOT))));
            String template = t.template().contains(":") ? t.template() : entry.namespace() + ":" + t.template();
            // 26.3 adds the test's dimension (the Overworld, as before).
*///?}
//? if >=26.3 {
/*            TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(batch, net.minecraft.world.level.Level.OVERWORLD, Identifier.parse(template),
                    t.timeoutTicks(), (int) t.setupTicks(), t.required(), Rotation.NONE, false, 1, 1, t.skyAccess(), 0);
*///?} else if >=26.1 {
/*            TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(batch, Identifier.parse(template), t.timeoutTicks(),
                    (int) t.setupTicks(), t.required(), Rotation.NONE, false, 1, 1, t.skyAccess(), 0);
*///?}
//? if >=26.1 {
/*            e.registerTest(entry.id(), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, entry.id()), data));
        }
    }

    private static Consumer<GameTestHelper> run(Method method) {
        return helper -> {
            try {
                method.invoke(null, new Shifted(helper.testInfo));
            } catch (InvocationTargetException ex) {
                if (ex.getCause() instanceof RuntimeException r) throw r;
                if (ex.getCause() instanceof Error r) throw r;
                throw new IllegalStateException(ex.getCause());
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        };
    }

    // A helper whose relative origin is the block under the template, as 1.21.1's structure block.
    private static final class Shifted extends GameTestHelper {
        Shifted(GameTestInfo info) {
            super(info);
        }

        private BlockPos origin() {
            return testInfo.getTestOrigin().below();
        }

        @Override
        public BlockPos absolutePos(BlockPos relativePos) {
            BlockPos o = origin();
            return StructureTemplate.transform(o.offset(relativePos), Mirror.NONE, testInfo.getRotation(), o);
        }

        @Override
        public BlockPos relativePos(BlockPos absolutePos) {
            BlockPos o = origin();
            Rotation inverse = testInfo.getRotation().getRotated(Rotation.CLOCKWISE_180);
            return StructureTemplate.transform(absolutePos, Mirror.NONE, inverse, o).subtract(o);
        }

        @Override
        public Vec3 absoluteVec(Vec3 relativeVec) {
            BlockPos o = origin();
            return StructureTemplate.transform(Vec3.atLowerCornerOf(o).add(relativeVec), Mirror.NONE, testInfo.getRotation(), o);
        }

        @Override
        public Vec3 relativeVec(Vec3 absoluteVec) {
            BlockPos o = origin();
            return StructureTemplate.transform(absoluteVec.subtract(Vec3.atLowerCornerOf(o)), Mirror.NONE, testInfo.getRotation(), o);
        }
    }
}
*///?}
