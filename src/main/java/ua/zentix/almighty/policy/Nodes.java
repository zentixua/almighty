package ua.zentix.almighty.policy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ua.zentix.almighty.platform.Mc;
import ua.zentix.almighty.platform.Platform;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The {@link Node}s as the loader's permission nodes ({@code almighty.<id>}; NeoForge's permission API, on Fabric
 * lucko's fabric-permissions-api), so the server's permission handler decides for players: LuckPerms (groups, temporary grants) when installed, otherwise each node's default resolver, which
 * answers from the role ladder: owners from the policy (verified UUIDs only), operators of level 3+ as admins, level 2
 * as builders, every other verified player at the preset's player role (member on friends, guest otherwise),
 * unverified players as guests.
 */
public final class Nodes {
    /** The running server and its policy; null between servers (the resolver then answers false). */
    private static volatile MinecraftServer server;
    private static volatile Supplier<Policy> policy = Policy::defaults;
    /** Op levels and online players, copied on the server thread once a second (bridge threads read only these). */
    private static volatile Map<UUID, Integer> ops = Map.of();
    private static volatile Map<UUID, ServerPlayer> online = Map.of();

    /** Server thread: refresh the snapshots the gate reads from bridge threads. */
    public static void refresh(MinecraftServer s) {
        // Online players only: a ticket's requester sent a message minutes ago; one who left meanwhile counts
        // without their op level (the safe direction).
        Map<UUID, Integer> o = new java.util.HashMap<>();
        Map<UUID, ServerPlayer> p = new java.util.HashMap<>();
        for (ServerPlayer sp : s.getPlayerList().getPlayers()) {
            p.put(sp.getUUID(), sp);
            int level = Mc.opLevel(s, sp.getGameProfile());
            if (level > 0) o.put(sp.getUUID(), level);
        }
        ops = Map.copyOf(o);
        online = Map.copyOf(p);
    }

    /** Whether a name is an online player's (from the snapshot; any thread). */
    public static boolean online(String name) {
        for (ServerPlayer p : online.values()) if (Mc.name(p.getGameProfile()).equalsIgnoreCase(name)) return true;
        return false;
    }

    /** The UUID of an online player by name (from the snapshot; any thread), or null. */
    public static UUID onlineId(String name) {
        for (ServerPlayer p : online.values()) if (Mc.name(p.getGameProfile()).equalsIgnoreCase(name)) return p.getUUID();
        return null;
    }

    /** Extra roles (the ladder of step 9: {@code /almighty role}); none yet. */
    private static volatile java.util.function.Function<UUID, Role> granted = id -> null;

    private Nodes() {}

    /** A node's default answer for a player when no permission plugin decides: the role ladder. */
    public static boolean byDefault(UUID id, Node n) {
        return role(id).atLeast(n.defaultRole());
    }

    public static void start(MinecraftServer s, Supplier<Policy> p) {
        server = s;
        policy = p;
    }

    /**
     * A permission handler standing in for the server's (GameTest: the LuckPerms path without LuckPerms): answers
     * {@code true}/{@code false} for a player and node, or null to fall through to the server's handler.
     */
    public interface Handler {
        Boolean get(UUID player, Node node);

        /** The answer with the {@code almighty:zone} context set to {@code zone}; null: none. */
        default Boolean getIn(UUID player, Node node, String zone) {
            return null;
        }

        /** The integer node {@code almighty.quota.<counter>}; null: the policy's value. */
        default Integer quota(UUID player, String counter) {
            return null;
        }
    }

    private static volatile Handler handler;

    public static void handler(Handler h) {
        handler = h;
    }

    public static void granted(java.util.function.Function<UUID, Role> roles) {
        granted = roles;
    }

    public static void stop(MinecraftServer s) {
        if (server == s) server = null;
    }

    /** A player's role on the ladder (the default answer when no permission plugin decides). */
    public static Role role(UUID id) {
        Policy p = policy.get();
        boolean verified = Identity.verified(id);
        if (verified && p.owners().contains(id)) return Role.OWNER;
        Role best = verified ? p.preset().playerRole() : Role.GUEST;
        Role extra = granted.apply(id);
        if (extra != null && verified && extra.compareTo(best) > 0) best = Role.min(extra, Role.ADMIN);
        Integer level = verified ? ops.get(id) : null;
        if (level != null) {
            Role byOp = level >= 3 ? Role.ADMIN : level == 2 ? Role.BUILDER : Role.MEMBER;
            if (byOp.compareTo(best) > 0) best = byOp;
        }
        return best;
    }

