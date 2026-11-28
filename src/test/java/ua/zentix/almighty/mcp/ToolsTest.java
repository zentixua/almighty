package ua.zentix.almighty.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool calls become the same bridge calls the Python adapter made, with its checks: start/end become from/to,
 * arguments a tool does not take are refused instead of silently ignored, and exactly-one-of rules hold.
 */
class ToolsTest {
    static Tools.Call route(String tool, String args) throws Tools.ToolError {
        return Tools.route(tool, JsonParser.parseString(args).getAsJsonObject());
    }

    static void call(String tool, String args, String method, String params) throws Tools.ToolError {
        Tools.Call c = route(tool, args);
        assertEquals(method, c.method(), tool + " " + args);
        assertEquals(JsonParser.parseString(params), c.params(), tool + " " + args);
    }

    @Test
    void routesLikeTheAdapter() throws Tools.ToolError {
        call("status", "{}", "status", "{}");
        call("status", "{\"player\":\"Alex\"}", "player", "{\"name\":\"Alex\"}");
        call("entities", "{\"start\":[0,0,0],\"end\":[1,1,1]}", "entities", "{\"from\":[0,0,0],\"to\":[1,1,1]}");
        call("command", "{\"lines\":[\"$say $(x)\"],\"args\":{\"x\":1}}", "function", "{\"lines\":[\"$say $(x)\"],\"args\":{\"x\":1}}");
        call("rule", "{}", "rules", "{}");
        call("rule", "{\"action\":\"add\",\"event\":\"tick\",\"every\":20}", "rule.add", "{\"event\":\"tick\",\"every\":20}");
        call("rule", "{\"action\":\"types\",\"query\":\"Break\"}", "event.types", "{\"query\":\"Break\"}");
        call("view", "{\"kind\":\"map\",\"center\":[0,0],\"size\":64}", "map", "{\"center\":[0,0],\"size\":64}");
        call("view", "{\"kind\":\"map\",\"start\":[0,0],\"end\":[9,9],\"size\":64}", "map", "{\"from\":[0,0],\"to\":[9,9]}");
        call("view", "{\"kind\":\"look\",\"start\":[0,0,0],\"end\":[1,1,1],\"direction\":\"north\"}", "look", "{\"from\":[0,0,0],\"to\":[1,1,1],\"look\":\"north\"}");
        call("view", "{\"kind\":\"eye\",\"who\":\"Alex\"}", "see", "{\"name\":\"Alex\"}");
        call("view", "{\"kind\":\"eye\",\"who\":\"0a1b2c3d-0000-4000-8000-000000000001\"}", "see", "{\"uuid\":\"0a1b2c3d-0000-4000-8000-000000000001\"}");
        call("view", "{\"kind\":\"eye\",\"eye\":[0,64,0],\"yaw\":90}", "see", "{\"at\":[0,64,0],\"yaw\":90}");
        call("build", "{\"undo\":5}", "undo", "{\"job\":5}");
        call("area", "{\"action\":\"prepare\",\"start\":[0,0],\"end\":[100,100],\"wait\":10}", "area.prepare", "{\"from\":[0,0],\"to\":[100,100],\"wait\":10}");
        call("area", "{}", "areas", "{}");
        call("job", "{}", "jobs", "{}");
        call("job", "{\"id\":3,\"cancel\":true}", "cancel", "{\"id\":3}");
        call("bot", "{\"action\":\"state\",\"name\":\"B\",\"after\":4}", "bot", "{\"name\":\"B\",\"after\":4}");
        call("bot", "{\"action\":\"act\",\"name\":\"B\",\"actions\":[{\"wait\":1}]}", "bot.act", "{\"name\":\"B\",\"actions\":[{\"wait\":1}]}");
        call("notes", "{\"id\":\"world:x\",\"text\":\"t\"}", "notes", "{\"id\":\"world:x\",\"text\":\"t\"}");
        call("call", "{\"method\":\"ships\",\"params\":{\"name\":\"Grand\"}}", "ships", "{\"name\":\"Grand\"}");
    }

    @Test
    void refusesWhatTheAdapterRefused() {
        for (String[] bad : new String[][]{
                {"command", "{}"}, {"command", "{\"commands\":[\"a\"],\"lines\":[\"b\"]}"}, {"command", "{\"commands\":[\"a\"],\"args\":{}}"},
                {"status", "{\"name\":\"Alex\"}"}, {"rule", "{\"action\":\"add\"}"}, {"rule", "{\"action\":\"remove\"}"},
                {"rule", "{\"action\":\"list\",\"event\":\"tick\"}"}, {"view", "{\"kind\":\"map\"}"}, {"view", "{\"kind\":\"map\",\"center\":[0,0,0]}"},
                {"view", "{\"kind\":\"eye\",\"who\":\"A\",\"yaw\":1}"}, {"view", "{\"kind\":\"blocks\",\"start\":[0,0,0],\"end\":[1,1,1],\"who\":\"A\"}"},
                {"build", "{}"}, {"build", "{\"undo\":1,\"dimension\":\"x\"}"}, {"area", "{\"action\":\"release\"}"}, {"job", "{\"cancel\":true}"},
                {"job", "{\"id\":1,\"cancel\":true,\"wait\":3}"}, {"bot", "{\"action\":\"act\",\"name\":\"B\"}"}, {"bot", "{\"action\":\"list\",\"name\":\"B\"}"},
                {"bot", "{\"action\":\"fly\"}"}, {"notes", "{\"text\":\"t\"}"}, {"call", "{}"}}) {
            assertThrows(Tools.ToolError.class, () -> route(bad[0], bad[1]), bad[0] + " " + bad[1]);
        }
    }

    /** Plan 4.13: every tool takes the player's ticket, and it reaches the bridge call (so the gate acts as that player). */
    @Test
    void everyToolPassesThePlayersTicket() throws Tools.ToolError {
        call("say", "{\"text\":\"hi\",\"to\":[\"Alex\"],\"for\":\"t1\"}", "say", "{\"text\":\"hi\",\"to\":[\"Alex\"],\"for\":\"t1\"}");
        call("build", "{\"undo\":3,\"for\":\"t1\"}", "undo", "{\"job\":3,\"for\":\"t1\"}");
        call("command", "{\"commands\":[\"time set day\"],\"for\":\"t1\"}", "command", "{\"commands\":[\"time set day\"],\"for\":\"t1\"}");
        call("call", "{\"method\":\"give\",\"params\":{\"item\":\"bread\"},\"for\":\"t1\"}", "give", "{\"item\":\"bread\",\"for\":\"t1\"}");
        call("call", "{\"method\":\"give\",\"params\":{\"for\":\"t2\"},\"for\":\"t1\"}", "give", "{\"for\":\"t2\"}");
        assertThrows(Tools.ToolError.class, () -> route("say", "{\"text\":\"hi\",\"for\":5}"));
        for (Profile profile : Profile.values()) {
            for (var e : Tools.list(profile)) {
                JsonObject t = e.getAsJsonObject();
                assertTrue(t.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("for"), profile + " " + t.get("name") + " lacks for");
            }
        }
    }

    @Test
    void definitionsCoverTheFourteenToolsWithHints() {
        assertEquals(Set.of("status", "entities", "command", "say", "script", "rule", "events", "view", "build", "area", "job", "bot", "notes", "call"),
                Tools.names());
        for (var e : Tools.list(Profile.FULL)) {
            JsonObject t = e.getAsJsonObject();
            String d = t.get("description").getAsString();
            assertTrue(d.length() <= 2048, t.get("name") + " description " + d.length() + " chars (aim: under 2 KB)");
        }
    }
}
