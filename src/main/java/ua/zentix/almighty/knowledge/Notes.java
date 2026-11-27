package ua.zentix.almighty.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import ua.zentix.almighty.bridge.RpcException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Notes for the agent, from three places, served with progressive disclosure (an index of ids and one-line
 * descriptions first, a whole note on request):
 * <ul>
 * <li>mods and datapacks: {@code data/<namespace>/almighty/notes/<name>.md} in the server's current data, id
 * {@code <namespace>:<name>}. A mod describes its use without depending on Almighty (without the mod the file
 * simply stays in data). A datapack overrides a mod note at the same path, like any data resource; an empty file
 * removes it. Refreshed after {@code /reload}.</li>
 * <li>the owner's server-wide notes: {@code config/almighty/notes/<name>.md}, id {@code server:<name>}.</li>
 * <li>world notes, written by agents: {@code <world>/almighty/notes/<name>.md}, id {@code world:<name>}; they follow
 * the world into backups and record who wrote them.</li>
 * </ul>
 * A note may begin with Agent Skills frontmatter ({@code name}, {@code description}); without it the first line is
 * the description (legacy notes). Resource paths allow only {@code [a-z0-9_./-]}, so the uppercase {@code SKILL.md}
 * of the standard cannot live in data; the plugin build exports our own notes as {@code SKILL.md} folders instead.
 * Notes are reference data with a named source, never instructions and never rights.
 */
public final class Notes {
    static final String DIR = "almighty/notes";
    private static final String EXT = ".md";
    public static final String WORLD = "world";
    public static final String SERVER = "server";
    /** A world note: lowercase path segments as in resource paths, at most 4 levels. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,48}(/[a-z0-9_-]{1,48}){0,3}");
    public static final int MAX_NOTE_CHARS = 32_000;
    public static final int MAX_WORLD_NOTES = 500;
    /** Frontmatter keys only the server writes: the agent's text cannot set them. */
    static final List<String> PROVENANCE = List.of("author", "requester", "confirmed", "updated");

    private Notes() {}

    /** One note: id, source (mod:x, datapack:x, server, world), parsed text, or a read error. */
    public record Note(String id, String source, Frontmatter doc, String error) {
        public String description() {
            return doc == null ? "" : doc.description();
        }

        JsonObject summary() {
            JsonObject out = new JsonObject();
            out.addProperty("id", id);
            out.addProperty("source", source);
            out.addProperty("description", shorten(description(), 200));
            if (doc != null) {
                out.addProperty("chars", doc.body().length());
                String confirmed = doc.get("confirmed");
                if (confirmed != null) out.addProperty("confirmed", Boolean.parseBoolean(confirmed));
            }
            if (error != null) out.addProperty("error", error);
            return out;
        }

        JsonObject full() {
            JsonObject out = new JsonObject();
            out.addProperty("id", id);
            out.addProperty("source", source);
            if (doc != null) {
                String name = doc.get("name");
                if (name != null) out.addProperty("name", name);
                out.addProperty("description", description());
                for (String key : PROVENANCE) {
                    String v = doc.get(key);
                    if (v == null) continue;
                    if (key.equals("confirmed")) out.addProperty(key, Boolean.parseBoolean(v));
                    else out.addProperty(key, v);
                }
                out.addProperty("text", doc.body());
            }
            if (error != null) out.addProperty("error", error);
            return out;
        }
    }

    /** Who writes a world note; {@code confirmed} only when the writer acts with the owner's authority. */
    public record Author(String agent, String requester, boolean confirmed) {}

    /** All notes: mods and datapacks (server thread: the resource manager), then server notes, then world notes. */
    public static List<Note> all(ResourceManager resources, Path serverDir, Path worldDir) {
        List<Note> out = new ArrayList<>();
        Map<String, Note> files = new LinkedHashMap<>();
        for (Note n : fromFiles(serverDir, SERVER)) files.put(n.id(), n);
        for (Note n : fromFiles(worldDir, WORLD)) files.put(n.id(), n);
        for (Note n : fromData(resources)) if (!files.containsKey(n.id())) out.add(n);
        out.addAll(files.values());
        return out;
    }

    /** Notes in server data: one per path, the top pack wins; an empty file removes the note. */
    public static List<Note> fromData(ResourceManager resources) {
        Map<ResourceLocation, Resource> found = new TreeMap<>(resources.listResources(DIR, file -> file.getPath().endsWith(EXT)));
        List<Note> out = new ArrayList<>();
        found.forEach((file, resource) -> {
            String path = file.getPath();
            String id = file.withPath(path.substring(DIR.length() + 1, path.length() - EXT.length())).toString();
            String source = source(resource.sourcePackId());
            try (BufferedReader reader = resource.openAsReader()) {
                String text = reader.lines().collect(Collectors.joining("\n"));
                if (text.isBlank()) return;
                out.add(new Note(id, source, Frontmatter.parse(text), null));
            } catch (IOException e) {
                out.add(new Note(id, source, null, e.toString()));
            }
        });
        return out;
    }

