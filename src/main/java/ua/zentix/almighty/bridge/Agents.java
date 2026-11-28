package ua.zentix.almighty.bridge;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Agent credentials. The agent {@value #DEFAULT} uses {@code config/almighty/token} (the file every existing setup
 * reads); every other agent {@code <name>} has {@code config/almighty/agents/<name>.token}. Owners add agents with
 * {@code /almighty agent add <name>} or by dropping a token file there and running {@code /almighty reload};
 * {@code /almighty revoke <name>} rotates a token. Thread-safe: the bridge reads the current table without locking.
 */
public final class Agents {
    public static final String DEFAULT = "gm";
    /** Agent names: lowercase letters, digits, {@code -} and {@code _}, as they appear in file names and policy keys. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Logger LOG = LogUtils.getLogger();

    private final Path defaultFile;
    private final Path dir;
    private volatile Map<String, Agent> byName = Map.of();

    private Agents(Path defaultFile, Path dir) {
        this.defaultFile = defaultFile;
        this.dir = dir;
    }

    /** Agents of a config directory ({@code config/almighty}); creates the {@value #DEFAULT} token if absent. */
    public static Agents load(Path configDir) throws IOException {
        Agents agents = new Agents(configDir.resolve("token"), configDir.resolve("agents"));
        agents.reload();
        return agents;
    }

    /** Fixed agents for tests: name → token, no files. */
    public static Agents of(Map<String, String> tokens) {
        Agents agents = new Agents(null, null);
        Map<String, Agent> m = new LinkedHashMap<>();
        tokens.forEach((name, token) -> m.put(name, new Agent(name, Token.of(token), null)));
        agents.byName = java.util.Collections.unmodifiableMap(m);
        return agents;
    }

    /** Re-read the token files: added files become agents, removed files stop working. */
    public synchronized void reload() throws IOException {
        if (defaultFile == null) return;
        Map<String, Agent> m = new LinkedHashMap<>();
        m.put(DEFAULT, keep(DEFAULT, Token.load(defaultFile), defaultFile));
        if (Files.isDirectory(dir)) {
            List<Path> sorted = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.token")) {
                files.forEach(sorted::add);
            }
            sorted.sort(null);
            {
                for (Path file : sorted) {
                    String name = file.getFileName().toString().replaceFirst("\\.token$", "");
                    if (!NAME.matcher(name).matches() || name.equals(DEFAULT)) {
                        LOG.warn("Almighty: ignoring agent token file {} (name must match {} and not be {})", file, NAME, DEFAULT);
                        continue;
                    }
                    String text;
                    try {
                        text = Files.readString(file).strip();
                    } catch (IOException e) {
                        // One unreadable file must not stop the bridge (or leave the table stale after a change).
                        LOG.warn("Almighty: ignoring agent token file {}: {}", file, e.toString());
                        continue;
                    }
                    if (text.length() < 32) {
                        LOG.warn("Almighty: ignoring agent token file {}: the token must be at least 32 characters", file);
                        continue;
                    }
                    Token token = Token.of(text);
                    String twin = m.entrySet().stream().filter(x -> x.getValue().token().equals(token)).map(Map.Entry::getKey)
                            .findFirst().orElse(null);
                    if (twin != null) {
                        LOG.warn("Almighty: ignoring agent token file {}: same token as agent {}", file, twin);
                        continue;
                    }
                    m.put(name, keep(name, token, file));
                }
            }
        }
        byName = java.util.Collections.unmodifiableMap(m);
    }

    /** Keep the existing agent object (and its presence) when its token did not change. */
    private Agent keep(String name, Token token, Path file) {
        Agent old = byName.get(name);
        return old != null && old.token().equals(token) ? old : new Agent(name, token, file);
    }

    /** The agent whose token the header carries; null if none. Compares against every agent in constant time each. */
    public Agent authenticate(String header) {
        byte[] given = Token.bearer(header);
        if (given == null) return null;
        Agent found = null;
        for (Agent a : byName.values()) {
            if (a.token().matches(given)) found = a;
        }
        return found;
    }

    public Agent get(String name) {
        return byName.get(name);
    }

    public Collection<Agent> list() {
        return byName.values();
    }

    /** Create a new agent with a fresh token file; its path is returned (the token is never shown). */
    public synchronized Path add(String name) throws IOException {
        check(name);
        if (byName.containsKey(name)) throw new IllegalArgumentException("Agent " + name + " already exists");
        Path file = dir.resolve(name + ".token");
        Token.rotate(file);
        reload();
        return file;
    }

    /** Remove an agent (its token file); {@value #DEFAULT} cannot be removed, only revoked. */
    public synchronized void remove(String name) throws IOException {
        Agent a = existing(name);
        if (name.equals(DEFAULT)) throw new IllegalArgumentException("Agent " + DEFAULT + " cannot be removed; use revoke to rotate its token");
        Files.deleteIfExists(a.file());
        reload();
    }

    /** Rotate an agent's token: the old one stops working at once; the new one is in the same file. */
    public synchronized Path revoke(String name) throws IOException {
        Agent a = existing(name);
        Token.rotate(a.file());
        reload();
        return a.file();
    }

    /**
     * Who gets players' messages (lead's F7): the configured agent ({@code feed.default_agent}) while it is present
     * (a call within {@code presentMs}); else the present agent seen last, so a freshly signed-in app hears players
     * without a config step; with nobody present, the configured agent if it exists, else the one seen last.
     */
    public String route(String configured, long presentMs) {
        Agent c = get(configured);
        if (c != null && c.present(presentMs)) return configured;
        Agent best = null;
        for (Agent a : list()) {
            if (a.lastSeenMillis() == 0) continue;
            if (best == null || a.lastSeenMillis() > best.lastSeenMillis()) best = a;
        }
        if (best != null && best.present(presentMs)) return best.name();
        if (c != null) return configured;
        return best != null ? best.name() : configured;
    }

    public List<String> names() {
        return new ArrayList<>(byName.keySet());
    }

    private Agent existing(String name) {
        Agent a = byName.get(name);
        if (a == null) throw new IllegalArgumentException("No agent " + name + "; agents: " + String.join(", ", byName.keySet()));
        if (a.file() == null) throw new IllegalArgumentException("Agent " + name + " has no token file");
        return a;
    }

    private void check(String name) {
        if (defaultFile == null) throw new IllegalStateException("Agents without files");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Agent name must be 1–32 characters: lowercase letters, digits, - and _");
        }
    }
}
