package ua.zentix.almighty.neoforge;

import com.electronwill.nightconfig.core.Config;
import org.junit.jupiter.api.Test;
import ua.zentix.almighty.platform.Platform;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** NeoForge mod config trees become dotted keys for the catalog (the catalog then hides secret-looking values). */
class NeoForgeFlattenTest {
    @Test
    void nestedSectionsBecomeDottedKeys() {
        Config config = Config.inMemory();
        config.set(List.of("discord", "bot_token"), "s3cr3t");
        config.set(List.of("discord", "channel"), "general");
        config.set(List.of("port"), 25565);
        List<Platform.ConfigValue> values = new ArrayList<>();
        NeoForgePlatform.flatten("mymod", "mymod-common.toml", "", config, values);
        assertEquals(List.of(
                new Platform.ConfigValue("mymod", "mymod-common.toml", "discord.bot_token", "s3cr3t"),
                new Platform.ConfigValue("mymod", "mymod-common.toml", "discord.channel", "general"),
                new Platform.ConfigValue("mymod", "mymod-common.toml", "port", 25565)),
                values.stream().sorted((a, b) -> a.key().compareTo(b.key())).toList());
    }
}
