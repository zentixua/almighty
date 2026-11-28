package ua.zentix.almighty.policy;

import org.junit.jupiter.api.Test;
import ua.zentix.almighty.bridge.RpcException;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tickets: bound to the agent they were minted for, 10 minutes, never extended, unguessable, bounded. */
class TicketsTest {
    private final AtomicLong now = new AtomicLong(1000);
    private final Tickets tickets = new Tickets(now::get);
    private final UUID alex = UUID.randomUUID();

    @Test
    void boundToAgentAndTime() throws Exception {
        String id = Tickets.newId();
        tickets.mint(id, alex, "Alex", "gm", 7, true);
        Tickets.Ticket t = tickets.check(id, "gm");
        assertEquals(alex, t.player());
        assertEquals(7, t.seq());
        assertEquals(403, assertThrows(RpcException.class, () -> tickets.check(id, "builder")).status(), "another agent's ticket");
        assertEquals(403, assertThrows(RpcException.class, () -> tickets.check(Tickets.newId(), "gm")).status(), "unknown");
        now.addAndGet(Tickets.TTL_MS - 1);
        tickets.check(id, "gm");
        now.addAndGet(2);
        assertThrows(RpcException.class, () -> tickets.check(id, "gm"), "expired: checking never extends it");
    }

    /** Lead's N6: a kept message routed by default follows the agent players are routed to; a named one never moves. */
    @Test
    void aKeptMessageFollowsTheRoutedAgent() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean away = new java.util.concurrent.atomic.AtomicBoolean(false);
        tickets.handover((t, agent) -> away.get() && agent.equals("claude-code"));
        String routed = Tickets.newId(), named = Tickets.newId();
        tickets.mint(routed, alex, "Alex", "gm", 7, true, false);
        tickets.mint(named, alex, "Alex", "gm", 8, true, true);
        assertThrows(RpcException.class, () -> tickets.check(routed, "claude-code"), "while gm may still answer");
        away.set(true);
        assertThrows(RpcException.class, () -> tickets.check(named, "claude-code"), "a message named for gm stays with gm");
        assertThrows(RpcException.class, () -> tickets.check(routed, "builder"), "only to the agent players are routed to");
        Tickets.Ticket moved = tickets.check(routed, "claude-code");
        assertEquals("claude-code", moved.agent());
        assertEquals(alex, moved.player());
        assertEquals("claude-code", tickets.peek(routed).agent(), "it is that agent's now");
        away.set(false);
        tickets.check(routed, "claude-code");
        assertThrows(RpcException.class, () -> tickets.check(routed, "gm"), "and no longer gm's");
        now.addAndGet(Tickets.TTL_MS + 1);
        assertThrows(RpcException.class, () -> tickets.check(routed, "claude-code"), "moving never extends it");
        assertTrue(tickets.peek(routed) == null);
    }

    @Test
    void unguessableAndBounded() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) assertTrue(ids.add(Tickets.newId()));
        assertEquals(32, Tickets.newId().length(), "128 bits");
        for (int i = 0; i < Tickets.MAX + 50; i++) tickets.mint(Tickets.newId(), alex, "Alex", "gm", i, true);
        assertTrue(tickets.size() <= Tickets.MAX);
    }
}
