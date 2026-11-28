package ua.zentix.almighty.feed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GmMessagesTest {
    @Test
    void unmodifiedClientsReceiveThePlayersLanguage() {
        assertEquals("Ведущий не запущен на этом сервере", GmMessages.unavailable("ru_ru").getString());
        assertEquals("Ведущий не запущен на этом сервере", GmMessages.unavailable("ru_RU").getString());
        assertEquals("The game master is not running on this server", GmMessages.unavailable("en_us").getString());
        assertEquals("The game master is not running on this server", GmMessages.unavailable("de_de").getString());
    }

    /** Every player-facing line exists in both languages with the same placeholders (the audit found prompts English-only). */
    @Test
    void bothLanguagesHaveEveryLineAndPlaceholder() throws Exception {
        com.google.gson.JsonObject en = read("en_us"), ru = read("ru_ru");
        assertEquals(en.keySet(), ru.keySet());
        for (String k : en.keySet()) {
            assertEquals(count(en.get(k).getAsString()), count(ru.get(k).getAsString()), k);
        }
        assertEquals("Сделано по вашей просьбе: /time set day ", GmMessages.get("almighty.receipt.done", "ru_ru", "/time set day").getString());
        assertEquals("Not allowed: x", GmMessages.get("almighty.outcome.not_allowed", "en_gb", "x").getString());
    }

    private static com.google.gson.JsonObject read(String lang) throws Exception {
        try (var in = GmMessagesTest.class.getResourceAsStream("/assets/almighty/lang/" + lang + ".json")) {
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static int count(String s) {
        int n = 0;
        for (int i = s.indexOf("%s"); i >= 0; i = s.indexOf("%s", i + 2)) n++;
        return n;
    }
}
