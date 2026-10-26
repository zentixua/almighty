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
}
