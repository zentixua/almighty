package ua.zentix.almighty.bridge;

import java.util.UUID;

/**
 * Who is making a call: the authenticated agent, the network address it came from and, when the call carries a
 * valid ticket ({@code for}), the player who asked. The bridge builds it from the request and the server's own
 * ticket table, never from parameters. {@link #INTERNAL} is an in-process call (GameTest, the server itself), not
 * reachable over the network.
 */
public record Caller(Agent agent, String address, UUID requester) {
    public static final Caller INTERNAL = new Caller(null, "internal", null);

    public Caller(Agent agent, String address) {
        this(agent, address, null);
    }

    /** The agent's name, or {@code internal}. */
    public String name() {
        return agent == null ? "internal" : agent.name();
    }

    public Caller withRequester(UUID player) {
        return new Caller(agent, address, player);
    }
}
