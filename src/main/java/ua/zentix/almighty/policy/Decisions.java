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

    /** From the gate (any thread): requester questions now, admin requests in the next digest. */
    public void prompt(Confirmations.Pending p) {
        server.execute(() -> {
            if (p.kind == Confirmations.Kind.REQUESTER) Prompts.ask(server, p);
            else news = true;
            event(p);
        });
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
            String line = name + (allow ? " allowed: " : " denied: ") + p.text + " (for " + p.requesterName + ")";
            for (UUID o : owners) if (!o.equals(id)) tell(o, line);
        }
        if (!allow) {
            tell(p.requester, "almighty.outcome.not_allowed", p.text);
            return "Denied: " + p.text;
        }
        // The exact parameters that were shown. An admin's approval is carried by the running confirmation itself
        // (Pending.decider), never by a ticket in the parameters that could outlive this one call.
        JsonObject params = p.params.deepCopy();
        CompletableFuture<?> run = by == null && p.kind == Confirmations.Kind.ADMIN
                ? Confirmations.run(p, dispatch, params, true) : Confirmations.run(p, dispatch, params, false);
        run.whenComplete((v, e) -> server.execute(() -> {
            event(p);
            tell(p.requester, e == null ? "almighty.outcome.done" : "almighty.outcome.failed", p.text);
        }));
        return "Allowed: " + p.text;
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

    /** A translated outcome to the requester (English or Russian by their client). */
    private void tell(UUID player, String key, String arg) {
        if (player == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null) p.sendSystemMessage(Prompts.line(key, ua.zentix.almighty.feed.GmMessages.language(p), arg));
    }

    private void event(Confirmations.Pending p) {
        feed.add("confirmation", p.describe());
    }

    /** Once a second: expiries; the digest when new admin requests wait and the last digest is old enough. */
    public void tick(int tickCount) {
        if (tickCount % 20 != 0) return;
        for (Confirmations.Pending p : confirmations.expire(System.currentTimeMillis())) {
            event(p);
            tell(p.requester, "almighty.outcome.expired", p.text);
        }
        if (!news || tickCount - lastDigest < DIGEST_TICKS) return;
        List<Confirmations.Pending> waiting = confirmations.waitingForAdmins();
        news = false;
        if (waiting.isEmpty()) return;
        lastDigest = tickCount;
        Set<UUID> told = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (Confirmations.Pending p : waiting) {
                if (Identity.verified(player.getUUID()) && Nodes.holds(player.getUUID(), p.node) && (p.node == Node.ENGINE || Nodes.holds(player.getUUID(), Node.APPROVE)) && told.add(player.getUUID())) {
                    player.sendSystemMessage(Prompts.digest(waiting));
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
