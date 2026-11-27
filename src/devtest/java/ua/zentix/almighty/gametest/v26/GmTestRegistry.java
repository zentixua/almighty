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
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

// 26.x GameTest registration (the loader-neutral part; NeoForgeTests and fabric/FabricTests hand it to the loader).
// 1.21.1 NeoForge scans @GameTestHolder classes for @GameTest methods; 26.x tests are
// registry entries (a test function in TEST_FUNCTION, a GameTestInstance in the test-instance registry, its batch an
// environment). This registers each @GameTest method of the listed classes with its 1.21.1 attributes: template
// (namespace:template, as @PrefixGameTestTemplate(false)), timeout, setup ticks, required, sky access, and its
// batch as an empty environment of the same name, so tests share a batch exactly as on 1.21.1 (26.x batches by
// environment). Ids: <namespace>:<class>.<method>, lower case, as NeoForge 1.21.1 names them.
//
// Positions: 1.21.1 counts relative positions from the structure block under the template (floor stone at y=1);
// 26.x from the template's corner (floor at y=0). Each test gets a GameTestHelper whose origin is one block lower,
// so the shared test bodies keep the 1.21.1 coordinates.
public final class GmTestRegistry {
    private GmTestRegistry() {}

    // Sable has no 26.x build: GmSableGameTests is a 1.21.1 class only.
    private static final List<Class<?>> CLASSES = List.of(GmGameTests.class, GmAuditGameTests.class, GmBotGameTests.class,
            GmConfirmGameTests.class, GmGateGameTests.class, GmInboxGameTests.class, GmJournalGameTests.class,
            GmLadderGameTests.class, GmLimitsGameTests.class, GmRuleGameTests.class, GmSafetyGameTests.class,
            GmScriptGameTests.class);

    // One test: its id, method, 1.21.1 attributes and namespace.
    public record Entry(Identifier id, Method method, GameTest test, String namespace) {
        // The test's batch as an environment id: {@code <namespace>:batch/<batch>}.
        public Identifier batch() {
            return Identifier.fromNamespaceAndPath(namespace, "batch/" + test.batch().toLowerCase(Locale.ROOT));
        }

        // The test's function (it runs the method with a 1.21.1-positioned helper).
        public Consumer<GameTestHelper> function() {
            return run(method);
        }

        // The test instance, its environment being its batch.
        public FunctionGameTestInstance instance(Holder<TestEnvironmentDefinition<?>> batch) {
            String template = test.template().contains(":") ? test.template() : namespace + ":" + test.template();
            return new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id), data(batch, Identifier.parse(template), test));
        }
    }

    private static List<Entry> entries;

    public static synchronized List<Entry> entries() {
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

    private static TestData<Holder<TestEnvironmentDefinition<?>>> data(Holder<TestEnvironmentDefinition<?>> batch, Identifier template, GameTest t) {
        // 26.3 adds the test's dimension (the Overworld, as before).
        *///?}
        //? if >=26.3 {
        /*return new TestData<>(batch, net.minecraft.world.level.Level.OVERWORLD, template, t.timeoutTicks(), (int) t.setupTicks(), t.required(),
                Rotation.NONE, false, 1, 1, t.skyAccess(), 0);
        *///?} else if >=26.1 {
        /*return new TestData<>(batch, template, t.timeoutTicks(), (int) t.setupTicks(), t.required(), Rotation.NONE, false, 1, 1, t.skyAccess(), 0);
        *///?}
        //? if >=26.1 {
        /*
    }

    private static Consumer<GameTestHelper> run(Method method) {
        return helper -> {
            try {
                method.invoke(null, new Shifted(info(helper)));
            } catch (InvocationTargetException ex) {
                if (ex.getCause() instanceof RuntimeException r) throw r;
                if (ex.getCause() instanceof Error r) throw r;
                throw new IllegalStateException(ex.getCause());
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        };
    }

    // The helper's test (a public field on NeoForge, private in vanilla, which Fabric runs).
    private static GameTestInfo info(GameTestHelper helper) throws IllegalAccessException {
        return (GameTestInfo) ua.zentix.almighty.platform.Reflect.field(GameTestHelper.class, "testInfo").get(helper);
    }

    // A helper whose relative origin is the block under the template, as 1.21.1's structure block.
    private static final class Shifted extends GameTestHelper {
        private final GameTestInfo testInfo;

        Shifted(GameTestInfo info) {
            super(info);
            this.testInfo = info;
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
