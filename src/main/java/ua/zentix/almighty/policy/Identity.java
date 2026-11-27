package ua.zentix.almighty.policy;

import java.util.Locale;
import java.util.UUID;

/**
 * Whether a player's identity is verified (plan 4.6). Mojang accounts have version-4 UUIDs, including behind a proxy
 * with modern forwarding; Floodgate (Geyser) players have UUIDs starting with sixteen zero digits and are
 * Xbox-authenticated. Offline-mode UUIDs are version 3, derived from the name, so anyone can claim one: such
 * players are never more than guests (step 5) unless the owner chooses otherwise.
 */
public final class Identity {
    public enum Mode {
        /** Version 4 and Floodgate verified, the rest not. */
        AUTO,
        /** Only version-4 (Mojang) UUIDs verified. */
        MOJANG,
        /** Like auto: the proxy (Velocity modern forwarding) passes Mojang UUIDs through. */
        PROXY,
        /** The owner vouches for every name (an offline LAN of friends): everyone verified. */
        OFFLINE;

        public static Mode parse(String s) {
            return valueOf(s.toUpperCase(Locale.ROOT));
        }
    }

    private static volatile Mode mode = Mode.AUTO;

    private Identity() {}

    public static Mode mode() {
        return mode;
    }

    public static void mode(Mode m) {
        mode = m;
    }

    /** A Floodgate (Bedrock through Geyser) player: the top 64 bits are zero. */
    public static boolean bedrock(UUID id) {
        return id.getMostSignificantBits() == 0;
    }

    public static boolean verified(UUID id) {
        return verified(id, mode);
    }

    public static boolean verified(UUID id, Mode mode) {
        return switch (mode) {
            case OFFLINE -> true;
            case MOJANG -> id.version() == 4;
            case AUTO, PROXY -> id.version() == 4 || bedrock(id);
        };
    }
}
