package ua.zentix.almighty.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Almighty's own files, read and written by the agent when asked (plan 4.10, step 10): {@code policy.toml} and
 * {@code scripts/<name>.groovy}, nothing else. Files stay the single source of truth; every policy write is
 * validated (the old file stays on any error), saved to {@code policy.history/} first and applied live. Friction
 * follows origin:
 * <ul>
 * <li>the owner (an owner's request, the console, an owner client) — applied at once; only host-level changes
 * (an agent raised to an owner ceiling) need the console;</li>
 * <li>a player's request — never: players cannot originate policy changes;</li>
 * <li>the agent's own initiative — a change that only restricts applies at once; one that grants anything waits
 * for one owner's approval (the admin digest, decided by a holder of {@code almighty.engine}).</li>
 * </ul>
 */
public final class ConfigFiles {
    static final int HISTORY = 50;
    private static final Pattern SCRIPT = Pattern.compile("scripts/[a-z0-9_-]{1,40}\\.groovy");

    private final Path dir;
    private final Policies policies;
    private final Gate gate;
    private final Asker asker;

    /** Asks an owner to approve a granting change; returns the 202 for the agent (the admin digest in game). */
    public interface Asker {
        RpcException ask(Args a, String grants);
    }

    public ConfigFiles(Path dir, Policies policies, Gate gate, Asker asker) {
        this.dir = dir;
        this.policies = policies;
        this.gate = gate;
        this.asker = asker;
    }

    /** The game's asker: an admin confirmation of the exact call, decided by a holder of {@code almighty.engine}. */
    public static Asker digest(Decisions decisions) {
        return (a, grants) -> {
            try {
                Confirmations.Pending pending = decisions.confirmations().ask(Confirmations.Kind.ADMIN, a.caller(), "file", a.raw(),
                        null, Node.ENGINE, null, "the agent");
                if (pending.count() == 1) decisions.prompt(pending);
                return Confirmations.waiting(pending, "the owner (it grants " + grants + ")");
            } catch (RpcException e) {
                return e;
            }
        };
    }

    private Path resolve(String path) throws RpcException {
        if (path.equals("policy.toml") || SCRIPT.matcher(path).matches()) return dir.resolve(path);
        throw RpcException.badRequest("path: policy.toml or scripts/<name>.groovy (lowercase letters, digits, - and _)");
    }

    /** {@code file {action: list | read | write | undo, path?, text?}}. Bridge thread (small files). */
    public JsonObject call(Args a) throws RpcException {
        String action = a.string("action");
        try {
            return switch (action) {
                case "list" -> list();
                case "read" -> read(a.string("path"));
                case "write" -> write(a, a.string("path"), a.string("text"), "write");
                case "undo" -> undo(a);
                default -> throw RpcException.badRequest("action: list, read, write or undo");
            };
        } catch (IOException e) {
            throw new RpcException(500, "io", e.getMessage());
        }
    }

    private JsonObject list() throws IOException {
        JsonArray files = new JsonArray();
        List<Path> paths = new ArrayList<>();
        paths.add(dir.resolve("policy.toml"));
        Path scripts = dir.resolve("scripts");
        if (Files.isDirectory(scripts)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(scripts, "*.groovy")) {
                s.forEach(paths::add);
            }
        }
        for (Path p : paths) {
            if (!Files.isRegularFile(p)) continue;
            JsonObject f = new JsonObject();
            f.addProperty("path", dir.relativize(p).toString().replace('\\', '/'));
            f.addProperty("bytes", Files.size(p));
            files.add(f);
        }
        JsonObject out = new JsonObject();
        out.add("files", files);
        out.addProperty("history", history().size());
        return out;
    }

    private JsonObject read(String path) throws IOException, RpcException {
        Path p = resolve(path);
        if (!Files.isRegularFile(p)) throw RpcException.notFound("No " + path);
        JsonObject out = new JsonObject();
        out.addProperty("path", path);
        out.addProperty("text", Files.readString(p, StandardCharsets.UTF_8));
        return out;
    }

    private List<Path> history() throws IOException {
        Path h = dir.resolve("policy.history");
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(h)) return out;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(h, "*.toml")) {
            s.forEach(out::add);
        }
        out.sort(null);
        return out;
    }

    /** Undo the last policy change: write back the newest saved version (with the same friction). */
    private JsonObject undo(Args a) throws IOException, RpcException {
        List<Path> h = history();
        if (h.isEmpty()) throw RpcException.notFound("No earlier policy saved");
        Path last = h.get(h.size() - 1);
        JsonObject out = write(a, "policy.toml", Files.readString(last, StandardCharsets.UTF_8), "undo");
        // write() saved the undone version as the newest entry; drop it and the restored one, so undo walks back.
        Files.deleteIfExists(newest());
        Files.deleteIfExists(last);
        return out;
    }

    private JsonObject write(Args a, String path, String text, String what) throws IOException, RpcException {
        Path p = resolve(path);
        Role effective = a.caller().agent() == null ? Role.OWNER : gate.rights(a.caller(), a.raw()).effective();
        boolean owner = effective == Role.OWNER;
        if (!owner && a.caller().requester() != null) {
            throw new RpcException(403, "policy", "Players cannot change Almighty's files (only the owner, or restricting changes by the agent)");
        }
        if (!path.equals("policy.toml")) {
            // Scripts run code in the server JVM: only the owner writes them (and approves them to run, step 11).
            if (!owner) throw new RpcException(403, "policy", "Scripts are written only when the owner asks");
            Files.createDirectories(p.getParent());
            Files.writeString(p, text, StandardCharsets.UTF_8);
            return done(path, what, "written; it runs only after the owner approves it (/almighty script approve)");
        }
        Policy neu;
        try {
            neu = Policy.parse(text);
        } catch (Policy.Invalid e) {
            throw RpcException.badRequest("policy.toml refused, the current one stays: " + e.getMessage());
        }
        Policy old = policies.current();
        if (!old.owners().isEmpty() && neu.owners().isEmpty()) throw RpcException.conflict("This would remove the last owner: refused");
        String host = PolicyEdits.hostLevel(old, neu);
        if (host != null && a.caller().agent() != null) {
            throw new RpcException(403, "policy", "Only the console can do this (" + host + "): the owner edits policy.toml there");
        }
        // This exact call, approved in game by a holder of almighty.engine (the owner): applied, whatever the agent's ceiling.
        Confirmations.Pending running = Confirmations.running();
        boolean approved = running != null && running.kind == Confirmations.Kind.ADMIN && running.method.equals("file") && running.decider != null;
        if (!owner && !approved) {
            String grants = PolicyEdits.grants(old, neu);
            if (grants != null) {
                // The agent's own initiative, granting something: one owner's approval, through the quiet digest.
                throw asker.ask(a, grants);
            }
        }
        apply(p, text);
        return done(path, what, owner ? "applied (asked by the owner)" : approved ? "applied (approved by the owner)" : "applied (it only restricts)");
    }

    /** {@code /almighty policy undo}: the owner at the console or in game (level 4); no friction beyond validation. */
    public String undoByOwner() {
        try {
            List<Path> h = history();
            if (h.isEmpty()) return "No earlier policy saved";
            Path last = h.get(h.size() - 1);
            String text = Files.readString(last, StandardCharsets.UTF_8);
            try {
                Policy.parse(text);
            } catch (Policy.Invalid e) {
                return "The saved policy is not valid any more: " + e.getMessage();
            }
            Files.deleteIfExists(last);
            apply(dir.resolve("policy.toml"), text);
            Files.deleteIfExists(newest());
            return null;
        } catch (IOException | RpcException e) {
            return e.getMessage();
        }
    }

    /** The newest history entry: the copy {@link #apply} just made of the version being undone. */
    private Path newest() throws IOException {
        List<Path> h = history();
        return h.isEmpty() ? dir.resolve("policy.history").resolve("none") : h.get(h.size() - 1);
    }

    private void apply(Path p, String text) throws IOException, RpcException {
        Path h = dir.resolve("policy.history");
        Files.createDirectories(h);
        if (Files.isRegularFile(p)) Files.copy(p, h.resolve(stamp(h)));
        List<Path> all = history();
        for (int i = 0; i + HISTORY < all.size(); i++) Files.deleteIfExists(all.get(i));
        String why = PolicyFile.write(p, text);
        if (why != null) throw RpcException.badRequest(why);
        String reloaded = policies.reload();
        if (reloaded != null) throw new RpcException(500, "reload", reloaded);
    }

    /** A unique, sortable history name (two writes in one millisecond must not collide). */
    private static String stamp(Path h) {
        long t = System.currentTimeMillis();
        while (Files.exists(h.resolve(String.format(java.util.Locale.ROOT, "%015d.toml", t)))) t++;
        return String.format(java.util.Locale.ROOT, "%015d.toml", t);
    }

    private static JsonObject done(String path, String what, String how) {
        JsonObject out = new JsonObject();
        out.addProperty("path", path);
        out.addProperty("done", what);
        out.addProperty("how", how);
        out.addProperty("undo", "file {action: \"undo\"} or /almighty policy undo");
        return out;
    }
}
