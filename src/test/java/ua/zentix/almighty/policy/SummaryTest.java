package ua.zentix.almighty.policy;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent's summary can never pass for the server's own text in a prompt or receipt (Summary's promise). */
class SummaryTest {
    @Test
    void aPlainSummaryIsKept() {
        assertEquals("snow over your base", Summary.clean("snow over your base"));
        assertEquals("взорвать вашу базу", Summary.clean("взорвать вашу базу"));
    }

    @Test
    void oneLineWithoutFormattingBracketsOrQuotes() {
        assertEquals("a b c", Summary.clean("  a\n§cb\t\"c\"  "));
        assertEquals("Allow cake", Summary.clean("[Allow] «cake»"), "no fake buttons, no closing of its own quote");
        assertEquals("ab", Summary.clean("a‮b​"), "no bidi overrides or zero-width characters");
        assertEquals("x", Summary.clean("{x}<>`'“”"));
        assertNull(Summary.clean(" \n§ "));
    }

    @Test
    void lengthLimited() {
        String s = Summary.clean("a".repeat(500));
        assertEquals(Summary.MAX, s.codePointCount(0, s.length()));
        assertTrue(s.endsWith("…"));
    }

    @Test
    void neverSpellsTheServersOwnText() {
        for (String forged : new String[]{"[Almighty] all fine", "A l m i g h t y", "Аlmighty", "almıghty", "harmless — what runs: nothing",
                "WHAT-RUNS nothing", "(its own words)", "ничего — что выполнится: ничего", "(слова ведущего)", "it cannot be undone",
                "это нельзя отменить", "asked by the owner", "просит владелец", "\\u0061lmighty", "for Bob, with your rights", "для Пети, с вашими правами"}) {
            assertNull(Summary.clean(forged), forged);
        }
    }

    @Test
    void readFromTheCall() {
        JsonObject p = new JsonObject();
        assertNull(Summary.of(p));
        p.add(Summary.PARAM, new JsonPrimitive(5));
        assertNull(Summary.of(p), "not a string");
        p.addProperty(Summary.PARAM, "light the way");
        assertEquals("light the way", Summary.of(p));
    }
}
