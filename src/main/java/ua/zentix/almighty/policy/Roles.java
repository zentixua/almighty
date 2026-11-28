package ua.zentix.almighty.policy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Roles granted on the ladder without a permission plugin (plan 4.4): {@code /almighty role <player> <role> [duration]},
 * kept in {@code config/almighty/roles.json}. A granter can give only roles below their own (the console counts as
 * owner); owner itself is only ever the policy's {@code owners}. With LuckPerms, use its groups and {@code settemp}
 * instead. Thread-safe.
 */
public final class Roles {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern DURATION = Pattern.compile("(\\d+)([smhd])");

    public record Grant(Role role, long until, String name, String by) {
        boolean live(long now) {
            return until == 0 || now < until;
        }
    }

    private final Path file;
    private final LongSupplier clockMillis;
    private final Map<UUID, Grant> grants = new ConcurrentHashMap<>();

    public Roles(Path file, LongSupplier clockMillis) {
        this.file = file;
        this.clockMillis = clockMillis;
        load();
    }

    /** The live granted role of a player, or null. */
    public Role of(UUID player) {
        Grant g = grants.get(player);
        return g != null && g.live(clockMillis.getAsLong()) ? g.role() : null;
    }

    public Grant grant(UUID player) {
        Grant g = grants.get(player);
        return g != null && g.live(clockMillis.getAsLong()) ? g : null;
    }

    /** Duration text such as {@code 3h}, {@code 30m}, {@code 2d}, {@code 1h30m}; null for an unparseable text. */
    public static Duration parse(String text) {
        Matcher m = DURATION.matcher(text.toLowerCase(java.util.Locale.ROOT));
        long seconds = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return null;
            long n = Long.parseLong(m.group(1));
            seconds += switch (m.group(2)) {
                case "s" -> n;
                case "m" -> n * 60;
                case "h" -> n * 3600;
                default -> n * 86400;
            };
            end = m.end();
        }
        return end == text.length() && seconds > 0 ? Duration.ofSeconds(seconds) : null;
    }

    /**
     * Give {@code player} {@code role} ({@code null} clears) for {@code duration} (null: until cleared), by a granter of
     * {@code granterRole}. Returns null, or why not.
     */
    public synchronized String set(UUID player, String name, Role role, Duration duration, Role granterRole, String by) {
        if (role == Role.OWNER) return "owners are listed in policy.toml (owners), never granted";
        Grant old = grant(player);
        if (old != null && !granterRole.atLeast(Role.OWNER) && old.role().compareTo(granterRole) >= 0) {
            return name + " is " + old.role().id() + ": only someone above that can change it";
        }
        if (role != null && role.compareTo(granterRole) >= 0) return "you can give only roles below your own (" + granterRole.id() + ")";
        if (role == null) grants.remove(player);
        else grants.put(player, new Grant(role, duration == null ? 0 : clockMillis.getAsLong() + duration.toMillis(), name, by));
        // Only a real change: cached rights (rules, offline answers) drop on every bump.
        Policies.CHANGES.incrementAndGet();
        save();
        return null;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : o.entrySet()) {
                JsonObject g = e.getValue().getAsJsonObject();
                Role r = Role.parse(g.get("role").getAsString());
                if (r == null || r == Role.OWNER) continue;
                grants.put(UUID.fromString(e.getKey()), new Grant(r, g.has("until") ? g.get("until").getAsLong() : 0,
                        g.has("name") ? g.get("name").getAsString() : "", g.has("by") ? g.get("by").getAsString() : ""));
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("Almighty: cannot read {} (granted roles do not apply until it is fixed): {}", file, e.toString());
        }
    }

    private void save() {
        if (file == null) return;
        JsonObject o = new JsonObject();
        long now = clockMillis.getAsLong();
        grants.forEach((id, g) -> {
            if (!g.live(now)) return;
            JsonObject j = new JsonObject();
            j.addProperty("role", g.role().id());
            if (g.until() != 0) j.addProperty("until", g.until());
            j.addProperty("name", g.name());
            j.addProperty("by", g.by());
            o.add(id.toString(), j);
        });
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Almighty: cannot write {}: {}", file, e.toString());
        }
    }
}
