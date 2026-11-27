package ua.zentix.almighty.policy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.exceptions.UnregisteredPermissionException;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import ua.zentix.almighty.Almighty;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The {@link Node}s as NeoForge permission nodes ({@code almighty.<id>}), so the server's permission handler decides
 * for players: LuckPerms (groups, temporary grants) when installed, otherwise each node's default resolver, which
 * answers from the role ladder: owners from the policy (verified UUIDs only), operators of level 3+ as admins, level 2
 * as builders, every other verified player at the preset's player role (member on friends, guest otherwise),
 * unverified players as guests.
 */
public final class Nodes {
    private static final Map<Node, PermissionNode<Boolean>> NODES = new EnumMap<>(Node.class);
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
            int level = s.getProfilePermissions(sp.getGameProfile());
            if (level > 0) o.put(sp.getUUID(), level);
        }
        ops = Map.copyOf(o);
        online = Map.copyOf(p);
    }

    /** Whether a name is an online player's (from the snapshot; any thread). */
    public static boolean online(String name) {
        for (ServerPlayer p : online.values()) if (p.getGameProfile().getName().equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Extra roles (the ladder of step 9: {@code /almighty role}); none yet. */
    private static volatile java.util.function.Function<UUID, Role> granted = id -> null;

    static {
        for (Node n : Node.values()) {
            NODES.put(n, new PermissionNode<>(Almighty.ID, n.nodeName(), PermissionTypes.BOOLEAN,
                    (player, id, context) -> role(id).atLeast(n.defaultRole())));
        }
    }

    private Nodes() {}

    public static void register(IEventBus bus) {
        bus.addListener((PermissionGatherEvent.Nodes e) -> e.addNodes(NODES.values().toArray(new PermissionNode<?>[0])));
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

    /** Whether a player holds a node: the permission handler for an online player, the offline query otherwise. */
    public static boolean holds(UUID id, Node node) {
        // An owner is the owner whatever a plugin says; an unverified identity (anyone can claim an offline name) is a
        // guest whatever a plugin says; everyone else is the handler's to decide.
        Role ladder = role(id);
        if (ladder == Role.OWNER) return true;
        if (!Identity.verified(id)) return Role.GUEST.atLeast(node.defaultRole());
        Handler h = handler;
        Boolean forced = h == null ? null : h.get(id, node);
        if (forced != null) return forced;
        PermissionNode<Boolean> pn = NODES.get(node);
        try {
            ServerPlayer player = server == null ? null : online.get(id);
            Boolean v = player != null ? PermissionAPI.getPermission(player, pn) : PermissionAPI.getOfflinePermission(id, pn);
            return Boolean.TRUE.equals(v);
        } catch (UnregisteredPermissionException | IllegalStateException e) {
            // No handler yet (unit tests) or nodes not gathered: the ladder answers.
            return ladder.atLeast(node.defaultRole());
        }
    }

    public static PermissionNode<Boolean> node(Node n) {
        return NODES.get(n);
    }
}
