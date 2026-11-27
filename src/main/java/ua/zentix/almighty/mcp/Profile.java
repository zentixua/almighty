package ua.zentix.almighty.mcp;

import java.util.Locale;

/**
 * Which tools a connection sees: {@code full} (the 14 tools) or {@code compact} (6 tools with one-line descriptions,
 * for local models with small context windows). Chosen by the connection URL ({@code /mcp?profile=compact}), so each
 * harness entry picks its own without a server setting.
 */
public enum Profile {
    FULL, COMPACT;

    public static Profile parse(String value) {
        if (value == null || value.isBlank()) return FULL;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "full" -> FULL;
            case "compact" -> COMPACT;
            default -> throw new IllegalArgumentException("profile is full or compact, not " + value);
        };
    }
}
