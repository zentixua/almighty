package ua.zentix.almighty.feed;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Server-selected fallbacks let unmodified clients read the mod's translations. */
public final class GmMessages {
    private static final JsonObject ENGLISH = load("en_us");
    private static final JsonObject RUSSIAN = load("ru_ru");

    private GmMessages() {}

    static Component unavailable(String language) {
        return get("almighty.gm.unavailable", language);
    }

    /** A translated line: the client's own translation if it has the mod, else English or Russian by its language. */
    public static Component get(String key, String language, Object... args) {
        JsonObject table = language.toLowerCase(Locale.ROOT).startsWith("ru_") ? RUSSIAN : ENGLISH;
        String fallback = table.has(key) ? table.get(key).getAsString() : ENGLISH.get(key).getAsString();
        return Component.translatableWithFallback(key, fallback, args);
    }

    /** The server's own text for a key (English or Russian by {@code language}), without arguments filled in. */
    public static String raw(String key, String language) {
        JsonObject table = language.toLowerCase(Locale.ROOT).startsWith("ru_") ? RUSSIAN : ENGLISH;
        return table.has(key) ? table.get(key).getAsString() : ENGLISH.get(key).getAsString();
    }

    /** A player's client language ({@code en_us} when unknown). */
    public static String language(net.minecraft.server.level.ServerPlayer player) {
        return player == null ? "en_us" : player.clientInformation().language();
    }

    private static JsonObject load(String language) {
        String path = "/assets/almighty/lang/" + language + ".json";
        var stream = GmMessages.class.getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Missing language resource: " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read language resource: " + path, e);
        }
    }
}
