package ua.zentix.almighty.policy;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The owner's policy, {@code config/almighty/policy.toml} (plan 4.5): the single source of truth for who the owners
 * are, which preset applies and how far each agent may go. Parsed strictly: every mistake is reported with its key,
 * and a bad file never replaces a good policy ({@link Policies}).
 */
public record Policy(Preset preset, Set<UUID> owners, Identity.Mode identity, Map<String, AgentRules> agents, Safety safety) {

    /**
     * {@code [safety]}: {@code ask_admin}, the only kinds of request that may reach an admin when the requester lacks the
     * right (plan 4.8; empty: none); {@code confirm_first}, reversible kinds the requester must still confirm. A kind is
     * a command name ({@code kick}, {@code tp}) or a method ({@code build}).
     */
    public record Safety(Set<String> askAdmin, Set<String> confirmFirst) {
        public static final Safety NONE = new Safety(Set.of(), Set.of());
    }

    /** How much other players' text reaches the agents, which fixes their trust floor (plan 4.2). */
    public enum Preset {
        /** Only owners address the agents: the floor is the ceiling (the behaviour before policies existed). */
        PRIVATE,
        /** Friends' server: anyone's text may reach the agent, so without a request it acts as a member. */
        FRIENDS,
        /** Public server: without a request the agent acts as a guest. */
        PUBLIC;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The role a verified player without any grant has. */
        public Role playerRole() {
            return this == FRIENDS ? Role.MEMBER : Role.GUEST;
        }
    }

    /** One agent: its ceiling (the most it may ever do) and, if set, an explicit floor (what it may do unasked). */
    public record AgentRules(Role ceiling, Role floor) {}

    /** Ceiling of an agent the file does not list: the default agent may do everything an owner may, others less. */
    public static Role defaultCeiling(String agent) {
        return agent.equals("gm") ? Role.OWNER : Role.ADMIN;
    }

    public Role ceiling(String agent) {
        AgentRules r = agents.get(agent);
        return r != null && r.ceiling != null ? r.ceiling : defaultCeiling(agent);
    }

    /** The trust floor: the rights of the least-trusted player whose text can reach the agent, never above its ceiling. */
    public Role floor(String agent) {
        Role ceiling = ceiling(agent);
        AgentRules r = agents.get(agent);
        if (r != null && r.floor != null) return Role.min(r.floor, ceiling);
        return switch (preset) {
            case PRIVATE -> ceiling;
            case FRIENDS -> Role.min(Role.MEMBER, ceiling);
            case PUBLIC -> Role.GUEST;
        };
    }

    /**
     * The policy when the file exists but cannot be used at startup (no last good policy): public, every agent at
     * guest. A typo must never open the server wider than the owner chose.
     */
    public static Policy lockdown() {
        return new Policy(Preset.PUBLIC, Set.of(), Identity.Mode.AUTO, Map.of(), Safety.NONE);
    }

    /** The policy when the file has nothing to say: private, no owners, every agent at its default ceiling. */
    public static Policy defaults() {
        return new Policy(Preset.PRIVATE, Set.of(), Identity.Mode.AUTO, Map.of(), Safety.NONE);
    }

    public static final class Invalid extends Exception {
        public Invalid(String message) {
            super(message);
        }
    }

