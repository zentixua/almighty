package ua.zentix.almighty.policy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 10: policy edits by asking the agent — restricting applies, granting waits for the owner, history and undo. */
class PolicyFilesTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final Caller gm = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "127.0.0.1");
    private final AtomicReference<String> asked = new AtomicReference<>();

    private ConfigFiles files(Path dir, String toml) throws Exception {
        Files.writeString(dir.resolve("policy.toml"), toml);
        Policies p = new Policies(dir.resolve("policy.toml"));
        assertNull(p.reload());
        Gate gate = new Gate(p, new Tickets(System::currentTimeMillis), (id, node) -> false, name -> false);
        return new ConfigFiles(dir, p, gate, (a, grants) -> {
            asked.set(grants);
            return new RpcException(202, "pending", "waiting: " + grants);
        });
    }

    private JsonObject write(ConfigFiles f, Caller as, String text) throws RpcException {
        JsonObject p = new JsonObject();
        p.addProperty("action", "write");
        p.addProperty("path", "policy.toml");
        p.addProperty("text", text);
        return f.call(new Args(p, as));
    }

    private static String friends(String extra) {
        return "preset = \"friends\"\nowners = [\"" + OWNER + "\"]\n" + extra;
    }

    @Test
    void grantsAndHostLevel() throws Exception {
        Policy friends = Policy.parse(friends(""));
        assertNull(PolicyEdits.grants(friends, Policy.parse("preset = \"public\"\nowners = [\"" + OWNER + "\"]")), "public restricts");
        assertNotNull(PolicyEdits.grants(friends, Policy.parse("preset = \"private\"\nowners = [\"" + OWNER + "\"]")), "private grants");
        assertNotNull(PolicyEdits.grants(friends, Policy.parse(friends("").replace(OWNER + "\"]", OWNER + "\", \"" + UUID.randomUUID() + "\"]"))), "a new owner");
        assertNotNull(PolicyEdits.grants(Policy.parse(friends("").replace(OWNER + "\"]", OWNER + "\", \"" + UUID.randomUUID() + "\"]")), friends),
                "removing an owner is never the agent's own decision");
        assertNull(PolicyEdits.grants(friends, Policy.parse(friends("[zones.spawn]\nfrom = [0,0,0]\nto = [9,9,9]\ndeny = [\"build\"]"))), "a deny zone restricts");
        assertNotNull(PolicyEdits.grants(Policy.parse(friends("[zones.spawn]\nfrom = [0,0,0]\nto = [9,9,9]\ndeny = [\"build\"]")), friends), "dropping it grants");
        assertNotNull(PolicyEdits.grants(friends, Policy.parse(friends("[zones.arena]\nfrom = [0,0,0]\nto = [9,9,9]\nagent_may = [\"build\"]"))), "an arena grants");
        assertNotNull(PolicyEdits.grants(friends, Policy.parse(friends("[quotas]\nbuild_blocks = 999999"))), "larger quotas grant");
        assertNull(PolicyEdits.grants(friends, Policy.parse(friends("[quotas]\nbuild_blocks = 10"))), "smaller quotas restrict");
        assertNotNull(PolicyEdits.grants(Policy.parse("preset = \"private\"\nowners = [\"" + OWNER + "\"]"), friends),
                "leaving private lets players reach the agents");
        assertNotNull(PolicyEdits.grants(friends, Policy.parse(friends("identity = \"proxy\"\n"))), "auto → proxy may verify more people");
        assertNull(PolicyEdits.grants(Policy.parse(friends("identity = \"proxy\"\n")), friends), "back to auto");
        assertNotNull(PolicyEdits.hostLevel(friends, Policy.parse(friends("[agents.mod]\nceiling = \"owner\""))), "owner ceiling: console only");
    }

    @Test
    void agentRestrictsAtOnceGrantsWaitAndUndoWalksBack(@TempDir Path dir) throws Exception {
        ConfigFiles f = files(dir, friends(""));
        JsonObject done = write(f, gm, friends("[quotas]\nbuild_blocks = 10"));
        assertEquals("applied (it only restricts)", done.get("how").getAsString());
        assertTrue(Files.readString(dir.resolve("policy.toml")).contains("build_blocks = 10"));
        RpcException wait = assertThrows(RpcException.class, () -> write(f, gm, friends("[quotas]\nbuild_blocks = 999999")));
        assertEquals(202, wait.status());
        assertEquals("larger quotas", asked.get());
        assertTrue(Files.readString(dir.resolve("policy.toml")).contains("build_blocks = 10"), "nothing applied while waiting");
        assertEquals(403, assertThrows(RpcException.class, () -> write(f, gm, friends("[agents.mod]\nceiling = \"owner\""))).status(),
                "host-level changes only at the console");
        assertEquals(400, assertThrows(RpcException.class, () -> write(f, gm, "preset = \"nope\"")).status(), "invalid: the old file stays");
        // The console (an internal caller) is the owner: applied at once; then undo walks back one change at a time.
        Caller console = new Caller(null, "console", null);
        write(f, console, friends("[quotas]\nbuild_blocks = 20"));
        assertNull(f.undoByOwner());
        assertTrue(Files.readString(dir.resolve("policy.toml")).contains("build_blocks = 10"));
        assertNull(f.undoByOwner());
        assertTrue(!Files.readString(dir.resolve("policy.toml")).contains("build_blocks"), "back to the start");
        assertNotNull(f.undoByOwner(), "nothing earlier");
    }

    @Test
    void onPrivateTheAgentUnaskedIsNotTheOwner(@TempDir Path dir) throws Exception {
        // Private: the agent's floor is its ceiling (owner for gm), but its own initiative still gets the friction.
        String priv = "preset = \"private\"\nowners = [\"" + OWNER + "\"]\n";
        ConfigFiles f = files(dir, priv);
        RpcException wait = assertThrows(RpcException.class, () -> write(f, gm, priv.replace(OWNER.toString(), OWNER + "\", \"" + UUID.randomUUID())));
        assertEquals(202, wait.status(), "a new owner, unasked: waits for the owner");
        assertEquals(202, assertThrows(RpcException.class, () -> write(f, gm, priv + "identity = \"offline\"")).status());
        assertEquals("applied (it only restricts)", write(f, gm, priv + "[quotas]\nbuild_blocks = 5").get("how").getAsString());
    }

    @Test
    void anAgentsUndoIsBoundToTheTextItRestores(@TempDir Path dir) throws Exception {
        ConfigFiles f = files(dir, friends("[quotas]\nbuild_blocks = 999"));
        write(f, gm, friends("[quotas]\nbuild_blocks = 10"));
        JsonObject undo = new JsonObject();
        undo.addProperty("action", "undo");
        // Undoing back to the larger quota grants: it waits, and what waits is the exact text.
        assertEquals(202, assertThrows(RpcException.class, () -> f.call(new Args(undo, gm))).status());
        assertTrue(Files.readString(dir.resolve("policy.toml")).contains("build_blocks = 10"));
        assertEquals(1, Files.list(dir.resolve("policy.history")).filter(x -> x.toString().endsWith(".toml")).count(), "nothing consumed");
    }

    @Test
    void lastOwnerAndScripts(@TempDir Path dir) throws Exception {
        ConfigFiles f = files(dir, friends(""));
        Caller console = new Caller(null, "console", null);
        assertEquals(409, assertThrows(RpcException.class, () -> write(f, console, "preset = \"friends\"")).status(), "last owner");
        Caller forPlayer = gm.withRequester(UUID.randomUUID());
        assertEquals(403, assertThrows(RpcException.class, () -> write(f, forPlayer, friends("[quotas]\nbuild_blocks = 1"))).status(),
                "a player's request never changes the policy");
        JsonObject s = new JsonObject();
        s.addProperty("action", "write");
        s.addProperty("path", "scripts/hello.groovy");
        s.addProperty("text", "return 1");
        assertEquals(403, assertThrows(RpcException.class, () -> f.call(new Args(s, gm))).status(), "the agent never writes scripts");
        s.addProperty("path", "../token");
        assertEquals(400, assertThrows(RpcException.class, () -> f.call(new Args(s, console))).status(), "only Almighty's own files");
    }

    @Test
    void approvedScriptsRunOnlyWhileUnchanged(@TempDir Path dir) throws Exception {
        ConfigFiles f = files(dir, friends(""));
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("scripts/hello.groovy"), "return 1");
        assertEquals(403, assertThrows(RpcException.class, () -> f.approvedScript("hello")).status(), "not approved yet");
        assertNull(f.approve("hello", true));
        assertEquals("return 1", f.approvedScript("hello"));
        Files.writeString(dir.resolve("scripts/hello.groovy"), "return 2");
        assertTrue(assertThrows(RpcException.class, () -> f.approvedScript("hello")).getMessage().contains("changed"), "an edit needs a new approval");
        assertNull(f.approve("hello", false));
        assertNotNull(f.approve("nope", true));
        assertEquals(400, assertThrows(RpcException.class, () -> f.approvedScript("../token")).status());
    }
}