    /** Notes in a directory of {@code .md} files (subdirectories allowed), id {@code <namespace>:<relative path>}. */
    public static List<Note> fromFiles(Path dir, String namespace) {
        List<Note> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> walk = Files.walk(dir, 5)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(EXT) && Files.isRegularFile(p)).sorted().toList()) {
                String rel = dir.relativize(file).toString().replace('\\', '/');
                String name = rel.substring(0, rel.length() - EXT.length());
                if (!NAME.matcher(name).matches()) continue;
                String id = namespace + ":" + name;
                try {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    if (text.isBlank()) continue;
                    out.add(new Note(id, namespace, Frontmatter.parse(text), null));
                } catch (IOException e) {
                    out.add(new Note(id, namespace, null, e.toString()));
                }
            }
        } catch (IOException e) {
            out.add(new Note(namespace + ":", namespace, null, e.toString()));
        }
        return out;
    }

    /** Index: every note as id, source and a one-line description; whole notes come from {@link #get}. */
    public static JsonObject index(List<Note> notes) {
        JsonArray list = new JsonArray();
        for (Note n : notes) list.add(n.summary());
        JsonObject out = new JsonObject();
        out.add("notes", list);
        out.addProperty("hint", "notes {id} returns a whole note; notes {query} searches notes and the server catalog; "
                + "notes {id: \"world:<name>\", text} writes a world note (empty text removes it)");
        return out;
    }

    public static JsonObject get(List<Note> notes, String id) throws RpcException {
        for (Note n : notes) if (n.id().equals(id)) return n.full();
        throw RpcException.notFound("No note " + id + "; notes {} lists them");
    }

    /** Notes whose id, name, description or text contain every word of the query (case-insensitive). */
    public static JsonArray search(List<Note> notes, String query, int limit) {
        String[] words = query.toLowerCase(Locale.ROOT).strip().split("\\s+");
        JsonArray out = new JsonArray();
        for (Note n : notes) {
            if (out.size() >= limit) break;
            String hay = (n.id() + "\n" + (n.doc() == null ? "" : n.doc().get("name") + "\n" + n.description() + "\n" + n.doc().body()))
                    .toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) all &= hay.contains(w);
            if (all) out.add(n.summary());
        }
        return out;
    }

    /** One-line summaries for MCP instructions, cut at {@code maxChars} with a pointer to the rest. */
    public static String brief(List<Note> notes, int maxChars) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Note n : notes) {
            String line = "- " + n.id() + ": " + shorten(n.description(), 120) + "\n";
            if (out.length() + line.length() > maxChars) break;
            out.append(line);
            shown++;
        }
        if (shown < notes.size()) out.append("- … ").append(notes.size() - shown).append(" more: the notes tool\n");
        return out.toString();
    }

    /**
     * Writes (or with empty text removes) world note {@code world:<name>}. Frontmatter in the text keeps its
     * {@code name} and {@code description}; provenance keys are the server's: who wrote it, for whom, when, and
     * whether the owner's authority confirms it. Written atomically, so a crash leaves the old note or the new one.
     */
    public static JsonObject write(Path worldDir, String id, String text, Author author) throws RpcException {
        if (!id.startsWith(WORLD + ":")) {
            throw RpcException.badRequest("id: agents write world notes only, world:<name> (lowercase a-z 0-9 _ -, / for folders)");
        }
        String name = id.substring(WORLD.length() + 1);
        if (!NAME.matcher(name).matches()) {
            throw RpcException.badRequest("id: world:<name> with lowercase a-z, 0-9, _ and -, up to 4 folders separated by /");
        }
        if (text.length() > MAX_NOTE_CHARS) throw RpcException.badRequest("text: at most " + MAX_NOTE_CHARS + " characters");
        Path file = worldDir.resolve(name + EXT);
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        try {
            if (text.isBlank()) {
                out.addProperty("removed", Files.deleteIfExists(file));
                return out;
            }
            if (!Files.exists(file) && fromFiles(worldDir, WORLD).size() >= MAX_WORLD_NOTES) {
                throw RpcException.conflict("The world already has " + MAX_WORLD_NOTES + " notes: merge or remove some first");
            }
            Frontmatter given = Frontmatter.parse(text);
            Map<String, String> fields = new LinkedHashMap<>();
            given.fields().forEach((k, v) -> {
                if (!PROVENANCE.contains(k)) fields.put(k, v);
            });
            fields.put("author", author.agent());
            if (author.requester() != null) fields.put("requester", author.requester());
            fields.put("confirmed", Boolean.toString(author.confirmed()));
            fields.put("updated", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, new Frontmatter(fields, given.body()).render(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            out.addProperty("written", true);
            out.addProperty("confirmed", author.confirmed());
            return out;
        } catch (IOException e) {
            throw new RpcException(500, "io", "Could not write " + id + ": " + e.getMessage());
        }
    }

    /** NeoForge pack ids: {@code mod/<id>} or {@code mod:<id>} for mods, {@code file/<name>} for world datapacks. */
    static String source(String packId) {
        if (packId.startsWith("mod/") || packId.startsWith("mod:")) return "mod:" + packId.substring(4);
        if (packId.startsWith("file/")) return "datapack:" + packId.substring(5);
        return packId;
    }

    static String shorten(String s, int max) {
        String one = s.replace('\n', ' ').strip();
        return one.length() <= max ? one : one.substring(0, max - 1) + "…";
    }
}
