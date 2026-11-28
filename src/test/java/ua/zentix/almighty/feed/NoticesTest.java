package ua.zentix.almighty.feed;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lead's N2: "away" and "paused" are limited separately, so a pause right after an away notice is still told. */
class NoticesTest {
    @Test
    void anEarlierAwayNeverHidesAPause() {
        Notices n = new Notices(300_000);
        UUID boris = UUID.randomUUID();
        assertTrue(n.due(boris, "almighty.gm.away", 1_000), "the first away notice");
        assertFalse(n.due(boris, "almighty.gm.away", 60_000), "away again within 5 minutes stays quiet");
        assertTrue(n.due(boris, "almighty.gm.paused", 82_000), "the pause 70 s later is told");
        assertFalse(n.due(boris, "almighty.gm.paused", 90_000), "paused again within 5 minutes stays quiet");
        assertTrue(n.due(UUID.randomUUID(), "almighty.gm.away", 90_000), "per player");
        assertEquals(82_000, n.at(boris, null));
        assertEquals(1_000, n.at(boris, "almighty.gm.away"));
        assertTrue(n.due(boris, "almighty.gm.away", 301_000), "after 5 minutes away is told again");
        n.prune(1_000_000);
        assertEquals(0, n.at(boris, null), "pruned");
    }
}
