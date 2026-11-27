package ua.zentix.almighty.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The {@code notes} method: what the agent can learn about this server. One method, by parameters:
 * <ul>
 * <li>{@code {}}: index of all notes (id, source, one-line description);</li>
 * <li>{@code {id}}: one whole note;</li>
 * <li>{@code {query?, kind?, offset?, limit?}}: notes matching every word of the query, and catalog entries of this
 * server (mods, registries, tags, recipes, commands, events, config) matching it, paged;</li>
 * <li>{@code {id: "world:<name>", text}}: write a world note; empty text removes it.</li>
 * </ul>
 */
public final class Knowledge {
    private static volatile Catalog catalog;

    private Knowledge() {}

    private static Catalog catalog(MinecraftServer server) {
        Catalog c = catalog;
        if (c == null || c.server() != server) catalog = c = new Catalog(server);
        return c;
    }

    /** The owner's server-wide notes. */
    public static Path serverNotes() {
        return ua.zentix.almighty.platform.Platform.get().configDir().resolve("almighty").resolve("notes");
    }

    /** World notes: they follow the world into backups and restores. */
    public static Path worldNotes(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("almighty").resolve("notes");
    }

    /** Notes from server data: call on the server thread (the resource manager changes on {@code /reload}). */
    static List<Notes.Note> fromData(MinecraftServer server) {
        return Notes.fromData(server.getResourceManager());
    }

    public static CompletableFuture<JsonElement> notes(GmServer gm, Args a, Notes.Author author) throws RpcException {
        MinecraftServer server = gm.server();
        String id = a.string("id", null);
        if (a.has("text")) {
            if (id == null) throw RpcException.badRequest("text: give the note id, world:<name>");
            for (String other : List.of("query", "kind", "offset", "limit")) {
                if (a.has(other)) throw RpcException.badRequest(other + ": not with text");
            }
            String text = a.string("text");
            Path dir = worldNotes(server);
            // A small file write, done on the bridge thread: the server thread never waits for the disk here.
            synchronized (Knowledge.class) {
                return CompletableFuture.completedFuture(Notes.write(dir, id, text, author));
            }
        }
        String query = a.string("query", null);
        String kind = a.string("kind", null);
        int limit = a.integer("limit", 50, 1, 200);
        int offset = a.integer("offset", 0, 0, Integer.MAX_VALUE);
        if (id != null && (query != null || kind != null || a.has("offset") || a.has("limit"))) {
            throw RpcException.badRequest("id reads one note: query, kind, offset and limit do not go with it");
        }
        if (kind != null && !Catalog.KINDS.contains(kind)) {
            throw RpcException.badRequest("kind: one of " + String.join(", ", Catalog.KINDS));
        }
        Path worldDir = worldNotes(server);
        if (query == null && kind == null) {
            // Data notes on the server thread; files from disk off it.
            return gm.onMain(() -> fromData(server)).thenApplyAsync(data -> {
                List<Notes.Note> all = Notes.all(data, serverNotes(), worldDir);
                try {
                    if (id != null) return Notes.get(all, id);
                } catch (RpcException e) {
                    throw new CompletionException(e);
                }
                JsonObject index = Notes.index(all);
                JsonArray kinds = new JsonArray();
                Catalog.KINDS.forEach(kinds::add);
                index.add("catalog", kinds);
                return index;
            });
        }
        Catalog c = catalog(server);
        boolean withNotes = query != null && kind == null && offset == 0;
        // Snapshot on the server thread what a reload replaces; search off it.
        return gm.onMain(() -> {
            c.refresh();
            return withNotes ? fromData(server) : List.<Notes.Note>of();
        }).thenApplyAsync(data -> {
            try {
                JsonObject out = c.search(kind, query, offset, limit);
                if (withNotes) out.add("notes", Notes.search(Notes.all(data, serverNotes(), worldDir), query, limit));
                return out;
            } catch (RpcException e) {
                throw new CompletionException(e);
            }
        });
    }
}
