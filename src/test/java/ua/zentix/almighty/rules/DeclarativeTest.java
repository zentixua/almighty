package ua.zentix.almighty.rules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.RpcException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 11: declarative rules — conditions on event fields, actions, and no command injection through field values. */
class DeclarativeTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void conditions() throws Exception {
        JsonObject fields = json("{\"player\":{\"name\":\"Steve\",\"pos\":[10,64,-3]},\"state\":\"minecraft:diamond_ore\",\"tags\":[\"a\",\"b\"]}");
        assertTrue(Declarative.matches(json("{\"player.name\":\"Steve\",\"player.pos.1\":64}"), fields));
        assertFalse(Declarative.matches(json("{\"player.name\":\"Alex\"}"), fields));
        assertTrue(Declarative.matches(json("{\"player.pos.0\":{\"gt\":5},\"player.pos.2\":{\"lt\":0}}"), fields));
        assertTrue(Declarative.matches(json("{\"state\":{\"in\":[\"minecraft:diamond_ore\",\"minecraft:gold_ore\"]}}"), fields));
        assertTrue(Declarative.matches(json("{\"state\":{\"contains\":\"diamond\"},\"tags\":{\"contains\":\"b\"}}"), fields));
        assertTrue(Declarative.matches(json("{\"missing\":{\"exists\":false},\"state\":{\"ne\":\"x\"}}"), fields));
    }

    @Test
    void parseAndFill() throws Exception {
        assertNull(Declarative.parse(null, null), "no when/then: the old feed rule");
        Declarative.Decl d = Declarative.parse(json("{\"player.name\":\"Steve\"}"),
                JsonParser.parseString("[\"cancel\", {\"say\":\"no, {player.name}\", \"to\":\"{player.name}\"}, {\"command\":\"tp {player.name} 0 64 0\"}]"));
        assertEquals(3, d.then().size());
        assertTrue(d.cancels() && d.commands());
        Declarative.Creator steve = new Declarative.Creator("gm", java.util.UUID.randomUUID(), "Steve");
        assertTrue(d.saysToAll(steve), "a templated name may be anyone: speaking to all");
        assertTrue(!Declarative.speaksToAll("steve", "chat", steve) && Declarative.speaksToAll("steve", "title", steve), "only a chat line to oneself is a reply");
        assertThrows(RpcException.class, () -> Declarative.parse(json("{\"a\":{\"like\":1}}"), null));
        assertThrows(RpcException.class, () -> Declarative.parse(null, JsonParser.parseString("[\"explode\"]")));
        JsonObject fields = json("{\"player\":{\"name\":\"Steve\"},\"message\":\"x\\\"; op @a\"}");
        assertEquals("tp Steve 0 64 0", Declarative.fill("tp {player.name} 0 64 0", fields, true));
        assertThrows(Declarative.Unsafe.class, () -> Declarative.fill("say {message}", fields, true), "chat text never reaches a command");
        assertEquals("you said x\"; op @a", Declarative.fill("you said {message}", fields, false), "plain text in say is fine");
    }
}
