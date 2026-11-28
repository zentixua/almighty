package ua.zentix.almighty;

import ua.zentix.almighty.platform.Setting;

/**
 * Game master bridge settings: {@code config/almighty-common.toml} in the server directory (each Crafty server has its own).
 * The bridge token is a separate file alongside it ({@link ua.zentix.almighty.bridge.Token}).
 */
public final class GmConfig {
    /** Every setting in file order (the loader binds them to its config file). */
    public static final java.util.List<Setting<?>> ALL;

    public static final Setting<Boolean> BRIDGE_ENABLED;
    public static final Setting<String> BRIDGE_HOST;
    public static final Setting<Integer> BRIDGE_PORT;
    public static final Setting<java.util.List<? extends String>> BRIDGE_ALLOWED_ORIGINS;
    public static final Setting<String> BRIDGE_PUBLIC_URL;
    public static final Setting<String> BRIDGE_TLS_CERT;
    public static final Setting<String> BRIDGE_TLS_KEY;
    public static final Setting<Boolean> BRIDGE_PLAIN_OFF_LOOPBACK;
    public static final Setting<Boolean> BRIDGE_INTEGRATED;
    public static final Setting<Double> WORK_MS_PER_TICK;
    public static final Setting<Integer> BUILD_MAX_BLOCKS;
    public static final Setting<Integer> MAX_CHUNKS;
    public static final Setting<Integer> LAG_MS;
    public static final Setting<Integer> AUDIT_DAYS;
    public static final Setting<java.util.List<? extends String>> FEED_NAMES;
    public static final Setting<java.util.List<? extends String>> FEED_TYPO_NAMES;
    public static final Setting<Integer> FEED_GM_MESSAGES;
    public static final Setting<String> FEED_DEFAULT_AGENT;
    public static final Setting<Integer> FEED_AWAY_SECONDS;
    public static final Setting<Boolean> SCRIPTS_ENABLED;
    public static final Setting<String> NAME;
    public static final Setting<Integer> BOTS_MAX;
    public static final Setting<String> BOTS_MARKER;
    public static final Setting<Boolean> BOTS_ALLOW_DISGUISE;

