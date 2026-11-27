package ua.zentix.almighty.knowledge;

import ua.zentix.almighty.platform.Platform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Config keys that look like secrets never show their value in the catalog. */
class CatalogTest {
    @Test
    void flattenedConfigHidesSecretValues() {
        // As a loader lists them (NeoForge: NeoForgePlatform.flatten, tested in NeoForgeFlattenTest).
        List<Platform.ConfigValue> values = List.of(
                new Platform.ConfigValue("mymod", "mymod-common.toml", "discord.bot_token", "s3cr3t"),
                new Platform.ConfigValue("mymod", "mymod-common.toml", "discord.channel", "general"),
                new Platform.ConfigValue("mymod", "mymod-common.toml", "port", 25565));
        List<Catalog.Entry> out = Catalog.configEntries(values);
        assertEquals(3, out.size());
        for (Catalog.Entry e : out) {
            String value = e.show().get("value").getAsString();
            switch (e.id()) {
                case "mymod:discord.bot_token" -> assertEquals("(hidden: looks like a secret)", value);
                case "mymod:discord.channel" -> assertEquals("general", value);
                case "mymod:port" -> assertEquals("25565", value);
                default -> throw new AssertionError(e.id());
            }
            assertFalse(e.show().toString().contains("s3cr3t"));
        }
    }

    @Test
    void secretLookingKeysAreHidden() {
        for (String key : List.of("discord.bot_token", "webhookUrl", "db.password", "api_key", "apiKey", "auth.header", "client_secret", "privateKey", "license_key", "pwd", "session_id", "database.url"))
            assertTrue(Catalog.SECRET.matcher(key).matches(), key);
        for (String key : List.of("bridge.port", "bots.max", "work.ms_per_tick", "name", "author"))
            assertFalse(Catalog.SECRET.matcher(key).matches(), key);
    }
}
