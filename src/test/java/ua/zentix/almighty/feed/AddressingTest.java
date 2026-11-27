package ua.zentix.almighty.feed;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server-side addressing: the cases of the Claude channel's router tests, now for every harness. Cyrillic fixtures on purpose. */
class AddressingTest {
    private static final List<String> NAMES = List.of("клод", "клоду", "ведучий");
    private static final List<String> TYPO = List.of("ведущий", "ведущему", "claude");
    private final AtomicLong now = new AtomicLong();
    private final Addressing a = new Addressing(NAMES, TYPO, now::get);
    private final UUID sam = UUID.randomUUID();

    private static boolean named(String text) {
        Addressing x = new Addressing(NAMES, TYPO, () -> 0);
        return x.chat(UUID.randomUUID(), text, false) == Addressing.Via.NAME;
    }

    @Test
    void nameInAnyForm() {
        for (String t : List.of("Ведущий, привет", "ведууущииий", "ВЕДУЩИЙ!!!", "ведуший помоги", "вeдущему", "Привет, клод",
                "клоооод", "Claude?", "cluade", "ведучий, дай меч", "ведущму скажи")) assertTrue(named(t), t);
        for (String t : List.of("проведущий", "я веду машину", "они ведут бой", "везучий ты", "клад нашёл", "код красный",
                "сведущий человек")) assertFalse(named(t), t);
    }

    @Test
    void conversationWindow() {
        assertNull(a.chat(sam, "всем привет", false));
        assertEquals(Addressing.Via.NAME, a.chat(sam, "ведущему: дай меч", false));
        a.opened(sam);
        now.set(30_000);
        assertEquals(Addressing.Via.CONVERSATION, a.chat(sam, "и щит", false));
        now.set(300_000);
        assertNull(a.chat(sam, "ещё", false));
        a.replied(List.of(sam));
        now.set(300_000 + Addressing.CONVERSATION_MS - 1);
        assertEquals(Addressing.Via.CONVERSATION, a.chat(sam, "спасибо", false), "a reply opens a conversation");
        a.forget(sam);
        assertNull(a.chat(sam, "пока", false), "leaving ends it");
    }

    @Test
    void aloneOnTheServer() {
        assertEquals(Addressing.Via.ALONE, a.chat(sam, "кто-нибудь тут?", true));
    }

    @Test
    void perPlayerLimitBeforeMinting() {
        for (int i = 0; i < 20; i++) assertTrue(a.allow(sam, 20));
        assertFalse(a.allow(sam, 20), "21st message in an hour is refused");
        assertTrue(a.allow(UUID.randomUUID(), 20), "per player");
        now.addAndGet(Addressing.HOUR_MS);
        assertTrue(a.allow(sam, 20), "the window slides");
    }
}
