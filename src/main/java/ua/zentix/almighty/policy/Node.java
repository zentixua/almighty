package ua.zentix.almighty.policy;

import java.util.Locale;

/**
 * Capabilities as permission nodes (plan 4.3): {@code almighty.<id>}, registered with the server's permission API
 * ({@link Nodes}), so a permission plugin (LuckPerms) can grant them; without one each answers from the role ladder
 * at its default role.
 */
public enum Node {
    KNOWLEDGE("knowledge", Role.GUEST, "read notes, the catalog and the event feed"),
    NOTES_WRITE("notes.write", Role.MEMBER, "write world notes"),
    OBSERVE_WORLD("observe.world", Role.GUEST, "look at the world: status, maps, views, blocks, entities, jobs"),
    OBSERVE_PLAYERS("observe.players", Role.ADMIN, "look at other players: where they are and what they see"),
    SPEAK_REPLY("speak.reply", Role.GUEST, "answer players privately"),
    SPEAK_ALL("speak.all", Role.MEMBER, "speak to everyone, titles"),
    BUILD("build", Role.MEMBER, "build and undo own builds"),
    GIVE_SELF("give.self", Role.MEMBER, "give items to the requester"),
    BOTS("bots", Role.MEMBER, "bots in survival or adventure"),
    BOTS_ADMIN("bots.admin", Role.ADMIN, "creative or spectator bots, client packets, skins"),
    AREA("area", Role.BUILDER, "keep areas loaded"),
    RULES("rules", Role.BUILDER, "rules without scripts"),
    UNDO_ANY("undo.any", Role.ADMIN, "undo other agents' changes"),
    AUDIT("audit", Role.ADMIN, "read the audit log"),
    APPROVE("approve", Role.ADMIN, "approve players' commands above their rights (ask_admin)"),
    // Commands run as the requester (or at the floor's permission level): vanilla's own checks decide what runs.
    COMMAND("command", Role.GUEST, "run commands and functions with the requester's own permission level"),
    ENGINE("engine", Role.OWNER, "scripts and rules with scripts (code in the server JVM)");

    private final String id;
    private final Role defaultRole;
    private final String description;

    Node(String id, Role defaultRole, String description) {
        this.id = id;
        this.defaultRole = defaultRole;
        this.description = description;
    }

    /** The node's name after {@code almighty.}. */
    public String id() {
        return id;
    }

    /** The node as LuckPerms and the permission API see it. */
    public String key() {
        return "almighty." + id;
    }

    /** NeoForge node names allow lowercase letters, digits, {@code _} and dots. */
    String nodeName() {
        return id.toLowerCase(Locale.ROOT);
    }

    public Role defaultRole() {
        return defaultRole;
    }

    public String description() {
        return description;
    }
}