    /** Parse the file's text; every error names its key. */
    public static Policy parse(String toml) throws Invalid {
        Config c;
        try {
            c = new TomlParser().parse(toml);
        } catch (RuntimeException e) {
            throw new Invalid("not valid TOML: " + e.getMessage());
        }
        List<String> errors = new ArrayList<>();
        Set<String> known = Set.of("preset", "owners", "identity", "agents", "safety", "version");
        for (Config.Entry entry : c.entrySet()) if (!known.contains(entry.getKey())) errors.add("unknown key " + entry.getKey());

        Preset preset = Preset.PRIVATE;
        Object p = c.get("preset");
        if (p != null) {
            try {
                preset = Preset.valueOf(String.valueOf(p).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add("preset: private, friends or public, not " + p);
            }
        }
        Set<UUID> owners = new LinkedHashSet<>();
        Object o = c.get("owners");
        if (o instanceof List<?> list) {
            for (Object x : list) {
                try {
                    owners.add(UUID.fromString(String.valueOf(x).strip()));
                } catch (IllegalArgumentException e) {
                    errors.add("owners: not a UUID: " + x + " (owners are UUIDs, not names: names can be taken by others)");
                }
            }
        } else if (o != null) {
            errors.add("owners: a list of UUIDs");
        }
        Identity.Mode identity = Identity.Mode.AUTO;
        Object id = c.get("identity");
        if (id != null) {
            try {
                identity = Identity.Mode.parse(String.valueOf(id));
            } catch (IllegalArgumentException e) {
                errors.add("identity: auto, mojang, proxy or offline, not " + id);
            }
        }
        Map<String, AgentRules> agents = new LinkedHashMap<>();
        Object a = c.get("agents");
        if (a instanceof Config table) {
            for (Config.Entry e : table.entrySet()) {
                String name = e.getKey();
                if (!(e.getValue() instanceof Config t)) {
                    errors.add("agents." + name + ": a table such as [agents." + name + "]");
                    continue;
                }
                for (Config.Entry k : t.entrySet()) {
                    if (!Set.of("ceiling", "floor", "profile", "images").contains(k.getKey())) errors.add("agents." + name + ": unknown key " + k.getKey());
                }
                Role ceiling = role(t.get("ceiling"), "agents." + name + ".ceiling", errors);
                Role floor = role(t.get("floor"), "agents." + name + ".floor", errors);
                if (ceiling != null && floor != null && floor.compareTo(ceiling) > 0) {
                    errors.add("agents." + name + ".floor: above its ceiling " + ceiling.id());
                }
                agents.put(name, new AgentRules(ceiling, floor));
            }
        } else if (a != null) {
            errors.add("agents: tables such as [agents.gm]");
        }
        Safety safety = Safety.NONE;
        Object sf = c.get("safety");
        if (sf instanceof Config t) {
            for (Config.Entry k : t.entrySet()) {
                if (!Set.of("ask_admin", "confirm_first").contains(k.getKey())) errors.add("safety: unknown key " + k.getKey());
            }
            safety = new Safety(kinds(t.get("ask_admin"), "safety.ask_admin", errors), kinds(t.get("confirm_first"), "safety.confirm_first", errors));
        } else if (sf != null) {
            errors.add("safety: a table [safety]");
        }
        if (!errors.isEmpty()) throw new Invalid(String.join("; ", errors));
        return new Policy(preset, Set.copyOf(owners), identity, Map.copyOf(agents), safety);
    }

    private static Set<String> kinds(Object v, String key, List<String> errors) {
        if (v == null) return Set.of();
        if (!(v instanceof List<?> list)) {
            errors.add(key + ": a list of command names or methods");
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object x : list) {
            String k = String.valueOf(x).strip().toLowerCase(Locale.ROOT);
            if (k.startsWith("/")) k = k.substring(1);
            if (k.isEmpty() || !k.matches("[a-z0-9_.:-]+")) errors.add(key + ": not a command name or method: " + x);
            else out.add(k);
        }
        return Set.copyOf(out);
    }

    private static Role role(Object v, String key, List<String> errors) {
        if (v == null) return null;
        Role r = Role.parse(String.valueOf(v));
        if (r == null) errors.add(key + ": guest, member, builder, admin or owner, not " + v);
        return r;
    }

    /** The file written on first start, with comments; {@code preset} chosen by {@link Policies}. */
    public static String template(Preset preset) {
        return """
                # Almighty policy: who may make the game master do what. Edit by hand and run /almighty reload, or ask
                # the game master to change it (when you, the owner, ask, it applies at once). A file with a mistake is
                # refused with the exact error, and the previous policy stays.

                # private: only owners talk to the agents; they may do everything their ceiling allows (as before 0.5).
                # friends: anyone's text may reach the agents; unasked, they act as a member would.
                # public:  unasked, the agents act as a guest would; players' requests run with the player's rights.
                preset = "%s"

                # Owners by UUID (in game: /almighty whoami). Names are never trusted: anyone can take a name offline.
                owners = []

                # Which UUIDs count as verified: auto (Mojang and Floodgate), mojang, proxy (Velocity forwarding), offline.
                identity = "auto"

                # Each agent: ceiling = the most it may ever do (guest, member, builder, admin, owner); floor = what it may
                # do unasked (derived from the preset unless set, never above the ceiling).
                [agents.gm]
                ceiling = "owner"

                [safety]
                # The only kinds of request that may reach an admin when the player lacks the right (command names such as
                # "kick", or methods such as "build"); batched into one quiet line every 10 minutes. Empty: none ever.
                ask_admin = []
                # Kinds the requesting player must confirm even when allowed and reversible (for example "tp").
                confirm_first = []
                """.formatted(preset.id());
    }
}
