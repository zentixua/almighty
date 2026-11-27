package ua.zentix.almighty;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Game master bridge settings: {@code config/almighty-common.toml} in the server directory (each Crafty server has its own).
 * The bridge token is a separate file alongside it ({@link ua.zentix.almighty.bridge.Token}).
 */
public final class GmConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue BRIDGE_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> BRIDGE_HOST;
    public static final ModConfigSpec.IntValue BRIDGE_PORT;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> BRIDGE_ALLOWED_ORIGINS;
    public static final ModConfigSpec.ConfigValue<String> BRIDGE_PUBLIC_URL;
    public static final ModConfigSpec.DoubleValue WORK_MS_PER_TICK;
    public static final ModConfigSpec.IntValue BUILD_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_CHUNKS;
    public static final ModConfigSpec.IntValue LAG_MS;
    public static final ModConfigSpec.IntValue AUDIT_DAYS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> FEED_NAMES;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> FEED_TYPO_NAMES;
    public static final ModConfigSpec.IntValue FEED_GM_MESSAGES;
    public static final ModConfigSpec.ConfigValue<String> FEED_DEFAULT_AGENT;
    public static final ModConfigSpec.IntValue FEED_AWAY_SECONDS;
    public static final ModConfigSpec.BooleanValue SCRIPTS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> NAME;
    public static final ModConfigSpec.IntValue BOTS_MAX;
    public static final ModConfigSpec.ConfigValue<String> BOTS_MARKER;
    public static final ModConfigSpec.BooleanValue BOTS_ALLOW_DISGUISE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("bridge");
        BRIDGE_ENABLED = b.comment("HTTP bridge on a dedicated server (the integrated server of single-player never starts it).")
                .define("enabled", true);
        BRIDGE_HOST = b.comment("Address the bridge listens on. The bridge grants operator rights: use loopback or a network that only",
                        "the host can reach (for example, the address of a Docker container whose port is not published).")
                .define("host", "127.0.0.1");
        BRIDGE_PORT = b.comment("Bridge port.").defineInRange("port", 25650, 1, 65535);
        BRIDGE_ALLOWED_ORIGINS = b.comment("Browser origins allowed to call the bridge (for example \"https://example.org\"). Requests from a browser",
                        "page carry an Origin header and are refused unless it is listed here; agents and command-line clients send none.")
                .defineListAllowEmpty("allowed_origins", java.util.List.of(), () -> "", o -> o instanceof String);
        BRIDGE_PUBLIC_URL = b.comment("The bridge's public address when a reverse proxy or tunnel puts it on the internet, for example",
                        "\"https://mc.example.org\" (cloud agents such as claude.ai need public HTTPS). Sign-in links and the address the",
                        "agent is given use it. Empty: the bridge's own address.")
                .define("public_url", "");
        b.pop();
        b.push("work");
        WORK_MS_PER_TICK = b.comment("Time per server tick for builds, undos and image views, in ms.")
                .defineInRange("ms_per_tick", 3.0, 0.5, 20.0);
        BUILD_MAX_BLOCKS = b.comment("Maximum number of block positions in one build.")
                .defineInRange("build_max_blocks", 2_000_000, 1, 16_000_000);
        MAX_CHUNKS = b.comment("How many chunks the game master keeps loaded at once (builds and prepared areas, including",
                        "the ring of neighbouring chunks).")
                .defineInRange("max_chunks", 1500, 9, 4000);
        b.pop();
        b.push("feed");
        LAG_MS = b.comment("Emit a lag event when more than this many ms pass between two server ticks.")
                .defineInRange("lag_ms", 1000, 100, 60_000);
        FEED_NAMES = b.comment("Words that address the game master in public chat (any case; repeated letters are ignored). Empty: the",
                        "game master's name (name below).")
                .defineListAllowEmpty("names", java.util.List.of(), () -> "claude", o -> o instanceof String t && !t.isBlank());
        FEED_TYPO_NAMES = b.comment("Longer names also recognized with one typo (first letter exact). Use only names with no real word one",
                        "letter away.")
                .defineListAllowEmpty("typo_names", java.util.List.of(), () -> "claude", o -> o instanceof String t && !t.isBlank());
        FEED_GM_MESSAGES = b.comment("How many messages one player may address to the game master per hour; more are answered by the server",
                        "(\"slow down\") and never reach the agent.")
                .defineInRange("gm_messages", 20, 1, 10_000);
        FEED_DEFAULT_AGENT = b.comment("Agent that receives messages addressed to the game master; /gm @<agent> <text> picks another.")
                .define("default_agent", "gm");
        FEED_AWAY_SECONDS = b.comment("If the agent has not read a message this many seconds after it arrived, the server tells the player",
                        "the game master is away (the message is kept).")
                .defineInRange("away_seconds", 30, 5, 3600);
        b.pop();
        b.push("audit");
        AUDIT_DAYS = b.comment("Days of audit log kept in logs/almighty/audit-<date>.jsonl (every GM call with effects and every refusal).")
                .defineInRange("days", 30, 1, 3650);
        b.pop();
        b.push("scripts");
        SCRIPTS_ENABLED = b.comment("Game master scripts (Groovy): the script method and rules with a script. Turn off if a rule disrupts the",
                        "server: rules with a script saved with the world then do not load but stay in the file (rules without a script still load).")
                .define("enabled", true);
        b.pop();
        b.push("bots");
        BOTS_MAX = b.comment("How many bots (players controlled by the game master) can be on the server at once. A bot costs the server",
                        "as much as a player: it keeps the chunks around it loaded.")
                .defineInRange("max", 8, 0, 100);
        BOTS_MARKER = b.comment("Marker shown before a bot's name in the tab list and in chat. Empty: no marker.").define("marker", "[bot]");
        BOTS_ALLOW_DISGUISE = b.comment("Allow a bot to look like a player of this server: without the marker, with the name or skin of a player the server",
                        "knows (online, whitelisted, operator, or joined before). The bot still has its own UUID: when the real player joins,",
                        "the bot gives the name up.")
                .define("allow_disguise", false);
        b.pop();
        NAME = b.comment("Name the game master uses in chat and as the source of its commands.").define("name", "Claude");
        SPEC = b.build();
    }

    private GmConfig() {}

    public static long workNanosPerTick() {
        return (long) (WORK_MS_PER_TICK.get() * 1_000_000L);
    }
}
