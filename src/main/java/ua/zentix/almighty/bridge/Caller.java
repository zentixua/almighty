package ua.zentix.almighty.bridge;

/**
 * Who is making a call: the authenticated agent and the network address it came from. The bridge builds it from the
 * request (never from parameters); later steps add the origin and the requester's ticket. {@link #INTERNAL} is an
 * in-process call (GameTest, the server itself), not reachable over the network.
 */
public record Caller(Agent agent, String address) {
    public static final Caller INTERNAL = new Caller(null, "internal");

    /** The agent's name, or {@code internal}. */
    public String name() {
        return agent == null ? "internal" : agent.name();
    }
}
