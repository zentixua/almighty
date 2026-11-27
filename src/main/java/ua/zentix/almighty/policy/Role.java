package ua.zentix.almighty.policy;

import java.util.Locale;

/** The role ladder (plan 4.4) used when no permission plugin answers: each role has every right of those below it. */
public enum Role {
    GUEST, MEMBER, BUILDER, ADMIN, OWNER;

    public boolean atLeast(Role other) {
        return compareTo(other) >= 0;
    }

    public static Role min(Role a, Role b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** A role by name; null if there is none. */
    public static Role parse(String s) {
        try {
            return valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The permission level a command runs with for this role (vanilla's requires checks decide). */
    public int commandLevel() {
        return switch (this) {
            case OWNER -> 4;
            case ADMIN -> 3;
            case BUILDER -> 2;
            case MEMBER, GUEST -> 0;
        };
    }
}
