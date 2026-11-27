package ua.zentix.almighty.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Config keys that look like secrets never show their value in the catalog. */
class CatalogTest {
    @Test
    void secretLookingKeysAreHidden() {
        for (String key : List.of("discord.bot_token", "webhookUrl", "db.password", "api_key", "apiKey", "auth.header", "client_secret", "privateKey"))
            assertTrue(Catalog.SECRET.matcher(key).matches(), key);
        for (String key : List.of("bridge.port", "bots.max", "work.ms_per_tick", "name"))
            assertFalse(Catalog.SECRET.matcher(key).matches(), key);
    }
}
