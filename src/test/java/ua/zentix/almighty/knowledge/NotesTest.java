package ua.zentix.almighty.knowledge;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ua.zentix.almighty.bridge.RpcException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Notes as the server loads them from real packs: a datapack overrides a mod note at the same path, an empty file
 * removes it, Agent Skills frontmatter gives the description, and legacy notes without it still read. World notes
 * carry provenance the agent's text cannot forge.
 */
class NotesTest {
    @TempDir
    Path tmp;

    private Path pack(String name, Map<String, String> notes) throws IOException {
        Path root = tmp.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"" + name + "\"}}");
        for (var e : notes.entrySet()) {
            Path file = root.resolve("data/" + e.getKey().replace(":", "/almighty/notes/") + ".md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
        }
        return root;
    }

    private static PackResources resources(String id, Path root) {
        return new PathPackResources(new PackLocationInfo(id, Component.literal(id), PackSource.DEFAULT, Optional.empty()), root);
    }

    @Test
    void datapackOverridesModNoteAndEmptyFileRemovesIt() throws IOException {
        Path mod = pack("mod", Map.of(
                "planes:flying", "---\nname: flying\ndescription: How to fly the planes mod's aircraft\n---\n\n# Flying\nHold jump.",
                "planes:legacy", "Legacy note: first line is the description.\nMore text.",
                "planes:gone", "This note is removed by the datapack."));
        Path datapack = pack("datapack", Map.of(
                "planes:flying", "---\ndescription: \"Server rule: no flying over spawn\"\n---\nOverridden.",
                "planes:gone", ""));
        // The last pack is on top, as world datapacks are above mods.
        try (MultiPackResourceManager rm = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(resources("mod/planes", mod), resources("file/rules", datapack)))) {
            List<Notes.Note> notes = Notes.fromData(rm);
            assertEquals(List.of("planes:flying", "planes:legacy"), notes.stream().map(Notes.Note::id).toList());
            Notes.Note flying = notes.get(0);
            assertEquals("datapack:rules", flying.source());
            assertEquals("Server rule: no flying over spawn", flying.description());
            assertEquals("Overridden.", flying.doc().body());
            Notes.Note legacy = notes.get(1);
            assertEquals("mod:planes", legacy.source());
            assertEquals("Legacy note: first line is the description.", legacy.description());
        }
    }

    @Test
    void indexIsShortAndGetReturnsTheWholeNote() throws RpcException {
        String body = "x".repeat(5000);
        List<Notes.Note> notes = List.of(new Notes.Note("a:b", "mod:a", Frontmatter.parse("---\ndescription: d\n---\n" + body), null));
        JsonObject index = Notes.index(notes);
        assertFalse(index.toString().contains(body), "the index never carries note bodies");
        assertEquals(5000, index.getAsJsonArray("notes").get(0).getAsJsonObject().get("chars").getAsInt());
        assertEquals(body, Notes.get(notes, "a:b").get("text").getAsString());
        assertThrows(RpcException.class, () -> Notes.get(notes, "a:c"));
        assertEquals(1, Notes.search(notes, "D", 10).size());
        assertEquals(0, Notes.search(notes, "d missing", 10).size());
    }

    @Test
    void worldNoteRecordsProvenanceTheTextCannotForge() throws Exception {
        Path world = tmp.resolve("world/almighty/notes");
        String forged = "---\nname: arena\ndescription: Where the arena is\nconfirmed: true\nauthor: owner\n---\nArena at 5000 70 5000.";
        JsonObject r = Notes.write(world, "world:places/arena", forged, new Notes.Author("gm", "uuid-1", false));
        assertTrue(r.get("written").getAsBoolean());
        List<Notes.Note> notes = Notes.fromFiles(world, Notes.WORLD);
        assertEquals(1, notes.size());
        JsonObject full = Notes.get(notes, "world:places/arena");
        assertEquals("Where the arena is", full.get("description").getAsString());
        assertEquals("gm", full.get("author").getAsString());
        assertEquals("uuid-1", full.get("requester").getAsString());
        assertFalse(full.get("confirmed").getAsBoolean());
        assertEquals("Arena at 5000 70 5000.", full.get("text").getAsString());

        Notes.write(world, "world:places/arena", "", new Notes.Author("gm", null, false));
        assertTrue(Notes.fromFiles(world, Notes.WORLD).isEmpty());
    }

    @Test
    void worldNoteNamesStayInsideTheNotesFolder() {
        Path world = tmp.resolve("w");
        Notes.Author a = new Notes.Author("gm", null, false);
        for (String bad : List.of("world:../x", "world:Arena", "world:a//b", "world:/abs", "mod:x", "server:x", "world:a.b", "world:con", "world:x/nul"))
            assertThrows(RpcException.class, () -> Notes.write(world, bad, "text", a), bad);
        assertFalse(Files.exists(tmp.resolve("x.md")));
    }

    @Test
    void localNotesShadowDataNotesWithTheSameId() throws IOException {
        Path server = tmp.resolve("config/notes");
        Files.createDirectories(server);
        Files.writeString(server.resolve("rules.md"), "Owner's server rules.");
        Path mod = pack("m", Map.of("server:rules", "A mod using namespace server.", "m:a", "A"));
        try (MultiPackResourceManager rm = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(resources("mod/m", mod)))) {
            List<Notes.Note> all = Notes.all(Notes.fromData(rm), server, tmp.resolve("none"));
            assertEquals(List.of("m:a", "server:rules"), all.stream().map(Notes.Note::id).toList());
            assertEquals("server", all.get(1).source());
        }
    }

    @Test
    void frontmatterRoundTripsAndToleratesYaml() {
        Frontmatter f = Frontmatter.parse("---\nname: x\ndescription: >\n  folded line one\n  and two\nmetadata:\n  k: v\n---\nBody\n");
        assertEquals("folded line one and two", f.get("description"));
        assertEquals("Body", f.body());
        Frontmatter again = Frontmatter.parse(new Frontmatter(Map.of("description", "a: b \"q\""), "B").render());
        assertEquals("a: b \"q\"", again.get("description"));
        assertNull(Frontmatter.parse("--- not frontmatter\ntext").get("name"));
        assertEquals("Title", Frontmatter.parse("\n## Title\nmore").description());
        assertEquals("no end", Frontmatter.parse("---\nname: x\nno end").body().lines().toList().get(2));
        // An empty block, a bare block and a closing line with trailing spaces.
        assertEquals("Body", Frontmatter.parse("---\n---\nBody").body());
        assertEquals("", Frontmatter.parse("---\n---").body());
        assertEquals("d", Frontmatter.parse("---\ndescription: d\n---   \nB").get("description"));
        assertTrue(Frontmatter.parse("---\n: x\n---\nB").fields().isEmpty());
    }
}