    static {
        Setting.Declarer b = new Setting.Declarer();
        b.section("bridge");
        BRIDGE_ENABLED = b.bool("enabled", true, "HTTP bridge on a dedicated server (the integrated server of single-player never starts it).");
        BRIDGE_HOST = b.string("host", "127.0.0.1", "Address the bridge listens on. The bridge grants operator rights: use loopback or a network that only",
                        "the host can reach (for example, the address of a Docker container whose port is not published).");
        BRIDGE_PORT = b.integer("port", 25650, 1, 65535, "Bridge port.");
        BRIDGE_ALLOWED_ORIGINS = b.strings("allowed_origins", "", o -> o instanceof String, "Browser origins allowed to call the bridge (for example \"https://example.org\"). Requests from a browser",
                        "page carry an Origin header and are refused unless it is listed here; agents and command-line clients send none.");
        BRIDGE_PUBLIC_URL = b.string("public_url", "", "The bridge's public address when a reverse proxy or tunnel puts it on the internet, for example",
                        "\"https://mc.example.org\" (cloud agents such as claude.ai need public HTTPS). Sign-in links and the address the",
                        "agent is given use it. Empty: the bridge's own address.");
        BRIDGE_TLS_CERT = b.string("tls_cert", "", "HTTPS: a PEM certificate chain file (for example a reverse proxy's or Let's Encrypt's fullchain.pem), relative",
                        "to the server directory. With tls_key set, the bridge speaks only HTTPS. Empty: plain HTTP (fine on loopback).");
        BRIDGE_TLS_KEY = b.string("tls_key", "", "HTTPS: the matching unencrypted PKCS#8 PEM private key (BEGIN PRIVATE KEY, for example privkey.pem).");
        BRIDGE_PLAIN_OFF_LOOPBACK = b.bool("allow_plain_http_off_loopback", false, "Without TLS the bridge starts only on loopback (127.0.0.1, ::1, localhost): elsewhere the token would",
                        "cross the network in plain text, so a host such as 0.0.0.0 is refused. true: start anyway, for a network only this",
                        "machine reaches (a container's private address whose port is not published). Never on a public address.");
        BRIDGE_INTEGRATED = b.bool("integrated", false, "Also start the bridge on the integrated server of single-player and LAN worlds (for mod developers and",
                        "filming). Loopback only: with another host the bridge does not start there.");
        b.section("work");
        WORK_MS_PER_TICK = b.real("ms_per_tick", 3.0, 0.5, 20.0, "Time per server tick for builds, undos and image views, in ms.");
        BUILD_MAX_BLOCKS = b.integer("build_max_blocks", 2_000_000, 1, 16_000_000, "Maximum number of block positions in one build.");
        MAX_CHUNKS = b.integer("max_chunks", 1500, 9, 4000, "How many chunks the game master keeps loaded at once (builds and prepared areas, including",
                        "the ring of neighbouring chunks).");
        b.section("feed");
        LAG_MS = b.integer("lag_ms", 1000, 100, 60_000, "Emit a lag event when more than this many ms pass between two server ticks.");
        FEED_NAMES = b.strings("names", "claude", o -> o instanceof String t && !t.isBlank(), "Words that address the game master in public chat (any case; repeated letters are ignored). Empty: the",
                        "game master's name (name below).");
        FEED_TYPO_NAMES = b.strings("typo_names", "claude", o -> o instanceof String t && !t.isBlank(), "Longer names also recognized with one typo (first letter exact). Use only names with no real word one",
                        "letter away.");
        FEED_GM_MESSAGES = b.integer("gm_messages", 20, 1, 10_000, "How many messages one player may address to the game master per hour; more are answered by the server",
                        "(\"slow down\") and never reach the agent.");
        FEED_DEFAULT_AGENT = b.string("default_agent", "gm", "Agent that receives messages addressed to the game master while it has made a call in the last minute. Otherwise they go to the most recently active agent with an owner ceiling, if it called within the last minute; if none is, they wait for this agent. /gm @<agent> <text> picks another.");
        FEED_AWAY_SECONDS = b.integer("away_seconds", 30, 5, 3600, "If the agent has not read a message this many seconds after it arrived, the server tells the player",
                        "the game master is away (the message is kept).");
        b.section("audit");
        AUDIT_DAYS = b.integer("days", 30, 1, 3650, "Days of audit log kept in logs/almighty/audit-<date>.jsonl (every GM call with effects and every refusal).");
        b.section("scripts");
        SCRIPTS_ENABLED = b.bool("enabled", true, "Game master scripts (Groovy): the script method and rules with a script. Turn off if a rule disrupts the",
                        "server: rules with a script saved with the world then do not load but stay in the file (rules without a script still load).");
        b.section("bots");
        BOTS_MAX = b.integer("max", 8, 0, 100, "How many bots (players controlled by the game master) can be on the server at once. A bot costs the server",
                        "as much as a player: it keeps the chunks around it loaded.");
        BOTS_MARKER = b.string("marker", "[bot]", "Marker shown before a bot's name in the tab list and in chat. Empty: no marker.");
        BOTS_ALLOW_DISGUISE = b.bool("allow_disguise", false, "Allow a bot to look like a player of this server: without the marker, with the name or skin of a player the server",
                        "knows (online, whitelisted, operator, or joined before). The bot still has its own UUID: when the real player joins,",
                        "the bot gives the name up.");
        b.section("");
        NAME = b.string("name", "Claude", "Name the game master uses in chat and as the source of its commands.");
        ALL = b.all();
    }

    private GmConfig() {}

    public static long workNanosPerTick() {
        return (long) (WORK_MS_PER_TICK.get() * 1_000_000L);
    }
}
