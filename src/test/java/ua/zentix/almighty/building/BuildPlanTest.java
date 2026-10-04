package ua.zentix.almighty.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.RpcException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** План постройки без мира: блок — строка как есть. */
class BuildPlanTest {
    private static BuildPlan<String> plan(String json) throws RpcException {
        return plan(json, 1_000_000);
    }

    private static BuildPlan<String> plan(String json, long max) throws RpcException {
        JsonArray ops = JsonParser.parseString(json).getAsJsonArray();
        return BuildPlan.parse(ops, s -> {
            if (s.contains("bad")) throw RpcException.badRequest("нет блока " + s);
            return s;
        }, -64, 319, max);
    }

    /** Все места: "x,y,z" → блок (+ "!" у keep). */
    private static Map<String, String> drain(BuildPlan<String> plan, List<String> order) {
        Map<String, String> out = new HashMap<>();
        BuildPlan.Placement<String> p = new BuildPlan.Placement<>();
        while (plan.next(p)) {
            String key = p.x + "," + p.y + "," + p.z;
            order.add(key);
            out.put(key, p.block + (p.keep ? "!" : ""));
        }
        return out;
    }

    @Test
    void fillOrderBottomUpNorthToSouthWestToEast() throws Exception {
        BuildPlan<String> plan = plan("[{\"op\":\"fill\",\"from\":[1,5,1],\"to\":[0,4,0],\"block\":\"stone\"}]");
        assertEquals(8, plan.size());
        assertArrayEquals(new int[] {0, 4, 0, 1, 5, 1}, plan.bounds());
        List<String> order = new ArrayList<>();
        drain(plan, order);
        assertEquals(List.of("0,4,0", "1,4,0", "0,4,1", "1,4,1", "0,5,0", "1,5,0", "0,5,1", "1,5,1"), order);
    }

    @Test
    void hollowOutlineKeep() throws Exception {
        List<String> order = new ArrayList<>();
        Map<String, String> hollow = drain(plan("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[4,4,4],\"block\":\"glass\",\"mode\":\"hollow\"}]"), order);
        assertEquals(125, hollow.size());
        assertEquals("minecraft:air", hollow.get("2,2,2"));
        assertEquals("glass", hollow.get("0,2,2"));
        assertEquals(27, hollow.values().stream().filter("minecraft:air"::equals).count());

        BuildPlan<String> outline = plan("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[4,4,4],\"block\":\"glass\",\"mode\":\"outline\"}]");
        assertEquals(98, outline.size());
        Map<String, String> shell = drain(outline, new ArrayList<>());
        assertEquals(98, shell.size(), "каждое место оболочки — один раз");
        assertFalse(shell.containsKey("2,2,2"));
        assertFalse(shell.containsKey("1,1,1"));
        assertTrue(shell.containsKey("4,2,2") && shell.containsKey("2,4,2") && shell.containsKey("2,2,0"));

        Map<String, String> keep = drain(plan("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[1,0,0],\"block\":\"dirt\",\"mode\":\"keep\"}]"), new ArrayList<>());
        assertEquals("dirt!", keep.get("1,0,0"));
    }

    @Test
    void thinOutlineHasNoInterior() throws Exception {
        // стенка в один блок толщиной — вся оболочка
        assertEquals(15, plan("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[4,2,0],\"block\":\"s\",\"mode\":\"outline\"}]").size());
        assertEquals(15, drain(plan("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[4,2,0],\"block\":\"s\",\"mode\":\"outline\"}]"), new ArrayList<>()).size());
        assertEquals(3, drain(plan("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[0,2,0],\"block\":\"s\",\"mode\":\"outline\"}]"), new ArrayList<>()).size());
    }

    @Test
    void layersSkipUnknownSymbols() throws Exception {
        String json = """
                [{"op":"layers","origin":[10,0,20],"palette":{"#":"stone","d":"dirt"},
                  "layers":[["#.#", "d d"], {"y": 99, "rows": ["", "..#"]}]}]""";
        BuildPlan<String> plan = plan(json);
        assertEquals(5, plan.size());
        Map<String, String> m = drain(plan, new ArrayList<>());
        assertEquals("stone", m.get("10,0,20"));
        assertEquals("stone", m.get("12,0,20"));
        assertEquals("dirt", m.get("10,0,21"));
        assertEquals("dirt", m.get("12,0,21"));
        assertEquals("stone", m.get("12,1,21"), "второй слой — на блок выше, его y из blocks не важен");
        assertFalse(m.containsKey("11,0,20"));
        assertArrayEquals(new int[] {10, 0, 20, 12, 1, 21}, plan.bounds());
    }

    @Test
    void setAndSeveralOps() throws Exception {
        BuildPlan<String> plan = plan("[{\"op\":\"set\",\"pos\":[3,4,5],\"block\":\"a\"},{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[0,0,1],\"block\":\"b\"}]");
        List<String> order = new ArrayList<>();
        Map<String, String> m = drain(plan, order);
        assertEquals(List.of("3,4,5", "0,0,0", "0,0,1"), order);
        assertEquals("a", m.get("3,4,5"));
        assertArrayEquals(new int[] {0, 0, 0, 3, 4, 5}, plan.bounds());
    }

    @Test
    void errorsNameTheOp() {
        RpcException e = assertThrows(RpcException.class, () -> plan("[{\"op\":\"set\",\"pos\":[0,0,0],\"block\":\"a\"},{\"op\":\"set\",\"pos\":[0,0,0],\"block\":\"bad\"}]"));
        assertTrue(e.getMessage().startsWith("ops[1]"), e.getMessage());
        assertThrows(RpcException.class, () -> plan("[{\"op\":\"spiral\"}]"));
        assertThrows(RpcException.class, () -> plan("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"a\",\"mode\":\"weird\"}]"));
        assertThrows(RpcException.class, () -> plan("[{\"op\":\"set\",\"pos\":[0,320,0],\"block\":\"a\"}]"), "выше мира");
        assertThrows(RpcException.class, () -> plan("[{\"op\":\"set\",\"pos\":[0.5,3,0],\"block\":\"a\"}]"), "дробные координаты");
        assertThrows(RpcException.class, () -> plan("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[9,9,9],\"block\":\"a\"}]", 999), "больше предела");
        assertThrows(RpcException.class, () -> plan("[]"));
        assertThrows(RpcException.class, () -> plan("[{\"op\":\"layers\",\"origin\":[0,0,0],\"palette\":{\"ab\":\"x\"},\"layers\":[]}]"));
    }
}
