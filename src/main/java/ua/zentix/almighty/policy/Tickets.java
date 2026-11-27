package ua.zentix.almighty.policy;

import ua.zentix.almighty.bridge.RpcException;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Request tickets (plan 4.6): minted by the server only for a message a player addressed to the game master, bound to
 * (player UUID, agent, feed event, time), valid {@value #TTL_MS} ms and never extended. A ticket says who asked; it
 * never raises rights (step 5 runs a ticketed call with at most the requester's rights). Unguessable: 128 random
 * bits. Thread-safe: minted on the server thread, checked on bridge threads.
 */
public final class Tickets {
    public static final long TTL_MS = 10 * 60_000;
    static final int MAX = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Ticket(String id, UUID player, String name, String agent, long seq, long created, boolean verified) {
        public long expires() {
            return created + TTL_MS;
        }
    }

    private final LongSupplier clockMillis;
    private final Map<String, Ticket> byId = new ConcurrentHashMap<>();

    public Tickets(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** A fresh unguessable id; bind it with {@link #mint} once the event has its sequence number. */
    public static String newId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public Ticket mint(String id, UUID player, String name, String agent, long seq, boolean verified) {
        long now = clockMillis.getAsLong();
        if (byId.size() >= MAX) {
            byId.values().removeIf(t -> t.expires() < now);
            // Still full: drop the oldest, so a flood cannot grow the table.
            while (byId.size() >= MAX) {
                byId.values().stream().min(java.util.Comparator.comparingLong(Ticket::created))
                        .ifPresent(t -> byId.remove(t.id()));
            }
        }
        Ticket t = new Ticket(id, player, name, agent, seq, now, verified);
        byId.put(id, t);
        return t;
    }

    /** The ticket if it exists, has not expired and was minted for this agent; otherwise 403 with the reason. */
    public Ticket check(String id, String agent) throws RpcException {
        Ticket t = byId.get(id);
        if (t == null) throw new RpcException(403, "ticket", "Unknown ticket (tickets end with a server restart): ask the player to repeat");
        if (t.expires() < clockMillis.getAsLong()) {
            byId.remove(id);
            throw new RpcException(403, "ticket", "The ticket expired (10 minutes): ask the player to repeat");
        }
        if (!t.agent().equals(agent)) throw new RpcException(403, "ticket", "The ticket was minted for agent " + t.agent());
        return t;
    }

    public int size() {
        return byId.size();
    }
}
