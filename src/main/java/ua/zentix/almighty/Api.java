package ua.zentix.almighty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import ua.zentix.almighty.act.Chat;
import ua.zentix.almighty.act.CommandRunner;
import ua.zentix.almighty.act.CommandVolume;
import ua.zentix.almighty.bot.Bot;
import ua.zentix.almighty.bot.Bots;
import ua.zentix.almighty.bot.Eye;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.Method;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.building.BuildJob;
import ua.zentix.almighty.building.BuildPlan;
import ua.zentix.almighty.knowledge.Knowledge;
import ua.zentix.almighty.knowledge.Notes;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.rules.Rules;
import ua.zentix.almighty.script.Json;
import ua.zentix.almighty.script.ScriptApi;
import ua.zentix.almighty.script.Scripts;
import ua.zentix.almighty.view.BlockText;
import ua.zentix.almighty.view.EyeView;
import ua.zentix.almighty.view.LookView;
import ua.zentix.almighty.view.MapView;
import ua.zentix.almighty.view.View;
import ua.zentix.almighty.work.Job;
import ua.zentix.almighty.world.Areas;
import ua.zentix.almighty.world.Dims;
import ua.zentix.almighty.world.Observe;
import ua.zentix.almighty.world.PrepareJob;
import ua.zentix.almighty.world.Ships;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bridge methods. Parameters are parsed on the bridge thread; the world is read and changed on the server thread
 * ({@link GmServer#onMain}) or by a job within the tick budget. Methods with {@code wait} wait up to that many seconds
 * for job completion, then return its current description; continue with {@code job}.
 */
public final class Api {
    static final int MAX_WAIT = 50;
    /** How many ticks a build waits for its chunks to load: 2 minutes at 20 ticks per second. */
    static final long CHUNK_WAIT_TICKS = 2400;
    /** The longest one-shot script: the server thread is blocked for that duration. */
    static final int MAX_SCRIPT_MS = 10_000;
    /** Plan 4.11: the longest a script may run below owner. */
    static final int SCRIPT_MS_BELOW_OWNER = 50;
    /** The most areas one agent may hold prepared at once. */
    static final int MAX_AREAS_PER_AGENT = 16;
    private static final Logger LOG = LogUtils.getLogger();
    /** Methods that may wait for seconds (feed long poll, job waits): served by the bridge's long-poll pool. */
    public static final java.util.Set<String> LONG = java.util.Set.of(
            "events", "job", "build", "undo", "area.prepare", "map", "look", "see", "bot.act", "bot.spawn", "pending");

    /** Whether a call has no effects: idempotency keys are ignored for it. {@code notes} with {@code text} writes a note. */
    public static boolean readOnly(String method, JsonObject params) {
        return READ_ONLY.contains(method) || (method.equals("notes") && !params.has("text"))
                || (method.equals("file") && params.has("action") && params.get("action").isJsonPrimitive()
                && java.util.Set.of("list", "read").contains(params.get("action").getAsString()));
    }

    /** Methods without effects on the world, whatever their parameters. */
    public static final java.util.Set<String> READ_ONLY = java.util.Set.of(
            "status", "players", "player", "entities", "blocks", "map", "look", "see", "jobs", "job", "areas",
            "bots", "bot", "ships", "rules", "event.types", "events", "audit", "pending", "changes");

    private Api() {}

    public static Map<String, Method> methods(GmServer gm) {
        MinecraftServer server = gm.server();
        Map<String, Method> m = new LinkedHashMap<>();
        m.put("status", a -> {
            // The caller's rights (with for: what that player's request allows); computed here, not on the server thread.
            JsonObject rights = a.caller().agent() == null ? null : gm.gate().describe(a.caller(), a.raw());
            return gm.onMain(() -> {
                JsonObject s = Observe.status(gm).getAsJsonObject();
                if (rights != null) s.add("rights", rights);
                return s;
            });
        });
        // Provenance set by the server (plan 5.4): the requester of the call, and confirmed when the owner asked
        // (an in-process call, or a request whose player is an owner), the same origin rule as policy edits.
        m.put("notes", a -> {
            java.util.UUID requester = a.caller().requester();
            boolean confirmed = a.caller().agent() == null;
            if (!confirmed && requester != null) {
                ua.zentix.almighty.policy.Gate.Rights r = gm.gate().rights(a.caller(), a.raw());
                confirmed = r.ticket() != null && r.effective() == ua.zentix.almighty.policy.Role.OWNER;
            }
            return Knowledge.notes(gm, a, new Notes.Author(a.caller().name(), requester == null ? null : requester.toString(), confirmed));
        });
        m.put("players", a -> gm.onMain(() -> Observe.players(server)));
        m.put("player", a -> gm.onMain(() -> Observe.player(server, a)));
        m.put("entities", a -> gm.onMain(() -> Observe.entities(server, a)));
        m.put("command", a -> {
            ua.zentix.almighty.policy.Gate.Rights r = a.caller().agent() == null ? null : gm.gate().rights(a.caller(), a.raw());
            if (r != null) CommandVolume.check(ua.zentix.almighty.policy.Confirmations.commands("command", a.raw()), r.effective() == ua.zentix.almighty.policy.Role.OWNER);
            return gm.onMain(() -> {
                CommandRunner.As as = gm.commandAs(r);
                gm.commandZone(a, as, false);
                gm.askAdmin(a.caller(), r, "command", a.raw(), as);
                return CommandRunner.commands(server, a, as);
            });
        });
        m.put("function", a -> {
            if (a.caller().agent() != null && a.has("args") && !a.raw().get("args").isJsonObject()) {
                throw RpcException.badRequest("args: an object of macro values");
            }
            ua.zentix.almighty.policy.Gate.Rights r = a.caller().agent() == null ? null : gm.gate().rights(a.caller(), a.raw());
            if (r != null) CommandVolume.check(ua.zentix.almighty.policy.Confirmations.commands("function", a.raw()), r.effective() == ua.zentix.almighty.policy.Role.OWNER);
            return gm.onMain(() -> {
                CommandRunner.As as = gm.commandAs(r);
                gm.commandZone(a, as, true);
                gm.askAdmin(a.caller(), r, "function", a.raw(), as);
                return CommandRunner.function(server, a, as);
            });
        });
        m.put("give", a -> {
            // Items for the requester only (plan P9): a plain item id, no components, never #almighty:never_give.
            String item = a.string("item");
            int count = a.integer("count", 1, 1, 64);
            if (a.caller().agent() != null && a.caller().requester() == null) {
                throw RpcException.badRequest("give: only for the player who asked (for: their ticket)");
            }
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(item);
            if (id == null || item.contains("[") || item.contains("{")) throw RpcException.badRequest("item: a plain item id such as minecraft:bread (no components)");
            java.util.UUID to = a.caller().requester();
            boolean owner = a.caller().agent() == null
                    || gm.gate().rights(a.caller(), a.raw()).effective() == ua.zentix.almighty.policy.Role.OWNER;
            return gm.onMain(() -> {
                var holder = server.registryAccess().lookupOrThrow(Registries.ITEM).get(net.minecraft.resources.ResourceKey.create(Registries.ITEM, id))
                        .orElseThrow(() -> RpcException.notFound("No item " + item));
                if (!owner && holder.is(ua.zentix.almighty.policy.ContentLimits.NEVER_GIVE)) {
                    throw new RpcException(403, "policy", item + " is never given (#almighty:never_give)");
                }
                // Spawn eggs work in survival (a wither from one egg): never below owner, whatever mod adds them.
                if (!owner && holder.value() instanceof net.minecraft.world.item.SpawnEggItem) {
                    throw new RpcException(403, "policy", item + ": spawn eggs are given only by the owner");
                }
                int n = Math.min(count, holder.value().getDefaultMaxStackSize());
                if (!owner && to != null) gm.quotas().charge(to, "give.items", n, gm.policies().current().limits().giveItems());
                ServerPlayer p = to == null ? null : server.getPlayerList().getPlayer(to);
                if (p == null) throw RpcException.conflict("The player who asked is offline");
                net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(holder, n);
                if (!p.getInventory().add(stack)) ua.zentix.almighty.platform.Mc.drop(p, stack);
                JsonObject out = new JsonObject();
                out.addProperty("player", Mc.name(p.getGameProfile()));
                out.addProperty("item", id.toString());
                out.addProperty("count", n);
                return out;
            });
        });
        m.put("pending", a -> gm.decisions().pending(a.caller().name(), a.string("id"), a.integer("wait", 0, 0, MAX_WAIT))
                .thenApply(o -> (JsonElement) o));
        m.put("say", a -> gm.onMain(() -> Chat.say(server, a)));
        m.put("blocks", a -> gm.onMain(() -> BlockText.read(Dims.level(server, a), a.ints("from", 3), a.ints("to", 3), a.bool("properties", true))));
        m.put("map", a -> {
            // Player markers show where players are: observe.players (rights audit M4), else bots only.
            boolean players = seesPlayers(gm, a, "players on a map");
            return view(gm, a, () -> map(server, a).players(players ? p -> true : p -> gm.bots().of(p) != null));
        });
        m.put("look", a -> view(gm, a, () -> new LookView(Dims.level(server, a), a.ints("from", 3), a.ints("to", 3), LookView.Look.parse(a.string("look")))));
        m.put("build", a -> build(gm, a));
        m.put("file", a -> CompletableFuture.completedFuture(gm.files().call(a)));
        m.put("changes", a -> CompletableFuture.completedFuture(gm.journal().list(a.integer("limit", 20, 1, 200))));
        m.put("undo", a -> {
            // A journal change (any GM call or job: builds, commands, functions, scripts), conflict-aware.
            if (a.has("change")) return gm.revert(a.longValue("change", 0, 1, Long.MAX_VALUE), a.caller(), a.raw(), waitSeconds(a));
            long id = a.integer("job", 1, Integer.MAX_VALUE);
            return gm.submit(() -> {
                Job of = gm.jobs().get(id);
                if (!(of instanceof BuildJob build)) throw RpcException.notFound("No build #" + id + " (the history keeps the last 50 jobs)");
                // Own undo means the same agent and the same requester (or none); anyone else's needs undo.any.
                if (!of.agent().equals(a.caller().name()) || !java.util.Objects.equals(of.requester(), a.caller().requester())) {
                    gm.gate().require(a.caller(), a.raw(), ua.zentix.almighty.policy.Node.UNDO_ANY, "build #" + id + " was made for someone else");
                }
                // Undoing an undo replays the original build, data and all: below owner that would bypass the content limits.
                if (of.kind().equals("undo") && a.caller().agent() != null
                        && gm.gate().rights(a.caller(), a.raw()).effective() != ua.zentix.almighty.policy.Role.OWNER) {
                    throw new RpcException(403, "policy", "Undoing an undo (a redo) needs the owner");
                }
                return BuildJob.undo(build, CHUNK_WAIT_TICKS);
            }, waitSeconds(a));
        });
        m.put("jobs", a -> gm.onMain(() -> gm.jobs().describe()));
        m.put("job", a -> {
            long id = a.integer("id", 1, Integer.MAX_VALUE);
            int wait = a.integer("wait", 0, 0, MAX_WAIT);
            return gm.onMain(() -> {
                Job job = gm.jobs().get(id);
                if (job == null) throw RpcException.notFound("No job #" + id + " (the history keeps the last 50 jobs)");
                return job;
            }).thenCompose(job -> gm.await(job, wait).thenApplyAsync(result ->
                    job instanceof View view ? view.attach(result.getAsJsonObject()) : result));
        });
        m.put("cancel", a -> {
            long id = a.integer("id", 1, Integer.MAX_VALUE);
            return gm.onMain(() -> {
                Job job = gm.jobs().get(id);
                if (job == null) throw RpcException.notFound("No job #" + id);
                if (!job.agent().equals(a.caller().name())) gm.gate().require(a.caller(), a.raw(), ua.zentix.almighty.policy.Node.UNDO_ANY,
                        "job #" + id + " is agent " + job.agent() + "'s");
                if (!gm.jobs().cancel(id)) throw RpcException.conflict("Job #" + id + " has already finished: " + job.state().id());
                return job.describe();
            });
        });
        m.put("area.prepare", a -> {
            int[] from = a.ints("from", 2), to = a.ints("to", 2);
            int ttl = a.integer("ttl_seconds", 300, 1, 3600);
            int wait = a.integer("wait", 0, 0, MAX_WAIT);
            // The chunks it loads, full height: an agent_may zone must hold them, a deny zone must not meet them.
            net.minecraft.server.level.ServerLevel al = Dims.level(server, a);
            gm.gate().zone(a.caller(), a.raw(), ua.zentix.almighty.policy.Node.AREA, Dims.id(al),
                    new int[]{Math.min(from[0], to[0]) & ~15, Mc.minBuildHeight(al), Math.min(from[1], to[1]) & ~15},
                    new int[]{(Math.max(from[0], to[0]) & ~15) + 15, Mc.maxBuildHeight(al) - 1, (Math.max(from[1], to[1]) & ~15) + 15});
            return gm.onMain(() -> {
                // Stability audit M5: a loop of area.prepare piled up leases the tick walks.
                if (a.caller().agent() != null && gm.areas().preparedBy(a.caller().name()) >= MAX_AREAS_PER_AGENT) {
                    throw new RpcException(429, "areas", "Agent " + a.caller().name() + " holds " + MAX_AREAS_PER_AGENT
                            + " prepared areas: release some (area.release) or let them expire");
                }
                Areas.Lease lease = gm.areas().acquire(Dims.level(server, a), "area", from[0], from[1], to[0], to[1], ttl * 20L);
                lease.owner("area #" + lease.id());
                lease.agent(a.caller().name());
                return lease;
            }).thenCompose(lease -> wait == 0
                    ? gm.onMain(lease::describe)
                    : gm.submit(() -> new PrepareJob(lease), wait).thenCompose(r -> gm.onMain(lease::describe)));
        });
        m.put("area.release", a -> {
            long id = a.integer("id", 1, Integer.MAX_VALUE);
            return gm.onMain(() -> {
                Areas.Lease lease = gm.areas().get(id);
                if (lease == null) throw RpcException.notFound("No area #" + id + " (its time may have run out)");
                // Only areas made with area.prepare (rights audit M7): a build's or a bot's lease ends with them, and
                // releasing it under them would fail the build midway. Another agent's area needs undo.any.
                if (lease.agent() == null) throw RpcException.conflict("#" + id + " is held by " + lease.ownerName() + ": it ends with it (cancel the job)");
                if (!lease.agent().equals(a.caller().name())) {
                    gm.gate().require(a.caller(), a.raw(), ua.zentix.almighty.policy.Node.UNDO_ANY, "area #" + id + " prepared by " + lease.agent());
                }
                lease.release();
                JsonObject out = new JsonObject();
                out.addProperty("released", id);
                return out;
            });
        });
        m.put("areas", a -> gm.onMain(() -> gm.areas().describe()));
        m.put("bots", a -> gm.onMain(() -> gm.bots().describe()));
        m.put("bot", a -> {
            String name = a.string("name");
            long after = a.longValue("after", 0, 0, Long.MAX_VALUE);
            java.util.function.Predicate<java.util.UUID> heard = heardBy(gm, a.caller());
            return gm.onMain(() -> gm.bots().get(name).describe(after, heard));
        });
        m.put("bot.spawn", a -> {
            Bots.Spec spec = Bots.Spec.parse(a);
            if (spec.pos() == null && a.raw().has(ua.zentix.almighty.policy.Gate.ZONE_ONLY)) {
                throw new RpcException(403, "policy", "A bot spawned on the agent's own initiative needs pos inside its zone");
            }
            String dim = spec.dimension() == null ? "minecraft:overworld" : spec.dimension().contains(":") ? spec.dimension() : "minecraft:" + spec.dimension();
            int[] at;
            if (spec.pos() != null) {
                at = new int[]{(int) Math.floor(spec.pos()[0]), (int) Math.floor(spec.pos()[1]), (int) Math.floor(spec.pos()[2])};
            } else {
                // Without pos the bot logs in at the world spawn: zones are checked there.
                net.minecraft.server.level.ServerLevel l = spec.dimension() != null ? Dims.level(server, spec.dimension()) : server.overworld();
                net.minecraft.core.BlockPos sp = Mc.spawnPos(l);
                at = new int[]{sp.getX(), sp.getY(), sp.getZ()};
                dim = Dims.id(l);
            }
            gm.gate().zone(a.caller(), a.raw(), gm.gate().nodeOf("bot.spawn", a.raw()), dim, at, at);
            boolean metered = a.caller().agent() != null
                    && gm.gate().rights(a.caller(), a.raw()).effective() != ua.zentix.almighty.policy.Role.OWNER;
            return gm.bots().spawn(metered ? spec.metered(a.caller().name()) : spec).thenApply(o -> o);
        });
        m.put("bot.remove", a -> {
            String name = a.string("name");
            return gm.onMain(() -> {
                Bot bot = gm.bots().mine(gm.bots().get(name), a.caller(), a.raw(), "remove");
                gm.bots().remove(bot, "removed by the game master");
                JsonObject out = new JsonObject();
                out.addProperty("removed", bot.name());
                return out;
            });
        });
        m.put("bot.act", a -> gm.bots().act(a.string("name"), a.array("actions"), a.bool("replace", false), waitSeconds(a), a.raw()));
        m.put("see", a -> see(gm, a));
        m.put("ships", a -> gm.onMain(() -> Ships.describe(server, a)));
        m.put("script", a -> script(gm, a));
        m.put("rule.add", a -> {
            // Bound to its creator (plan 4.12): the requester, or the agent unasked; the owner's rules have no TTL.
            // An admin's one-call approval is never turned into a standing rule bound to that admin.
            ua.zentix.almighty.policy.Confirmations.Pending approving = ua.zentix.almighty.policy.Confirmations.running();
            if (approving != null && approving.kind == ua.zentix.almighty.policy.Confirmations.Kind.ADMIN && a.caller().agent() != null) {
                throw new RpcException(403, "policy", "A rule cannot be made through an admin's approval: rules act for their own creator");
            }
            ua.zentix.almighty.rules.Declarative.Creator creator = creator(gm, a);
            boolean owner = creator.agent() == null || gm.gate().role(creator.agent(), creator.player()) == ua.zentix.almighty.policy.Role.OWNER;
            Rules.Spec spec = Rules.parse(a, creator, owner, System.currentTimeMillis());
            if (!owner && spec.decl() != null) {
                if (spec.decl().cancels() && !ua.zentix.almighty.rules.Declarative.placed(spec.event().type())) {
                    throw new RpcException(403, "policy", spec.event().name() + " has no place in the world: a rule that cancels it everywhere needs the owner");
                }
                if (spec.decl().cancels()) gm.gate().require(a.caller(), a.raw(), ua.zentix.almighty.policy.Node.BUILD, "a rule that cancels acts where its creator may build");
                if (spec.decl().saysToAll(creator)) gm.gate().require(a.caller(), a.raw(), ua.zentix.almighty.policy.Node.SPEAK_ALL, "a rule that speaks to anyone but its creator");
                for (ua.zentix.almighty.rules.Declarative.Action s : spec.decl().actions("say")) {
                    if (ua.zentix.almighty.policy.Forgery.prefix(s.text())) {
                        throw new RpcException(403, "policy", "Rule text may not carry " + ua.zentix.almighty.act.Chat.RESERVED);
                    }
                }
                if (spec.decl().commands()) {
                    // The same checks a command call gets below owner, on the template (fields fill only plain names and numbers).
                    gm.gate().require(a.caller(), a.raw(), ua.zentix.almighty.policy.Node.COMMAND, "a rule that runs commands");
                    for (ua.zentix.almighty.rules.Declarative.Action c : spec.decl().actions("command")) {
                        String forged = ua.zentix.almighty.policy.Forgery.refusal(c.text());
                        if (forged != null) throw new RpcException(403, "policy", forged);
                        JsonObject one = new JsonObject();
                        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
                        list.add(c.text());
                        one.add("commands", list);
                        // Nobody is there to confirm when the rule fires: what would need a player's confirmation is refused.
                        if (ua.zentix.almighty.policy.Confirmations.needsRequester("command", one, creator.name() == null ? "" : creator.name(), creator.player(),
                                gm.policies().current().safety().confirmFirst())) {
                            throw new RpcException(403, "policy", "Not in a rule: \"" + c.text() + "\" would need a player's confirmation each time");
                        }
                    }
                }
            }
            ua.zentix.almighty.rules.Declarative.Creator mine = creator;
            boolean admin = owner || gm.gate().role(creator.agent(), creator.player()).atLeast(ua.zentix.almighty.policy.Role.ADMIN);
            return gm.onMain(() -> gm.rules().add(spec, c -> admin || sameCreator(c, mine)));
        });
        m.put("rule.remove", a -> {
            // Below admin, only rules made for you (the same player, or the same agent unasked).
            ua.zentix.almighty.rules.Declarative.Creator me = creator(gm, a);
            boolean admin = me.agent() == null || gm.gate().role(me.agent(), me.player()).atLeast(ua.zentix.almighty.policy.Role.ADMIN);
            return gm.onMain(() -> gm.rules().remove(a, c -> admin || sameCreator(c, me)));
        });
        m.put("rules", a -> gm.onMain(() -> gm.rules().describe()));
        m.put("event.types", a -> {
            String query = a.string("query", "");
            int limit = a.integer("limit", 50, 1, 500);
            return CompletableFuture.completedFuture(Json.from(ua.zentix.almighty.platform.Platform.get().events().search(query, limit)));
        });
        m.put("audit", a -> {
            int limit = a.integer("limit", 50, 1, 1000);
            String agent = a.string("agent", null), method = a.string("method", null);
            return CompletableFuture.completedFuture(gm.audit().recent(limit, e ->
                    (agent == null || e.get("agent").getAsString().equals(agent))
                            && (method == null || e.get("method").getAsString().equals(method))));
        });
        m.put("events", a -> {
            long after = a.longValue("after", 0, 0, Long.MAX_VALUE);
            int wait = a.integer("wait", 0, 0, 25);
            int limit = a.integer("limit", 200, 1, 1000);
            // mine: messages routed to other agents are left out (each message reaches one agent).
            boolean mine = a.bool("mine", false);
            String me = a.caller().agent() == null ? null : a.caller().agent().name();
            try {
                // Player-authored text only from players who may reach this agent (its trust floor, plan 4.2).
                java.util.function.Predicate<java.util.UUID> heard = heardBy(gm, a.caller());
                JsonObject page = gm.feed().read(after, limit, wait * 1000L, e -> (!mine || me == null
                        || !e.has("agent") || e.get("agent").getAsString().equals(me))
                        && (!e.has("untrusted") || !e.has("uuid") || heard.test(java.util.UUID.fromString(e.get("uuid").getAsString()))));
                if (a.caller().agent() != null) a.caller().agent().read(page.get("next").getAsLong());
                return CompletableFuture.completedFuture(page);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RpcException.unavailable("The bridge is stopping");
            }
        });
        return m;
    }

    /**
     * Whose player-authored text an agent may read: players who hold every right it has unasked (its trust floor;
     * {@link ua.zentix.almighty.policy.Nodes#covers}). In-process callers read everything.
     */
    static java.util.function.Predicate<java.util.UUID> heardBy(GmServer gm, ua.zentix.almighty.bridge.Caller caller) {
        if (caller.agent() == null) return id -> true;
        ua.zentix.almighty.policy.Role floor = gm.policies().current().floor(caller.agent().name());
        return id -> ua.zentix.almighty.policy.Nodes.covers(id, floor);
    }

    /** The same rule creator: the same player, or the same agent unasked (the owner's rules are nobody's below admin). */
    private static boolean sameCreator(ua.zentix.almighty.rules.Declarative.Creator c, ua.zentix.almighty.rules.Declarative.Creator me) {
        return c.agent() != null && java.util.Objects.equals(c.player(), me.player()) && (me.player() != null || c.agent().equals(me.agent()));
    }

    /** Who a rule is for: the console or internal caller (the owner), the requester of this call, or the agent unasked. */
    private static ua.zentix.almighty.rules.Declarative.Creator creator(GmServer gm, Args a) throws RpcException {
        if (a.caller().agent() == null) return ua.zentix.almighty.rules.Declarative.Creator.OWNER;
        ua.zentix.almighty.policy.Gate.Rights r = gm.gate().rights(a.caller(), a.raw());
        return r.ticket() == null ? new ua.zentix.almighty.rules.Declarative.Creator(r.agent(), null, null)
                : new ua.zentix.almighty.rules.Declarative.Creator(r.agent(), r.ticket().player(), r.ticket().name());
    }

    /**
     * Groovy script on the server thread between ticks: compilation happens here on the bridge thread; the value
     * is JSON, {@code println} output is a string; errors return {@code ok: false} with text and script line.
     * The full code is logged at INFO.
     */
    private static CompletableFuture<JsonElement> script(GmServer gm, Args a) throws RpcException {
        if (!GmConfig.SCRIPTS_ENABLED.get()) throw Rules.scriptsOff();
        // A script by name: the owner's file, run only while its hash matches the owner's approval.
        boolean named = !a.has("code") && a.has("name");
        String scriptName = named ? a.string("name") : null;
        if (named && !scriptName.matches("[a-z0-9_-]{1,40}")) throw RpcException.badRequest("name: lowercase letters, digits, - and _");
        String code = named ? gm.files().approvedScript(scriptName) : a.string("code");
        if (code.length() > Rules.MAX_SCRIPT) throw RpcException.badRequest("code: at most " + Rules.MAX_SCRIPT / 1024 + " KB");
        int timeout = a.integer("timeout_ms", 1000, 1, MAX_SCRIPT_MS);
        // Plan 4.11: below owner (a granted engine node, an approved script run by a builder) a script holds the tick
        // for at most SCRIPT_MS_BELOW_OWNER.
        boolean capped = a.caller().agent() != null && timeout > SCRIPT_MS_BELOW_OWNER
                && gm.gate().rights(a.caller(), a.raw()).effective() != ua.zentix.almighty.policy.Role.OWNER;
        if (capped) timeout = SCRIPT_MS_BELOW_OWNER;
        Object args = a.has("args") ? Json.toJava(a.raw().get("args")) : new LinkedHashMap<String, Object>();
        Scripts.Compiled compiled;
        try {
            compiled = Scripts.compile("script", code, timeout);
        } catch (Scripts.CompileError e) {
            JsonObject out = new JsonObject();
            out.addProperty("ok", false);
            out.addProperty("error", "Script failed to compile: " + e.getMessage());
            return CompletableFuture.completedFuture(out);
        }
        return gm.onMain(() -> {
            if (named) LOG.info("GM: approved script {}", scriptName);
            else LOG.info("GM: script\n{}", code);
            ScriptApi api = new ScriptApi(gm.server(), false, data -> {
                JsonObject d = new JsonObject();
                d.addProperty("script", true);
                d.add("data", data);
                gm.feed().add("emit", d);
            });
            try {
                Map<String, Object> vars = new java.util.HashMap<>(Map.of("server", gm.server(), "gm", api, "state", gm.state(), "args", args));
                // Who asked: an approved script may act for its requester (its own code decides how).
                vars.put("requester", a.caller().requester() == null ? null : a.caller().requester().toString());
                Scripts.Result r = Scripts.run(compiled, vars);
                JsonObject out = new JsonObject();
                out.addProperty("ok", r.ok());
                if (r.ok()) out.add("value", Json.from(r.value()));
                else out.addProperty("error", r.message());
                if (r.line() > 0) out.addProperty("line", r.line());
                if (!r.output().isEmpty()) out.addProperty("output", r.output());
                out.addProperty("ms", Math.round(r.nanos() / 1e4) / 100.0);
                if (capped) out.addProperty("timeout_ms", SCRIPT_MS_BELOW_OWNER);
                return out;
            } finally {
                compiled.close();
            }
        });
    }

    /**
     * Vision: a bot or player's eyes ({@code name}), any entity ({@code uuid}), or a free camera ({@code at} +
     * {@code yaw}/{@code pitch}). Without an image ({@code image: false}), return immediately; with one, use a snapshot job.
     */
    private static CompletableFuture<JsonElement> see(GmServer gm, Args a) throws RpcException {
        MinecraftServer server = gm.server();
        boolean image = a.bool("image", true);
        int width = a.integer("width", 224, 16, EyeView.MAX_WIDTH), height = a.integer("height", 126, 16, EyeView.MAX_HEIGHT);
        double fov = a.number("fov", 70, 10, 150);
        double distance = a.number("distance", 96, 4, EyeView.MAX_DISTANCE);
        int radius = a.integer("radius", 48, 1, 128);
        int sounds = a.integer("sounds_ticks", 100, 0, 20 * 60);
        String name = a.string("name", null), uuid = a.string("uuid", null);
        double[] at = a.has("at") ? numbers(a, "at") : null;
        float yaw = (float) a.number("yaw", 0, -360, 360), pitch = (float) a.number("pitch", 0, -90, 90);
        if ((name != null ? 1 : 0) + (uuid != null ? 1 : 0) + (at != null ? 1 : 0) != 1) throw RpcException.badRequest("see: exactly one of name, uuid or at");
        // A free camera (at) is no one's eyes: without observe.players it lists no players but bots (rights audit M4).
        boolean hide = at != null && !seesPlayers(gm, a, "players seen from a free camera");
        GmServer.MainCall<Object[]> look = () -> {
            Entity viewer = null;
            ServerLevel level;
            Vec3 eye;
            float y = yaw, p = pitch;
            if (at != null) {
                level = Dims.level(server, a);
                eye = new Vec3(at[0], at[1], at[2]);
            } else {
                if (name != null) {
                    viewer = server.getPlayerList().getPlayerByName(name);
                    if (viewer == null) throw RpcException.notFound("Not online: " + name);
                } else {
                    UUID id;
                    try {
                        id = UUID.fromString(uuid);
                    } catch (IllegalArgumentException e) {
                        throw RpcException.badRequest("uuid: " + e.getMessage());
                    }
                    for (ServerLevel l : server.getAllLevels()) if (viewer == null) viewer = l.getEntity(id);
                    if (viewer == null) throw RpcException.notFound("No loaded entity " + uuid);
                }
                level = (ServerLevel) viewer.level();
                eye = viewer.getEyePosition();
                y = viewer.getViewYRot(1.0F);
                p = viewer.getViewXRot(1.0F);
            }
            Eye.Camera cam = Eye.Camera.of(eye, y, p, fov, (double) width / height);
            Bot bot = viewer instanceof ServerPlayer sp ? gm.bots().of(sp) : null;
            JsonObject seen = Eye.observe(level, cam, viewer, bot, radius, distance, sounds);
            if (hide) {
                java.util.function.Predicate<JsonObject> player = o -> "minecraft:player".equals(o.has("type") ? o.get("type").getAsString() : null)
                        && !(o.has("name") && gm.bots().isBotName(o.get("name").getAsString()));
                if (seen.get("entities") instanceof com.google.gson.JsonArray list) {
                    com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
                    for (JsonElement e : list) if (!player.test(e.getAsJsonObject())) kept.add(e);
                    seen.add("entities", kept);
                }
                // The first thing on the view ray, too.
                if (seen.get("aim_far") instanceof JsonObject far && far.get("entity") instanceof JsonObject hit && player.test(hit)) {
                    seen.remove("aim_far");
                }
            }
            return new Object[] {level, cam, viewer, seen};
        };
        if (!image) return gm.onMain(() -> (JsonObject) look.run()[3]);
        return view(gm, a, () -> {
            Object[] l = look.run();
            return new EyeView((ServerLevel) l[0], (Eye.Camera) l[1], width, height, distance, (Entity) l[2], (JsonObject) l[3]);
        });
    }

    /** Whether this call may see where other players are (observe.players); in-process calls may. */
    private static boolean seesPlayers(GmServer gm, Args a, String why) {
        try {
            gm.gate().require(a.caller(), a.raw(), ua.zentix.almighty.policy.Node.OBSERVE_PLAYERS, why);
            return true;
        } catch (RpcException e) {
            return false;
        }
    }

    private static double[] numbers(Args a, String name) throws RpcException {
        JsonElement e = a.raw().get(name);
        if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) throw RpcException.badRequest(name + ": [x, y, z]");
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            JsonElement v = e.getAsJsonArray().get(i);
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw RpcException.badRequest(name + ": [x, y, z] as numbers");
            out[i] = v.getAsDouble();
        }
        return out;
    }

    private static int waitSeconds(Args a) throws RpcException {
        return a.integer("wait", 30, 0, MAX_WAIT);
    }

    /** Snapshot job; the image is encoded as PNG off the server thread. */
    private static CompletableFuture<JsonElement> view(GmServer gm, Args a, GmServer.MainCall<View> factory) throws RpcException {
        int wait = waitSeconds(a);
        CompletableFuture<View> started = gm.onMain(() -> {
            gm.jobs().admit();
            return gm.jobs().submit(factory.run());
        });
        return started.thenCompose(view -> gm.await(view, wait).thenApplyAsync(result -> view.attach(result.getAsJsonObject())));
    }

    /** {@code center} [x, z] and {@code size} (default 128), or {@code from}/{@code to} [x, z]. */
    private static MapView map(MinecraftServer server, Args a) throws RpcException {
        ServerLevel level = Dims.level(server, a);
        int x0, z0, sx, sz;
        if (a.has("center")) {
            int[] c = a.ints("center", 2);
            int size = a.integer("size", 128, 1, MapView.MAX_SIDE);
            x0 = c[0] - size / 2;
            z0 = c[1] - size / 2;
            sx = sz = size;
        } else {
            int[] from = a.ints("from", 2), to = a.ints("to", 2);
            x0 = Math.min(from[0], to[0]);
            z0 = Math.min(from[1], to[1]);
            sx = Math.abs(from[0] - to[0]) + 1;
            sz = Math.abs(from[1] - to[1]) + 1;
            if (sx > MapView.MAX_SIDE || sz > MapView.MAX_SIDE) throw RpcException.badRequest("Map: at most " + MapView.MAX_SIDE + " blocks per side");
        }
        Integer below = a.has("below") ? a.integer("below", -2048, 4096) : null;
        List<MapView.Mark> marks = new ArrayList<>();
        if (a.has("marks")) {
            for (JsonElement e : a.array("marks")) {
                if (!e.isJsonObject()) throw RpcException.badRequest("marks: objects {label, x, z}");
                Args mark = new Args(e.getAsJsonObject());
                marks.add(new MapView.Mark(mark.string("label", "?"), mark.integer("x", -30_000_000, 30_000_000), mark.integer("z", -30_000_000, 30_000_000)));
            }
        }
        return new MapView(level, x0, z0, sx, sz, below, marks);
    }

    /**
     * Building: parse the plan on the bridge thread (registries are read-only after startup); acquire chunks
     * and submit the job on the server thread.
     */
    private static CompletableFuture<JsonElement> build(GmServer gm, Args a) throws RpcException {
        MinecraftServer server = gm.server();
        ServerLevel level = Dims.level(server, a);
        HolderLookup<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
        // Below owner: block states only, never #almighty:never_build (plan 4.3).
        boolean limited = a.caller().agent() != null
                && gm.gate().rights(a.caller(), a.raw()).effective() != ua.zentix.almighty.policy.Role.OWNER;
        BuildPlan<BlockInput> plan = BuildPlan.parse(a.array("ops"), s -> {
            try {
                BlockStateParser.BlockResult r = BlockStateParser.parseForBlock(blocks, s, true);
                if (limited) ua.zentix.almighty.policy.ContentLimits.block(s, r.blockState(), r.nbt() != null);
                return new BlockInput(r.blockState(), r.properties().keySet(), r.nbt());
            } catch (CommandSyntaxException e) {
                throw RpcException.badRequest("block \"" + s + "\": " + e.getMessage());
            }
        }, Mc.minBuildHeight(level), Mc.maxBuildHeight(level) - 1, GmConfig.BUILD_MAX_BLOCKS.get());
        int[] b = plan.bounds();
        gm.gate().zone(a.caller(), a.raw(), ua.zentix.almighty.policy.Node.BUILD, Dims.id(level),
                new int[]{b[0], b[1], b[2]}, new int[]{b[3], b[4], b[5]});
        // A player's request below owner is charged to that player's hourly block quota.
        // The agent's own initiative below owner is charged to the agent (dropping the ticket must not lift the quota).
        if (limited) {
            gm.quotas().charge(a.caller().requester() != null ? a.caller().requester().toString() : "agent:" + a.caller().agent(),
                    "build.blocks", plan.size(), gm.policies().current().limits().buildBlocks());
        }
        java.util.UUID requester = a.caller().requester();
        return gm.submit(() -> {
            // Claims (plan 4.7): below owner, as the requester (or a stranger, unasked), on the server thread.
            if (limited) {
                String claimed = gm.claims().refusal(requester, level, b[0], b[2], b[3], b[5]);
                if (claimed != null) throw new RpcException(403, "policy", "Not built: " + claimed);
            }
            return BuildJob.build(level, gm.areas(), plan, CHUNK_WAIT_TICKS);
        }, waitSeconds(a));
    }
}