    /**
     * Whether a player holds every right an agent at this trust floor has unasked: each node the floor's role holds,
     * and its command permission level (as {@code GmServer.commandAs}: max of op level and role level). Only such a
     * player may reach the agent (plan 4.2: the floor is the rights of the least-trusted player whose text can reach
     * it), so acting on a message unasked never gives its author more than they may do themselves. Any thread: the
     * snapshots and the permission handler, as the gate reads them.
     */
    public static boolean covers(UUID id, Role floor) {
        return covers(id, floor, false);
    }

    /**
     * As {@link #covers}; {@code noWait}: an offline player's nodes come from the ladder, never from a permission
     * plugin that may load the user from storage (the feed filter runs under the feed lock, which the server thread
     * needs for every event).
     */
    public static boolean covers(UUID id, Role floor, boolean noWait) {
        for (Node n : Node.values()) if (floor.atLeast(n.defaultRole()) && !holds(id, n, noWait)) return false;
        Integer op = Identity.verified(id) ? ops.get(id) : null;
        int level = Math.max(op == null ? 0 : op, role(id).commandLevel());
        return level >= floor.commandLevel();
    }

    /** The current policy (the gate's; any thread). */
    public static Policy policy() {
        return policy.get();
    }

    /**
     * Whether a verified player holds {@code node} in zone {@code zone} by a grant scoped to it: the permission
     * handler's answer with the dynamic context {@code almighty:zone=<zone>} (plan 4.3; LuckPerms: {@code /lp user Bob
     * permission set almighty.build true almighty:zone=arena}). Without a handler there are no scoped grants. Any thread.
     */
    public static boolean holdsIn(UUID id, Node node, String zone) {
        if (!Identity.verified(id)) return false;
        Handler h = handler;
        Boolean forced = h == null ? null : h.getIn(id, node, zone);
        if (forced != null) return forced;
        ServerPlayer player = server == null ? null : online.get(id);
        Boolean v = Platform.present() && player != null ? Platform.get().permissionIn(id, player, node, zone) : null;
        return Boolean.TRUE.equals(v);
    }

    /**
     * A player's hourly limit for {@code counter} ({@code build_blocks}, {@code give_items}): the permission handler's
     * integer node {@code almighty.quota.<counter>} (LuckPerms meta) for a verified online player, else
     * {@code fallback} (the policy's {@code [quotas]}). Any thread.
     */
    public static long quota(UUID id, String counter, long fallback) {
        if (id == null || !Identity.verified(id)) return fallback;
        Handler h = handler;
        Integer forced = h == null ? null : h.quota(id, counter);
        if (forced != null) return Math.max(0, forced);
        ServerPlayer player = server == null ? null : online.get(id);
        Integer v = Platform.present() && player != null ? Platform.get().quota(player, counter) : null;
        return v != null ? Math.max(0, v) : fallback;
    }

    /** Whether a player holds a node: the permission handler for an online player, the offline query otherwise. */
    public static boolean holds(UUID id, Node node) {
        return holds(id, node, false);
    }

    private static boolean holds(UUID id, Node node, boolean noWait) {
        // An owner is the owner whatever a plugin says; an unverified identity (anyone can claim an offline name) is a
        // guest whatever a plugin says; everyone else is the handler's to decide.
        Role ladder = role(id);
        if (ladder == Role.OWNER) return true;
        if (!Identity.verified(id)) return Role.GUEST.atLeast(node.defaultRole());
        Handler h = handler;
        Boolean forced = h == null ? null : h.get(id, node);
        if (forced != null) return forced;
        ServerPlayer player = server == null ? null : online.get(id);
        // No platform (unit tests), no handler yet, or nodes not gathered: the ladder answers.
        Boolean v = Platform.present() && (player != null || !noWait) ? Platform.get().permission(id, player, node) : null;
        return v != null ? v : ladder.atLeast(node.defaultRole());
    }
}
