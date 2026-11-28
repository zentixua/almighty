package ua.zentix.almighty.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Agents;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.policy.Confirmations;
import ua.zentix.almighty.policy.Nodes;
import ua.zentix.almighty.policy.Policy;

import java.util.Map;
import java.util.UUID;

import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.params;
import static ua.zentix.almighty.gametest.GmGameTests.player;

/**
 * Origins and confirmations (plan step 6): a request's command runs as the requesting player at their level; an
 * irreversible request waits until that player confirms the exact call; a request above the player's rights reaches
 * an admin only if its kind is in ask_admin, and then runs with the deciding admin's rights.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmConfirmGameTests {
    private GmConfirmGameTests() {}

    private static final Caller GM = new Caller(Agents.of(Map.of("gm", "g".repeat(32))).get("gm"), "test");

    private static void check(boolean ok, String what) {
        if (!ok) throw new GameTestAssertException(what);
    }

    private static String ticket(GameTestHelper h, GmServer gm, ServerPlayer p, String text) throws InterruptedException {
        long after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack(), "gm " + text);
        for (JsonElement e : gm.feed().read(after, 100, 0).getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("type").getAsString().equals("gm") && o.get("player").getAsString().equals(Mc.name(p.getGameProfile()))) {
                return o.get("ticket").getAsString();
            }
        }
        throw new GameTestAssertException("no /gm event");
    }

    /** Whether the player may run the whole command line (parsed to its end, to a node that executes). */
    private static boolean runs(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher, String cmd, ServerPlayer p) {
        var parse = dispatcher.parse(cmd, p.createCommandSourceStack());
        return parse.getExceptions().isEmpty() && !parse.getReader().canRead() && parse.getContext().getCommand() != null;
    }

    private static void op(GameTestHelper h, ServerPlayer p, int level) {
        Gt.op(h.getLevel().getServer(), p.getGameProfile(), level);
        Nodes.refresh(h.getLevel().getServer());
    }

    private static void deop(GameTestHelper h, ServerPlayer p) {
        Gt.deop(h.getLevel().getServer(), p.getGameProfile());
    }

    @GameTest(template = "floor", batch = "gm_confirm")
    public static void requestsRunAsTheRequester(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        ServerPlayer alex = player(h, "confirm-alex", UUID.randomUUID());
        try {
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\""));
            Nodes.refresh(h.getLevel().getServer());
            String t = ticket(h, gm, alex, "make it day");
            JsonArray r = gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"time set day\"],\"for\":\"%s\"}", t)).join().getAsJsonArray();
            check(!r.get(0).getAsJsonObject().get("ok").getAsBoolean(), "a member cannot set the time (vanilla needs level 2): " + r);
            op(h, alex, 2);
            JsonArray ok = gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"time set day\"],\"for\":\"%s\"}", t)).join().getAsJsonArray();
            check(ok.get(0).getAsJsonObject().get("ok").getAsBoolean(), "as a level-2 operator it runs: " + ok);
            // Unasked, the agent runs commands at its floor (member: level 0).
            JsonArray floor = gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"time set day\"]}")).join().getAsJsonArray();
            check(!floor.get(0).getAsJsonObject().get("ok").getAsBoolean(), "unasked at the member floor: " + floor);
            // Vanilla calls it unknown; the answer says it is a permission level (time needs 2), not a typo.
            check(floor.get(0).getAsJsonObject().has("needs_level") && floor.get(0).getAsJsonObject().get("needs_level").getAsInt() == 2,
                    "refusal names the level the command needs: " + floor);
            JsonArray typo = gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"tiem set day\"]}")).join().getAsJsonArray();
            check(!typo.get(0).getAsJsonObject().has("needs_level"), "a real typo gets no level hint: " + typo);
        } finally {
            deop(h, alex);
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(alex);
        }
        h.succeed();
    }

    /**
     * Lead's N1: at first setup the app approved with the plain command the sign-in page shows is the owner's own app
     * (ceiling owner), so players' /gm reaches it (the route takes only an owner-ceiling app); an agent policy.toml
     * already lists keeps its own ceiling; once an owner exists, the plain form keeps the default (admin).
     */
    @GameTest(template = "floor", batch = "gm_first_app")
    public static void theFirstAppIsTheOwners(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        java.nio.file.Path file = gm.policies().file();
        String saved = file != null && java.nio.file.Files.exists(file) ? java.nio.file.Files.readString(file) : null;
        try {
            if (file == null) throw new GameTestAssertException("no policy file in GameTest");
            java.nio.file.Files.writeString(file, "preset = \"friends\"\nidentity = \"proxy\"\n");
            check(gm.policies().reload() == null, "policy loads");
            check(gm.policies().current().owners().isEmpty(), "no owner yet");
            check(ua.zentix.almighty.mcp.signin.SignInCommand.firstApp(gm.policies().current()), "first setup: the plain approval gives owner");
            String said = ua.zentix.almighty.mcp.signin.SignInCommand.rights(gm, "claude-code", true, true);
            check(gm.policies().current().ceiling("claude-code") == ua.zentix.almighty.policy.Role.OWNER, "owner ceiling: " + said);
            check(said.contains("your rights") && said.contains("/almighty agent remove claude-code"), "the decision is shown: " + said);
            // Signed in again: the file lists it now, so nothing is rewritten and the approval command sees it as listed.
            check(ua.zentix.almighty.mcp.signin.OwnerApp.listed(file, "claude-code") != null, "listed after the grant");
            // A second plain approval from the console (still no owner listed) keeps the default: only the first app.
            check(!ua.zentix.almighty.mcp.signin.SignInCommand.firstApp(gm.policies().current()), "a second app got owner by default");
            // With an owner, the plain form keeps the policy's default ceiling.
            java.nio.file.Files.writeString(file, "preset = \"friends\"\nidentity = \"proxy\"\nowners = [\"" + UUID.randomUUID() + "\"]\n");
            check(gm.policies().reload() == null, "policy with an owner loads");
            check(!ua.zentix.almighty.mcp.signin.SignInCommand.firstApp(gm.policies().current()), "with an owner, no default owner app");
            said = ua.zentix.almighty.mcp.signin.SignInCommand.rights(gm, "codex", false, false);
            check(gm.policies().current().ceiling("codex") == ua.zentix.almighty.policy.Role.ADMIN, "default ceiling: " + said);
            check(said.contains("codex owner"), "names the owner form: " + said);
        } finally {
            if (saved != null) java.nio.file.Files.writeString(file, saved);
            else if (file != null) java.nio.file.Files.deleteIfExists(file);
            gm.policies().reload();
            gm.policies().set(before);
        }
        h.succeed();
    }

    /**
     * Owner setup in one step (lead, "setup must be simple"): while policy.toml lists no owner, the verified level-4
     * operator who approves a sign-in becomes the first owner; nobody below level 4, nobody unverified, and nobody once
     * an owner exists.
     */
    @GameTest(template = "floor", batch = "gm_first_owner")
    public static void aFirstOwnerInOneStep(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        java.nio.file.Path file = gm.policies().file();
        String saved = file != null && java.nio.file.Files.exists(file) ? java.nio.file.Files.readString(file) : null;
        ServerPlayer op = player(h, "first_owner", UUID.randomUUID()), other = player(h, "second_op", UUID.randomUUID());
        try {
            if (file == null) throw new GameTestAssertException("no policy file in GameTest");
            java.nio.file.Files.writeString(file, "preset = \"friends\"\nidentity = \"proxy\"\n");
            check(gm.policies().reload() == null, "policy loads");
            op(h, op, 3);
            check(!ua.zentix.almighty.mcp.signin.SignInCommand.ownerOrFirst(op.createCommandSourceStack()), "a level-3 operator becomes owner");
            op(h, op, 4);
            op(h, other, 4);
            // The file, not only the loaded policy: an owner written by hand but not reloaded, or a broken file, stays.
            java.nio.file.Files.writeString(file, "preset = \"friends\"\nidentity = \"proxy\"\nowners = [\"" + UUID.randomUUID() + "\"]\n");
            check(ua.zentix.almighty.mcp.signin.SignInCommand.claimFirstOwner(op.createCommandSourceStack()) != null, "an unreloaded owner was overwritten");
            java.nio.file.Files.writeString(file, "owners = [\"Artem\"]\n");
            check(ua.zentix.almighty.mcp.signin.SignInCommand.claimFirstOwner(op.createCommandSourceStack()) != null, "a broken file was overwritten");
            java.nio.file.Files.writeString(file, "preset = \"friends\"\nidentity = \"proxy\"\n");
            check(ua.zentix.almighty.mcp.signin.SignInCommand.ownerOrFirst(op.createCommandSourceStack()), "the first level-4 operator may approve");
            check(ua.zentix.almighty.mcp.signin.SignInCommand.claimFirstOwner(op.createCommandSourceStack()) == null, "became the first owner");
            check(gm.policies().current().owners().contains(op.getUUID()), "listed in policy.toml: " + gm.policies().current().owners());
            check(java.nio.file.Files.readString(file).contains(op.getUUID().toString()), "written to the file");
            // Now there is an owner: another level-4 operator is not one, and cannot become one this way.
            check(!ua.zentix.almighty.mcp.signin.SignInCommand.ownerOrFirst(other.createCommandSourceStack()), "a second operator became owner");
            check(ua.zentix.almighty.mcp.signin.SignInCommand.claimFirstOwner(other.createCommandSourceStack()) != null, "a second first owner");
            check(ua.zentix.almighty.mcp.signin.SignInCommand.ownerOrFirst(op.createCommandSourceStack()), "the owner still approves");
        } finally {
            deop(h, op);
            deop(h, other);
            if (saved != null) java.nio.file.Files.writeString(file, saved);
            gm.policies().reload();
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(op);
            h.getLevel().getServer().getPlayerList().remove(other);
        }
        h.succeed();
    }

    /** Lead's F10: [Undo] on a player's receipt runs on click, as [Allow] does (it used to be typed into the chat box). */
    @GameTest(template = "floor", batch = "gm_receipt_undo")
    public static void undoOnAReceiptRunsOnClick(GameTestHelper h) {
        String c = ua.zentix.almighty.policy.Prompts.receipt(null, ua.zentix.almighty.policy.Facts.of("build", new com.google.gson.JsonObject(), "Bob"), "build {}", 5L, "en_us").toString();
        check(c.contains("/almighty undo 5") && !c.toLowerCase(java.util.Locale.ROOT).contains("suggest"), "receipt click: " + c);
        h.succeed();
    }

    /**
     * Mission almighty-prompts: what a player reads in a question. The server's facts (what really runs, its target)
     * stand beside the agent's summary, which is quoted, marked as its words and carries no click; a forged summary is
     * not shown and the facts alone read as a sentence; the exact call is on hover, behind [Details] and, for Bedrock
     * players, written out; only Allow, Deny and Details run commands.
     */
    @GameTest(template = "floor", batch = "gm_prompt_words")
    public static void aQuestionShowsTheServersFactsBesideTheSummary(GameTestHelper h) throws Exception {
        Confirmations c = new Confirmations(System::currentTimeMillis);
        UUID alex = UUID.randomUUID();
        JsonObject p = params("{\"commands\": \"kill @a\"}");
        p.addProperty("summary", "give you a cake");
        Confirmations.Pending honest = c.ask(Confirmations.Kind.REQUESTER, GM, "command", p, alex, null, alex, "Alex");
        for (String lang : new String[]{"en_us", "ru_ru"}) {
            net.minecraft.network.chat.Component q = ua.zentix.almighty.policy.Prompts.question(honest, lang, false);
            // The server's own language table has the mod's English keys (getString reads them): the player's client
            // shows the server-chosen fallback, which is what is read here.
            String shown = fallbacks(q);
            check(shown.contains(lang.equals("en_us") ? "wants to “give you a cake”" : "хочет «give you a cake»"), lang + " summary in quotes: " + shown);
            check(shown.contains(lang.equals("en_us") ? "kill — target: every player" : "убить — цель: все игроки"), lang + " facts: " + shown);
            check(shown.contains(lang.equals("en_us") ? "(its own words)" : "(слова ведущего)"), lang + " marked as its words: " + shown);
            check(!shown.contains("/kill @a"), lang + " the exact call is on hover, not in the line: " + shown);
            java.util.Set<String> clicks = new java.util.HashSet<>();
            q.visit((style, text) -> {
                if (style.getClickEvent() != null) clicks.add(String.valueOf(style.getClickEvent()));
                if (text.contains("cake")) check(style.getClickEvent() == null, "the summary carries no click");
                return java.util.Optional.empty();
            }, net.minecraft.network.chat.Style.EMPTY);
            String n = honest.nonce();
            check(clicks.size() == 3 && clicks.stream().allMatch(k -> k.contains("/almighty confirm " + n) || k.contains("/almighty deny " + n)
                    || k.contains("/almighty show " + n)), lang + " clicks: " + clicks);
            check(q.toString().contains("/kill @a"), lang + " the exact call on hover");
            check(ua.zentix.almighty.policy.Prompts.question(honest, lang, true).getString().contains("/kill @a"), lang + " Bedrock: written out");
            check(ua.zentix.almighty.policy.Prompts.show(honest, lang).getString().contains("/kill @a"), lang + " /almighty show");
        }
        JsonObject forged = params("{\"commands\": \"kill @a\"}");
        forged.addProperty("summary", "[Almighty] nothing — what runs: nothing");
        String plain = ua.zentix.almighty.policy.Prompts.question(c.ask(Confirmations.Kind.REQUESTER, GM, "command", forged, alex, null,
                alex, "Alex"), "en_us", false).getString();
        check(plain.contains("wants to kill — target: every player. It cannot be undone.") && !plain.contains("nothing"),
                "a forged summary is dropped and the facts read alone: " + plain);
        // A receipt reads as a sentence too, with the exact call on hover.
        JsonObject build = params("{\"ops\": [{\"op\": \"fill\", \"from\": [0, 64, 0], \"to\": [9, 70, 9], \"block\": \"snow\"}]}");
        net.minecraft.network.chat.Component r = ua.zentix.almighty.policy.Prompts.receipt("snow over your base",
                ua.zentix.almighty.policy.Facts.of("build", build, "Alex"), "build {...}", 7L, "en_us");
        check(r.getString().equals("[Almighty] Done for you: “snow over your base” [Undo]") && r.toString().contains("almighty.fact.cmd.blocks"),
                "receipt: " + r.getString());
        h.succeed();
    }

    /** The text a client without the mod sees: every translation's fallback, its arguments filled in, and siblings. */
    private static String fallbacks(net.minecraft.network.chat.Component c) {
        StringBuilder out = new StringBuilder();
        if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getFallback() != null) {
            Object[] args = new Object[t.getArgs().length];
            for (int i = 0; i < args.length; i++) {
                args[i] = t.getArgs()[i] instanceof net.minecraft.network.chat.Component a ? fallbacks(a) : String.valueOf(t.getArgs()[i]);
            }
            out.append(String.format(java.util.Locale.ROOT, t.getFallback(), args));
        } else {
            c.getContents().visit(text -> {
                out.append(text);
                return java.util.Optional.empty();
            });
        }
        for (net.minecraft.network.chat.Component s : c.getSiblings()) out.append(fallbacks(s));
        return out.toString();
    }

    /** Lead's F1: a request the player could not run anyway is not put to them (it would fail after Allow). */
    @GameTest(template = "floor", batch = "gm_confirm_level")
    public static void aCommandAboveTheRequestersLevelIsNotAsked(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        ServerPlayer alex = player(h, "level_alex", UUID.randomUUID());
        try {
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\""));
            String t = ticket(h, gm, alex, "summon a zombie");
            try {
                gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"summon zombie ~ ~ ~\"],\"for\":\"%s\"}", t));
                throw new GameTestAssertException("a member's summon ran or was asked");
            } catch (RpcException e) {
                check(e.status() == 403 && e.details() != null && e.details().has("needs_level"), "refused with needs_level: " + e.status() + " " + e.getMessage());
            }
        } finally {
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(alex);
        }
        h.succeed();
    }

    /** Scenario P10: a teleport to a friend waits for the friend's yes, not the asker's. */
    @GameTest(template = "floor", batch = "gm_confirm_tp")
    public static void aTeleportToAFriendAsksTheFriend(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        ServerPlayer alex = player(h, "tp_alex", UUID.randomUUID()), bea = player(h, "tp_bea", UUID.randomUUID());
        try {
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\""));
            op(h, alex, 2);
            String t = ticket(h, gm, alex, "take me to bea");
            for (String cmd : new String[] {"tp tp_bea", "tp tp_alex tp_bea", "tp tp_bea @s"}) {
                try {
                    gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"%s\"],\"for\":\"%s\"}", cmd, t));
                    throw new GameTestAssertException("a teleport with a friend ran unasked: " + cmd);
                } catch (RpcException e) {
                    check(e.status() == 202, cmd + ": " + e.getMessage());
                    Confirmations.Pending p = gm.decisions().confirmations().get(e.details().get("id").getAsString());
                    check(bea.getUUID().equals(p.confirmer), cmd + ": the friend confirms, not " + p.confirmer);
                    String q = ua.zentix.almighty.policy.Prompts.question(p, "en_us").getString();
                    check(q.contains("(asked by tp_alex)") && (q.contains("teleport tp_alex to tp_bea") || q.contains("teleport tp_bea to tp_alex")),
                            "the friend sees who asked and who goes where: " + q);
                    check(!e2s(gm.decisions().decide(p.nonce(), alex, true)).startsWith("Allowed"), "the asker cannot answer for the friend");
                    gm.decisions().decide(p.nonce(), bea, false);
                }
            }
            // To a place: still the asker's own move (self only, at once).
            JsonArray own = gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"tp @s ~ ~ ~\"],\"for\":\"%s\"}", t)).join().getAsJsonArray();
            check(own.get(0).getAsJsonObject().get("ok").getAsBoolean(), "a teleport of oneself to a place runs: " + own);
        } finally {
            deop(h, alex);
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(alex);
            h.getLevel().getServer().getPlayerList().remove(bea);
        }
        h.succeed();
    }

    @GameTest(template = "floor", batch = "gm_confirm")
    public static void irreversibleWaitsForTheRequester(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        ServerPlayer alex = player(h, "confirm-bea", UUID.randomUUID());
        try {
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\""));
            op(h, alex, 2);
            String t = ticket(h, gm, alex, "kill the pigs");
            String id;
            try {
                gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"kill @e[type=pig]\"],\"for\":\"%s\"}", t));
                throw new GameTestAssertException("an irreversible request ran without confirmation");
            } catch (RpcException e) {
                check(e.status() == 202 && e.code().equals("pending"), "pending: " + e.status() + " " + e.getMessage());
                id = e.details().get("id").getAsString();
            }
            Confirmations.Pending p = gm.decisions().confirmations().get(id);
            check(!e2s(gm.decisions().decide(p.nonce(), null, true)).startsWith("Allowed"), "the console cannot answer for the requester");
            // Typed by the player as the prompt's [Allow] button does: /almighty is reachable below level 4 (the
            // sign-in subcommands share the node and must not put their level on it).
            var server = h.getLevel().getServer();
            var dispatcher = server.getCommands().getDispatcher();
            for (String cmd : new String[] {"almighty whoami", "almighty confirm " + p.nonce(), "almighty deny " + p.nonce()}) {
                check(runs(dispatcher, cmd, alex), "a level-2 player cannot type /" + cmd);
            }
            for (String cmd : new String[] {"almighty approve", "almighty connect", "almighty pause"}) {
                check(!runs(dispatcher, cmd, alex), "a level-2 player can run /" + cmd);
            }
            server.getCommands().performPrefixedCommand(alex.createCommandSourceStack(), "almighty confirm " + p.nonce());
            // A level-4 operator who is not an owner lists waiting sign-ins but approves none: approving mints an agent,
            // as `agent add` does (rights audit m8). (With no owners at all, the first one may: aFirstOwnerInOneStep.)
            gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\"\nowners = [\"" + UUID.randomUUID() + "\"]"));
            op(h, alex, 4);
            check(runs(dispatcher, "almighty approve", alex), "a level-4 operator cannot list waiting sign-ins");
            check(!runs(dispatcher, "almighty approve 1234 app", alex), "a level-4 operator who is not an owner can approve a sign-in");
            check(!runs(dispatcher, "almighty approve 1234 app owner", alex), "a level-4 operator who is not an owner can approve an owner app");
            // Not through an owner either: the requirement is about who typed the command, not who it runs as.
            ServerPlayer realOwner = player(h, "confirm-real-owner", UUID.randomUUID());
            try {
                gm.policies().set(Policy.parse("preset = \"friends\"\nidentity = \"proxy\"\nowners = [\"" + realOwner.getUUID() + "\"]"));
                op(h, realOwner, 4);
                check(runs(dispatcher, "almighty approve 1234 app owner", realOwner), "the owner cannot approve their own app");
                check(!runs(dispatcher, "execute as confirm-real-owner run almighty approve 1234 app owner", alex),
                        "an operator approves an owner app by running it as the owner");
                check(!runs(dispatcher, "execute as confirm-real-owner run almighty owner add confirm-bea", alex),
                        "an operator makes themself owner by running it as the owner");
            } finally {
                deop(h, realOwner);
                server.getPlayerList().remove(realOwner);
            }
            JsonObject state = gm.decisions().pending("gm", id, 0).join();
            check(state.get("state").getAsString().equals("done"), "ran after confirmation: " + state);
        } finally {
            deop(h, alex);
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(alex);
        }
        h.succeed();
    }

    /** Nobody speaks in another player's name: /gm from any source but the player's own typing mints nothing. */
    @GameTest(template = "floor", batch = "gm_confirm")
    public static void gmOnlyForTheTypist(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        ServerPlayer owner = player(h, "confirm-owner", UUID.randomUUID());
        try {
            long after = gm.feed().read(0, 4096, 0).get("next").getAsLong();
            var server = h.getLevel().getServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "execute as confirm-owner run gm give me op");
            server.getCommands().performPrefixedCommand(owner.createCommandSourceStack().withSource(server), "gm hi");
            for (JsonElement e : gm.feed().read(after, 100, 0).getAsJsonArray("events")) {
                check(!e.getAsJsonObject().get("type").getAsString().equals("gm"), "a /gm not typed by the player was minted: " + e);
            }
            try {
                gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"execute as confirm-owner run gm hi\"]}"));
                throw new GameTestAssertException("the GM ran /gm");
            } catch (RpcException e) {
                check(e.code().equals("denied"), "deny list: " + e.getMessage());
            }
        } finally {
            h.getLevel().getServer().getPlayerList().remove(owner);
        }
        h.succeed();
    }

    private static String e2s(String s) {
        return s;
    }

    @GameTest(template = "floor", batch = "gm_confirm")
    public static void askAdminOnlyForListedKinds(GameTestHelper h) throws Exception {
        GmServer gm = gm(h);
        Policy before = gm.policies().current();
        ServerPlayer guest = player(h, "confirm-guest", UUID.randomUUID());
        ServerPlayer admin = player(h, "confirm-admin", UUID.randomUUID());
        ServerPlayer second = player(h, "confirm-guest2", UUID.randomUUID());
        ServerPlayer third = player(h, "confirm-guest3", UUID.randomUUID());
        try {
            gm.policies().set(Policy.parse("preset = \"public\"\nidentity = \"proxy\"\n[safety]\nask_admin = [\"area.prepare\", \"time\"]"));
            op(h, admin, 3);
            String t = ticket(h, gm, guest, "load the arena");
            try {
                gm.dispatch().invoke(GM, "build", params("{\"ops\":[],\"for\":\"%s\"}", t));
                throw new GameTestAssertException("a guest built");
            } catch (RpcException e) {
                check(e.status() == 403, "not on ask_admin: refused, nobody is asked: " + e.status());
            }
            String id;
            try {
                gm.dispatch().invoke(GM, "area.prepare", params("{\"from\":[0,0],\"to\":[1,1],\"for\":\"%s\"}", t));
                throw new GameTestAssertException("a guest prepared an area");
            } catch (RpcException e) {
                check(e.status() == 202, "on ask_admin: waits for an admin: " + e.status() + " " + e.getMessage());
                id = e.details().get("id").getAsString();
            }
            Confirmations.Pending p = gm.decisions().confirmations().get(id);
            check(gm.decisions().decide(p.nonce(), guest, true).startsWith("Not decided"), "the guest cannot approve themself");
            check(gm.decisions().decide(p.nonce(), admin, true).startsWith("Allowed"), "the admin approves");
            // A listed command the guest's own level cannot run waits for an admin, then runs as the guest with the
            // admin's level, for that one call.
            String id2;
            // Another guest: one player asks the admins at most once per 10 minutes.
            String t2 = ticket(h, gm, second, "set the time");
            try {
                gm.dispatch().invoke(GM, "command", params("{\"commands\":[\"time set 1234\"],\"for\":\"%s\"}", t2)).join();
                throw new GameTestAssertException("a guest set the time");
            } catch (java.util.concurrent.CompletionException e) {
                check(e.getCause() instanceof RpcException r && r.status() == 202, "time waits for an admin: " + e.getCause());
                id2 = ((RpcException) e.getCause()).details().get("id").getAsString();
            }
            Confirmations.Pending p2 = gm.decisions().confirmations().get(id2);
            check(gm.decisions().decide(p2.nonce(), admin, true).startsWith("Allowed"), "the admin approves the command");
            // The console denies a third one by typing /almighty deny <id> (the same node as sign-in codes' deny).
            String t3 = ticket(h, gm, third, "load the other arena");
            String id3;
            try {
                gm.dispatch().invoke(GM, "area.prepare", params("{\"from\":[64,64],\"to\":[65,65],\"for\":\"%s\"}", t3));
                throw new GameTestAssertException("a guest prepared an area");
            } catch (RpcException e) {
                check(e.status() == 202, "third request waits for an admin: " + e.status() + " " + e.getMessage());
                id3 = e.details().get("id").getAsString();
            }
            Confirmations.Pending p3 = gm.decisions().confirmations().get(id3);
            var server = h.getLevel().getServer();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "almighty deny " + p3.nonce());
            check(p3.describe().get("state").getAsString().equals("denied"), "the console's typed deny: " + p3.describe());
            // The agent's own elevation (plan 4.8 item 5): unasked, above its floor, a listed kind waits for an admin
            // too, at most once per 10 minutes per agent.
            try {
                gm.dispatch().invoke(GM, "area.prepare", params("{\"from\":[128,128],\"to\":[129,129]}"));
                throw new GameTestAssertException("a guest-floor agent prepared an area unasked");
            } catch (RpcException e) {
                check(e.status() == 202, "the agent's own ask waits for an admin: " + e.status() + " " + e.getMessage());
            }
            try {
                gm.dispatch().invoke(GM, "area.prepare", params("{\"from\":[192,192],\"to\":[193,193]}"));
                throw new GameTestAssertException("a second ask went through");
            } catch (RpcException e) {
                check(e.status() == 429, "one ask per 10 minutes per agent: " + e.status() + " " + e.getMessage());
            }
            h.succeedWhen(() -> {
                String state = p.describe().get("state").getAsString();
                check(state.equals("done"), "ran with the admin's rights: " + p.describe());
                check(p2.describe().get("state").getAsString().equals("done") && Mc.dayTime(h.getLevel()) % 24000 == 1234,
                        "time set with the admin's level: " + p2.describe() + " day time " + Mc.dayTime(h.getLevel()));
                gm.areas().releaseAll();
            });
        } finally {
            deop(h, admin);
            gm.policies().set(before);
            h.getLevel().getServer().getPlayerList().remove(guest);
            h.getLevel().getServer().getPlayerList().remove(admin);
            h.getLevel().getServer().getPlayerList().remove(second);
            h.getLevel().getServer().getPlayerList().remove(third);
        }
    }
}
