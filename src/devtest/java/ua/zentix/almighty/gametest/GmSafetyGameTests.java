package ua.zentix.almighty.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;

import java.util.concurrent.CompletableFuture;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.params;

/**
 * Server protection that holds for every harness (plan step 2): the deny list at the choke point and for commands that
 * scripts issue, the script accident guard, and world calls from other threads running under the tick budget.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmSafetyGameTests {
    private GmSafetyGameTests() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
    }

    private static RpcException refused(GmServer gm, String method, JsonObject params) {
        try {
            gm.dispatch().invoke(Caller.INTERNAL, method, params);
        } catch (RpcException e) {
            return e;
        }
        throw new GameTestAssertException(method + " was not refused: " + params);
    }

    /** {@code stop}, {@code save-off}, agent management and plot builds are refused before anything runs. */
    @GameTest(template = "floor", batch = "gm_safety")
    public static void denyListAtTheChokePoint(GameTestHelper h) {
        GmServer gm = gm(h);
        for (String c : new String[]{"stop", "save-off", "execute as @a run stop", "almighty revoke gm", "tick rate 500"}) {
            RpcException e = refused(gm, "command", params("{\"commands\":[\"say before\",\"%s\"]}", c));
            check(e.status() == 403 && e.code().equals("denied"), c + ": " + e.code());
        }
        check(!h.getLevel().noSave, "save-off did not run");
        check(refused(gm, "build", params("{\"ops\":[{\"op\":\"set\",\"pos\":[20480000,64,0],\"block\":\"stone\"}]}")).status() == 403, "plot build");
        check(refused(gm, "script", params("{\"code\":\"Thread.sleep(50)\"}")).status() == 403, "sleep script");
        h.succeed();
    }

    /** A script's own {@code gm.command} meets the same list at run time; the accident guard refuses at compile time. */
    @GameTest(template = "floor", batch = "gm_safety")
    public static void scriptsMeetTheDenyList(GameTestHelper h) {
        GmServer gm = gm(h);
        // Built at run time, so the choke point's source check cannot see it: only the run-time check can refuse it.
        JsonObject r = GmGameTests.now(call(gm, "script", params("{\"code\":\"gm.command(['save', 'off'].join('-'))\"}")))
                .getAsJsonObject();
        check(!r.get("ok").getAsBoolean() && r.get("error").getAsString().contains("denied"), "run-time refusal: " + r);
        check(!h.getLevel().noSave, "save-off did not run");
        // The accident guard inside the compiler covers scripts that reach it without the bridge (rules loaded from a file).
        try {
            ua.zentix.almighty.script.Scripts.compile("guard", "level.getChunk(0, 0)", 100).close();
            throw new GameTestAssertException("getChunk compiled");
        } catch (ua.zentix.almighty.script.Scripts.CompileError e) {
            check(e.getMessage().contains("denied"), e.getMessage());
        }
        h.succeed();
    }

    /** A world call from a bridge thread is queued and runs on the server thread at tick end. */
    @GameTest(template = "floor", batch = "gm_safety", timeoutTicks = 40)
    public static void worldCallsRunAtTickEnd(GameTestHelper h) {
        GmServer gm = gm(h);
        // Queued from another thread (waited for here: a pool thread's start-up is wall-clock time, GameTest ticks are not).
        CompletableFuture<Boolean> onServer = CompletableFuture.supplyAsync(() -> gm.onMain(() -> gm.server().isSameThread())).join();
        check(!onServer.isDone(), "a world call from another thread ran before the tick end");
        h.succeedWhen(() -> check(onServer.isDone() && onServer.join(), "world call has not run on the server thread yet"));
    }

    private static CompletableFuture<JsonElement> call(GmServer gm, String method, JsonObject params) {
        try {
            return gm.dispatch().invoke(Caller.INTERNAL, method, params);
        } catch (RpcException e) {
            throw new GameTestAssertException(method + ": " + e.getMessage());
        }
    }
}
