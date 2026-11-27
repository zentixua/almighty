package ua.zentix.almighty.policy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.RpcException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** One case per deny-list entry (AGENTS.md "Test invariants"), plus the near misses that must stay allowed. */
class DenyListTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "stop", "/stop", "reload", "save-off", "save-all flush", "SAVE-ALL FLUSH", "forceload add 0 0",
            "tick rate 101", "tick rate 1000.5",
            "execute as @a run stop", "execute positioned 0 0 0 run forceload add 0 0",
            "tp @s 20000000 64 0", "teleport Bob 0 64 -20480000", "fill 20000000 0 0 20000010 5 10 stone",
            "clone 0 0 0 1 1 1 20480000 0 0", "setblock 0 0 20000001 stone", "place feature tree 20000000 70 0",
            "spreadplayers 20000000 0 1 10 false @a", "forceload query 20000000 0",
            "almighty revoke gm", "almighty agent add sneaky", "/almighty reload",
            "execute run almighty agent remove gm"})
    void deniedCommands(String command) {
        assertNotNull(DenyList.command(command), command);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "stopsound @a", "reloadx", "save-all", "save-on", "forceload remove 0 0", "forceload query 0 0",
            "tick rate 100", "tick rate 20", "tick freeze", "tp @s 19999999 64 0", "say stop the server",
            "give @s stone 20000000", "time set 20000000", "execute as @a run say hi", "gm hello"})
    void allowedCommands(String command) {
        assertNull(DenyList.command(command), command);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "TicketType.create('x', null)", "level.chunkSource.addRegionTicket(t, pos, 0, 0)", "x.addTicket(t)",
            "level.setChunkForced(0, 0, true)", "x.forceChunk(1)",
            "level.getChunk(0, 0)", "level.getChunkAt(pos)",
            "new Thread({ -> }).start()", "Thread.start { }", "Executors.newFixedThreadPool(2)", "new Timer()",
            "CompletableFuture.runAsync { }", "server.execute { }", "server.submit({ })", "pool.schedule(r)",
            "NeoForge.EVENT_BUS.post(e)", "bus.addListener(x)", "bus.register(this)",
            "Thread.sleep(1000)", "sleep(100)", "lock.wait()",
            "System.exit(0)", "Runtime.getRuntime().exec('ls')", "Runtime.getRuntime().halt(1)", "new ProcessBuilder('sh')"})
    void deniedScripts(String code) {
        assertNotNull(DenyList.script(code), code);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "level.getChunkNow(0, 0)", "gm.command('say hi')", "1 + 2", "player.sleeping", "state.registered = true",
            "list.collect { it * 2 }"})
    void allowedScripts(String code) {
        assertNull(DenyList.script(code), code);
    }

    @Test
    void bridgeCalls() {
        assertNotNull(DenyList.reason("command", json("{\"commands\":[\"say hi\",\"stop\"]}")), "any command in the list");
        assertNotNull(DenyList.reason("command", json("{\"commands\":\"save-off\"}")), "a single string");
        assertNotNull(DenyList.reason("function", json("{\"lines\":[\"$tick rate $(r)\"],\"args\":{\"r\":500}}")), "macro expanded");
        assertNull(DenyList.reason("function", json("{\"lines\":[\"$tick rate $(r)\"],\"args\":{\"r\":20}}")));
        assertNotNull(DenyList.reason("script", json("{\"code\":\"Thread.sleep(5)\"}")));
        assertNotNull(DenyList.reason("rule.add", json("{\"event\":\"x\",\"script\":\"level.getChunk(0,0)\"}")));
        assertNull(DenyList.reason("rule.add", json("{\"event\":\"x\"}")), "rule without a script");
        assertNotNull(DenyList.reason("build", json("{\"ops\":[{\"fill\":[[20480000,0,0],[20480001,1,1]],\"block\":\"stone\"}]}")));
        assertNull(DenyList.reason("build", json("{\"ops\":[{\"fill\":[[0,0,0],[1,1,1]],\"block\":\"stone\"}]}")));
        assertNotNull(DenyList.reason("area.prepare", json("{\"from\":[20000000,0],\"to\":[20000010,10]}")));
        assertNotNull(DenyList.reason("bot.spawn", json("{\"name\":\"b\",\"pos\":[0,64,-20000000]}")));
        assertNull(DenyList.reason("bot.spawn", json("{\"name\":\"b\",\"pos\":[0,64,0]}")));
        assertNotNull(DenyList.reason("bot.act", json("{\"name\":\"b\",\"actions\":[{\"chat\":\"/stop\"}]}")), "bot typing a command");
        assertNull(DenyList.reason("bot.act", json("{\"name\":\"b\",\"actions\":[{\"chat\":\"stop\"}]}")), "plain chat");
        assertNull(DenyList.reason("status", new JsonObject()));
        RpcException e = assertThrows(RpcException.class, () -> DenyList.check("command", json("{\"commands\":[\"stop\"]}")));
        assertEquals(403, e.status());
        assertEquals("denied", e.code());
    }
}
