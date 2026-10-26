package ua.zentix.almighty.feed;

import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Server-selected fallbacks let unmodified clients read the mod's translations. */
final class GmMessages {
    private static final String UNAVAILABLE = "almighty.gm.unavailable";
    private static final String ENGLISH = load("en_us");
    private static final String RUSSIAN = load("ru_ru");

    private GmMessages() {}

    static Component unavailable(String language) {
        String fallback = language.toLowerCase(Locale.ROOT).startsWith("ru_") ? RUSSIAN : ENGLISH;
        return Component.translatableWithFallback(UNAVAILABLE, fallback);
    }

    private static String load(String language) {
        String path = "/assets/almighty/lang/" + language + ".json";
        var stream = GmMessages.class.getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Missing language resource: " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject().get(UNAVAILABLE).getAsString();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read language resource: " + path, e);
        }
    }
}
