package ua.zentix.almighty.notes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Agent notes from mods and datapacks: {@code data/<namespace>/almighty/notes/<name>.md}, note id
 * {@code <namespace>:<name>}. A mod describes its use (commands, rules, pitfalls) without depending on Almighty:
 * without the bridge, the file simply remains in data. A datapack overrides a mod note at the same path, like
 * any data resource; an empty file removes it. Read current server data, refreshed after {@code /reload}.
 */
public final class Notes {
    static final String DIR = "almighty/notes";
    private static final String EXT = ".md";

    private Notes() {}

    public static JsonArray read(ResourceManager resources) {
        Map<ResourceLocation, Resource> files = new TreeMap<>(resources.listResources(DIR, file -> file.getPath().endsWith(EXT)));
        JsonArray out = new JsonArray();
        files.forEach((file, resource) -> {
            String path = file.getPath();
            JsonObject note = new JsonObject();
            note.addProperty("id", file.withPath(path.substring(DIR.length() + 1, path.length() - EXT.length())).toString());
            note.addProperty("source", resource.sourcePackId());
            try (BufferedReader reader = resource.openAsReader()) {
                String text = reader.lines().collect(Collectors.joining("\n")).strip();
                if (text.isEmpty()) return;
                note.addProperty("text", text);
            } catch (IOException e) {
                note.addProperty("error", e.toString());
            }
            out.add(note);
        });
        return out;
    }
}
