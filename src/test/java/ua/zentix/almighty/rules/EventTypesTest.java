package ua.zentix.almighty.rules;

import net.neoforged.neoforgespi.language.ModFileScanData;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Список событий по данным сканирования: класс без предка ({@code module-info} в jar мода) не роняет его сборку. */
class EventTypesTest {
    private static ModFileScanData.ClassData data(String name, String parent) {
        return new ModFileScanData.ClassData(Type.getObjectType(name), parent == null ? null : Type.getObjectType(parent), Set.of());
    }

    @Test
    void classesWithoutParentAreSkipped() {
        Map<String, String> parent = new HashMap<>(), mod = new HashMap<>();
        EventTypes.collect(List.of(
                data("module-info", null),
                data("a/MyEvent", "net/neoforged/bus/api/Event"),
                data("a/MyEvent$Inner", "a/MyEvent"),
                data("a/Plain", "java/lang/Object")), "mymod", parent, mod);
        assertEquals(Map.of("a.MyEvent", "mymod", "a.MyEvent$Inner", "mymod"), EventTypes.events(parent, mod));
    }
}
