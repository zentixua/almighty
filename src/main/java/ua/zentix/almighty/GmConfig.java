package ua.zentix.almighty;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Настройки моста ведущего: {@code config/almighty-common.toml} в каталоге сервера (у каждого сервера Crafty свой).
 * Токен моста — отдельным файлом рядом ({@link ua.zentix.almighty.bridge.Token}).
 */
public final class GmConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue BRIDGE_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> BRIDGE_HOST;
    public static final ModConfigSpec.IntValue BRIDGE_PORT;
    public static final ModConfigSpec.DoubleValue WORK_MS_PER_TICK;
    public static final ModConfigSpec.IntValue BUILD_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_CHUNKS;
    public static final ModConfigSpec.IntValue LAG_MS;
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
                        "the bot gives the name up. The server owner decides.")
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
