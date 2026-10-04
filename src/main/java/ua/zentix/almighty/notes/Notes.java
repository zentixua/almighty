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
 * Памятки агенту от модов и датапаков: {@code data/<namespace>/almighty/notes/<имя>.md}, id памятки —
 * {@code <namespace>:<имя>}. Мод пишет там, как с ним работать (команды, правила, подводные камни), и от Almighty не
 * зависит: без моста файл просто лежит в данных. Датапак перекрывает памятку мода тем же путём, как любой ресурс данных,
 * а пустой файл её убирает. Читаются текущие данные сервера: после {@code /reload} — новые.
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
