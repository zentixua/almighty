package ua.zentix.almighty.act;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.RpcException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Параметры макроса: целые остаются целыми, иначе {@code $(n)} подставит «5.0». */
class CommandRunnerTest {
    @Test
    void jsonArgsKeepIntegers() throws Exception {
        CompoundTag tag = CommandRunner.macroArgs(JsonParser.parseString("{\"n\":5,\"big\":10000000000,\"d\":1.5,\"s\":\"камень\",\"b\":true,\"l\":[1,2]}"));
        assertEquals(Tag.TAG_INT, tag.get("n").getId());
        assertEquals("5", tag.get("n").getAsString());
        assertEquals(Tag.TAG_LONG, tag.get("big").getId());
        assertEquals(Tag.TAG_DOUBLE, tag.get("d").getId());
        assertEquals("камень", tag.getString("s"));
        assertEquals(Tag.TAG_BYTE, tag.get("b").getId());
        assertEquals(Tag.TAG_LIST, tag.get("l").getId());
    }

    @Test
    void snbtAndErrors() throws Exception {
        assertEquals(3, CommandRunner.macroArgs(new JsonPrimitive("{x: 3}")).getInt("x"));
        assertThrows(RpcException.class, () -> CommandRunner.macroArgs(new JsonPrimitive("{x: ")));
        assertThrows(RpcException.class, () -> CommandRunner.macroArgs(JsonParser.parseString("[1]")));
        assertThrows(RpcException.class, () -> CommandRunner.macroArgs(JsonParser.parseString("{\"l\":[1,\"a\"]}")));
    }
}
