package ua.zentix.almighty.policy;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ua.zentix.almighty.bridge.Dispatch;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.feed.Feed;
import ua.zentix.almighty.platform.Mc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Confirmations at work on the server: shows the question, takes the answer ({@code /almighty confirm|deny}), runs
 * the allowed call, tells the agent ({@code confirmation} feed event, {@code pending} method) and the requester,
 * expires stale questions, and sends admins one quiet digest line at most every {@value #DIGEST_TICKS} ticks.
 */
public final class Decisions {
    static final int DIGEST_TICKS = 12_000;

    private final MinecraftServer server;
    private final Confirmations confirmations;
    private final Tickets tickets;
    private final Feed feed;
    private Dispatch dispatch;
    private int lastDigest = Integer.MIN_VALUE / 2;
    private boolean news;

    public Decisions(MinecraftServer server, Confirmations confirmations, Tickets tickets, Feed feed) {
        this.server = server;
        this.confirmations = confirmations;
        this.tickets = tickets;
        this.feed = feed;
    }

    public void dispatch(Dispatch d) {
        this.dispatch = d;
    }

    public Confirmations confirmations() {
        return confirmations;
    }

    /**
     * From the gate (any thread): requester questions now; an admin request goes straight to the admins online who
     * may decide it, as a direct chat question, within limits (no approval spam): at most {@value #DIRECT_PER_ADMIN}
     * direct questions per admin and one per requester (or per agent asking on its own) every 10 minutes; merged
     * identical requests are never asked again. What is over a limit, or finds nobody online, waits in the quiet
     * digest and {@code /almighty inbox} as before.
     */
    public void prompt(Confirmations.Pending p) {
        server.execute(() -> {
            if (p.kind == Confirmations.Kind.REQUESTER) Prompts.ask(server, p);
            else if (!direct(p)) news = true;
            event(p);
        });
    }

    static final int DIRECT_PER_ADMIN = 3;
    static final long DIRECT_WINDOW_MS = 10 * 60_000;
    private final java.util.Map<UUID, java.util.Deque<Long>> directByAdmin = new java.util.HashMap<>();
    private final java.util.Map<String, Long> directByAsker = new java.util.HashMap<>();
    private final java.util.Map<String, Set<UUID>> prompted = new java.util.LinkedHashMap<>();

    /** The admins a request was put to directly (tests, and the owners' notice). */
    public Set<UUID> promptedFor(String id) {
        return prompted.getOrDefault(id, Set.of());
    }

    /** Whether waiting admin requests are due for the next digest line. */
    public boolean digestDue() {
        return news;
    }

    /** True when every admin online who may decide it got the question directly (server thread). */
    private boolean direct(Confirmations.Pending p) {
        long now = System.currentTimeMillis();
        String asker = p.requester != null ? p.requester.toString() : "agent:" + p.caller.name();
        Long last = directByAsker.get(asker);
        if (last != null && now - last < DIRECT_WINDOW_MS) return false;
        Set<UUID> told = new HashSet<>();
        boolean all = true;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (id.equals(p.requester) || !mayDecide(id, p)) continue;
            java.util.Deque<Long> recent = directByAdmin.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
            while (!recent.isEmpty() && now - recent.peekFirst() >= DIRECT_WINDOW_MS) recent.pollFirst();
            if (recent.size() >= DIRECT_PER_ADMIN) {
                all = false;
                continue;
            }
            recent.addLast(now);
            told.add(id);
            player.sendSystemMessage(Prompts.adminQuestion(p, ua.zentix.almighty.feed.GmMessages.language(player)));
        }
        if (told.isEmpty()) return false;
        directByAsker.put(asker, now);
        if (prompted.size() >= Confirmations.MAX) prompted.remove(prompted.keySet().iterator().next());
        prompted.put(p.id, Set.copyOf(told));
        return all;
    }

    private static boolean mayDecide(UUID id, Confirmations.Pending p) {
        return Identity.verified(id) && Nodes.holds(id, p.node) && (p.node == Node.ENGINE || Nodes.holds(id, Node.APPROVE));
    }

    /** {@code /almighty confirm|deny <nonce>}: {@code by} null is the console. Returns the answer for the decider. */
    public String decide(String nonce, ServerPlayer by, boolean allow) {
        Confirmations.Pending p = confirmations.byNonce(nonce);
        if (p == null) return "No such request (it may have expired)";
        UUID id = by == null ? null : by.getUUID();
        String name = by == null ? "the console" : Mc.name(by.getGameProfile());
        String why = confirmations.decide(p, id, name, allow, node -> Nodes.holds(id, node));
        if (why != null) return "Not decided: " + why;
        event(p);
        // Plan 4.8: an admin's decision is told to the owners online (the owner's own decisions need no notice).
        if (p.kind == Confirmations.Kind.ADMIN) {
            java.util.Set<UUID> owners = this.owners.get();
            String line = name + (allow ? " allowed: " : " denied: ") + Prompts.what(p.summary, p.facts, p.text, "en_us", false).getString()
                    + " (for " + p.requesterName + "; exact call: " + p.text + ")";
            for (UUID o : owners) if (!o.equals(id)) tell(o, line);
        }
        // Lead's F3: the requester who decided hears the translated outcome only; anyone else (an admin, the console)
        // gets one line in their own language.
        boolean self = id != null && id.equals(p.requester);
        String lang = by == null ? "en_us" : ua.zentix.almighty.feed.GmMessages.language(by);
        if (!allow) {
            tell(p.requester, "almighty.outcome.not_allowed", p);
            return self ? "" : ua.zentix.almighty.feed.GmMessages.get("almighty.outcome.denied", lang, plain(p, lang)).getString();
        }
        // The exact parameters that were shown. An admin's approval is carried by the running confirmation itself
        // (Pending.decider), never by a ticket in the parameters that could outlive this one call.
        JsonObject params = p.params.deepCopy();
        CompletableFuture<?> run = by == null && p.kind == Confirmations.Kind.ADMIN
                ? Confirmations.run(p, dispatch, params, true) : Confirmations.run(p, dispatch, params, false);
        run.whenComplete((v, e) -> server.execute(() -> {
            event(p);
            // Lead's F1 and F3: a command that ran but failed (ok: false) is a failure; a success already got its
            // receipt ("Done for you", with [Undo]), so no second "Done" line.
            if (e != null || failed(v)) tell(p.requester, "almighty.outcome.failed", p);
        }));
        return self ? "" : ua.zentix.almighty.feed.GmMessages.get("almighty.outcome.allowed", lang, plain(p, lang)).getString();
    }

    /** The call as the decider's answer names it: summary and facts in words, the exact call after them. */
    private static String plain(Confirmations.Pending p, String lang) {
        return Prompts.what(p.summary, p.facts, p.text, lang, false).getString() + " (" + p.text + ")";
    }

    /** Whether a call's result reports a failed command ({@code ok: false} in a command list). */
    public static boolean failed(Object result) {
        if (result instanceof com.google.gson.JsonArray list) {
            for (var r : list) if (r instanceof JsonObject o && o.has("ok") && !o.get("ok").getAsBoolean()) return true;
        }
        // A function's result is one object with ok (review of #101).
        return result instanceof JsonObject o && o.has("ok") && o.get("ok").isJsonPrimitive() && !o.get("ok").getAsBoolean();
    }

    /** The policy's owners (UUIDs), told of every admin decision. */
    private volatile java.util.function.Supplier<java.util.Set<UUID>> owners = java.util.Set::of;

    public void owners(java.util.function.Supplier<java.util.Set<UUID>> owners) {
        this.owners = owners;
    }

    /** A notice to every owner online (any thread; sent on the server thread). */
    public void tellOwners(String text) {
        server.execute(() -> {
            for (UUID owner : owners.get()) tell(owner, text);
        });
    }

    private void tell(UUID player, String text) {
        if (player == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null) p.sendSystemMessage(Prompts.line(text));
    }

    /** A translated outcome to the requester (English or Russian by their client), naming the call as the question did. */
    private void tell(UUID player, String key, Confirmations.Pending pending) {
        if (player == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null) p.sendSystemMessage(Prompts.outcome(key, pending, ua.zentix.almighty.feed.GmMessages.language(p)));
    }

    private void event(Confirmations.Pending p) {
        feed.add("confirmation", p.describe());
    }

    /** Once a second: expiries; the digest when new admin requests wait and the last digest is old enough. */
    public void tick(int tickCount) {
        if (tickCount % 20 != 0) return;
        for (Confirmations.Pending p : confirmations.expire(System.currentTimeMillis())) {
            event(p);
            tell(p.requester, "almighty.outcome.expired", p);
        }
        if (!news || tickCount - lastDigest < DIGEST_TICKS) return;
        List<Confirmations.Pending> waiting = confirmations.waitingForAdmins();
        news = false;
        if (waiting.isEmpty()) return;
        lastDigest = tickCount;
        Set<UUID> told = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (Confirmations.Pending p : waiting) {
                if (mayDecide(player.getUUID(), p) && told.add(player.getUUID())) {
                    player.sendSystemMessage(Prompts.digest(waiting, ua.zentix.almighty.feed.GmMessages.language(player)));
                }
            }
        }
    }

    /** {@code pending {id, wait?}}: the agent's own waiting call. */
    public CompletableFuture<JsonObject> pending(String agent, String id, int waitSeconds) throws RpcException {
        Confirmations.Pending p = confirmations.get(id);
        if (p == null || !p.caller.name().equals(agent)) throw RpcException.notFound("No pending call " + id);
        if (waitSeconds == 0 || p.done.isDone()) return CompletableFuture.completedFuture(p.done.isDone() ? p.done.join() : p.describe());
        return p.done.copy().completeOnTimeout(p.describe(), waitSeconds, TimeUnit.SECONDS);
    }
}
