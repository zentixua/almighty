package ua.zentix.almighty.bridge;

import java.nio.file.Path;

/**
 * An agent: one credential with a name. Each agent connects with its own bearer token; later steps give each its
 * own ceiling, floor and event cursor. The name is the identity used in logs, audit and policy; the token never is.
 */
public final class Agent {
    private final String name;
    private final Token token;
    /** Where the token is kept; null for agents made in tests. */
    private final Path file;
    private volatile long lastSeenMillis;
    private volatile String lastAddress;

    Agent(String name, Token token, Path file) {
        this.name = name;
        this.token = token;
        this.file = file;
    }

    public String name() {
        return name;
    }

    Token token() {
        return token;
    }

    public Path file() {
        return file;
    }

    /** Time of the last authenticated call (epoch ms), 0 if none since start: the agent's presence. */
    public long lastSeenMillis() {
        return lastSeenMillis;
    }

    public String lastAddress() {
        return lastAddress;
    }

    void seen(String address) {
        lastAddress = address;
        lastSeenMillis = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return name;
    }
}
