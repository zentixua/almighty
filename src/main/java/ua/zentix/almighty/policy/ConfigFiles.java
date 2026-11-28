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

        /** Tell the owners a change the agent made on its own (restricting, so it applied at once; plan 4.10 receipt). */
        default void told(String text) {}
    }

    /** The policy text a granting proposal was made against (set by the server at ask time, never by the agent). */
    static final String BASE = "_policy_base";

    public ConfigFiles(Path dir, Policies policies, Gate gate, Asker asker) {
        this.dir = dir;
        this.policies = policies;
        this.gate = gate;
        this.asker = asker;
    }

    /** The game's asker: an admin confirmation of the exact call, decided by a holder of {@code almighty.engine}. */
    public static Asker digest(Decisions decisions) {
        return new Asker() {
            @Override
            public void told(String text) {
                decisions.tellOwners(text);
            }

            @Override
            public RpcException ask(Args a, String grants) {
            try {
                // The owner reads a summary (a whole file does not fit a chat line); the exact text runs when allowed.
                String text = a.raw().has("text") ? a.raw().get("text").getAsString() : "";
                String display = "file: the agent proposes policy.toml (" + text.lines().count() + " lines, sha256 " + sha(text)
                        + "), which grants " + grants;
                Confirmations.Pending pending = decisions.confirmations().ask(Confirmations.Kind.ADMIN, a.caller(), "file", a.raw(),
                        null, Node.ENGINE, null, "the agent", display);
                if (pending.count() == 1) decisions.prompt(pending);
                return Confirmations.waiting(pending, "the owner (it grants " + grants + ")");
            } catch (RpcException e) {
                return e;
            }
            }
        };
    }

    static String sha(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
                case "read" -> read(a, a.string("path"));
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

    private JsonObject read(Args a, String path) throws IOException, RpcException {
        Path p = resolve(path);
        // Scripts are the owner's code (they may hold anything the owner wrote): only the owner reads them here.
        if (!path.equals("policy.toml") && !owner(a)) throw new RpcException(403, "policy", "Scripts are read only when the owner asks");
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

    /**
     * Whether the call comes from the owner: the console or an internal caller, or a request whose player is the
     * owner. The agent unasked is never the owner here, whatever its floor (on a private server too): its own
     * initiative gets the friction.
     */
    private boolean owner(Args a) throws RpcException {
        if (a.caller().agent() == null) return true;
        Gate.Rights r = gate.rights(a.caller(), a.raw());
        return r.ticket() != null && r.effective() == Role.OWNER;
    }

    /** This exact call was allowed in game by a holder of almighty.engine (the owner's digest), not any admin ask. */
    private static boolean approved() {
        Confirmations.Pending running = Confirmations.running();
        return running != null && running.kind == Confirmations.Kind.ADMIN && running.method.equals("file")
                && running.node == Node.ENGINE && running.decider != null;
    }

    /**
     * Undo the last policy change: a write of the newest saved version's exact text (so an approval, if it grants,
     * is bound to that text), with the same friction. The restored entry leaves the history; the undone version is
     * kept in {@code policy.history/undone/}.
     */
    private JsonObject undo(Args a) throws IOException, RpcException {
        synchronized (PolicyFile.LOCK) {
            List<Path> h = history();
            if (h.isEmpty()) throw RpcException.notFound("No earlier policy saved");
            Path last = h.get(h.size() - 1);
            JsonObject p = new JsonObject();
            p.addProperty("action", "write");
            p.addProperty("path", "policy.toml");
            p.addProperty("text", Files.readString(last, StandardCharsets.UTF_8));
            p.addProperty("undo_of", last.getFileName().toString());
            if (a.raw().has("for")) p.add("for", a.raw().get("for"));
            return write(new Args(p, a.caller()), "policy.toml", p.get("text").getAsString(), "undo");
        }
    }

    private JsonObject write(Args a, String path, String text, String what) throws IOException, RpcException {
        Path p = resolve(path);
        boolean owner = owner(a);
        if (!owner && a.caller().requester() != null && !approved()) {
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
        boolean approved = approved();
        synchronized (PolicyFile.LOCK) {
            // Compared with the policy as it is now, under the lock (another write may have just landed).
            Policy old = policies.current();
            if (!old.owners().isEmpty() && neu.owners().isEmpty()) throw RpcException.conflict("This would remove the last owner: refused");
            String host = PolicyEdits.hostLevel(old, neu);
            if (host != null && a.caller().agent() != null) {
                throw new RpcException(403, "policy", "Only the console can do this (" + host + "): the owner edits policy.toml there");
            }
            String base = sha(Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : "");
            if (!owner && !approved) {
                String grants = PolicyEdits.grants(old, neu);
                // The agent's own initiative, granting something: one owner's approval, through the quiet digest. The
                // proposal carries the file it was made against (rights audit m3), so an approval cannot silently
                // revert an edit made since, and the grants shown are the grants applied.
                if (grants != null) {
                    JsonObject proposal = a.raw().deepCopy();
                    proposal.addProperty(BASE, base);
                    throw asker.ask(new Args(proposal, a.caller()), grants);
                }
            }
            if (Confirmations.running() != null && a.raw().has(BASE) && !a.raw().get(BASE).getAsString().equals(base)) {
                throw RpcException.conflict("policy.toml changed after this was proposed: not applied; the agent proposes it again against the current file");
            }
            Path saved = apply(p, text);
            if (!owner && !approved) {
                asker.told("The agent " + a.caller().name() + " changed policy.toml on its own (only restricting; sha256 " + sha(text)
                        + "). Undo: /almighty policy undo");
            }
            // An undo: the restored entry leaves the walk (only if it is still that text and the newest entry, so a
            // plain write naming an older entry deletes nothing: rights audit m4); the undone version is kept.
            List<Path> walk = history();
            if (a.raw().has("undo_of") && saved != null && walk.size() >= 2) {
                Path entry = dir.resolve("policy.history").resolve(a.raw().get("undo_of").getAsString()).normalize();
                // The newest entry before this write's own (apply just saved the old file as the newest).
                if (entry.equals(walk.get(walk.size() - 2).normalize()) && Files.isRegularFile(entry)
                        && Files.readString(entry, StandardCharsets.UTF_8).equals(text)) {
                    Files.deleteIfExists(entry);
                    if (saved != null) {
                        Path undone = dir.resolve("policy.history").resolve("undone");
                        Files.createDirectories(undone);
                        Files.move(saved, undone.resolve(saved.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        return done(path, what, owner ? "applied (asked by the owner)" : approved ? "applied (approved by the owner)" : "applied (it only restricts)");
    }

    /** {@code /almighty policy undo}: the owner at the console or in game; no friction beyond validation. */
    public String undoByOwner() {
        JsonObject p = new JsonObject();
        p.addProperty("action", "undo");
        try {
            undo(new Args(p, ua.zentix.almighty.bridge.Caller.INTERNAL));
            return null;
        } catch (IOException | RpcException e) {
            return e.getMessage();
        }
    }

    /** Write the new text, keeping the old file in history first; returns the history copy (null if none). */
    private Path apply(Path p, String text) throws IOException, RpcException {
        Path h = dir.resolve("policy.history");
        Files.createDirectories(h);
        Path copy = null;
        if (Files.isRegularFile(p)) {
            copy = h.resolve(stamp(h));
            Files.copy(p, copy);
        }
        List<Path> all = history();
        for (int i = 0; i + HISTORY < all.size(); i++) Files.deleteIfExists(all.get(i));
        String why = PolicyFile.write(p, text);
        if (why != null) {
            if (copy != null) Files.deleteIfExists(copy);
            throw RpcException.badRequest(why);
        }
        String reloaded = policies.reload();
        if (reloaded != null) throw new RpcException(500, "reload", reloaded);
        return copy;
    }

    /**
     * Owner-approved scripts (plan 4.12): {@code scripts/<name>.groovy} runs by name only while its SHA-256 matches
     * the one the owner pinned with {@code /almighty script approve <name>} ({@code scripts/approved.json}). Any edit
     * of the file (by the owner, or anyone with file access) needs a new approval.
     */
    public String approvedScript(String name) throws RpcException {
        Path p = resolve("scripts/" + name + ".groovy");
        try {
            if (!Files.isRegularFile(p)) throw RpcException.notFound("No script " + name + " in config/almighty/scripts");
            String code = Files.readString(p, StandardCharsets.UTF_8);
            String pinned = approvals().has(name) ? approvals().get(name).getAsString() : null;
            if (pinned == null) throw new RpcException(403, "policy", "Script " + name + " is not approved: the owner runs /almighty script approve " + name);
            if (!pinned.equals(fullSha(code))) throw new RpcException(403, "policy", "Script " + name + " changed since the owner approved it: /almighty script approve " + name + " again");
            return code;
        } catch (IOException e) {
            throw new RpcException(500, "io", e.getMessage());
        }
    }

    /** {@code /almighty script approve|revoke <name>}: pin or drop the file's hash. Returns null, or why not. */
    public String approve(String name, boolean approve) {
        synchronized (PolicyFile.LOCK) {
            try {
                Path p = resolve("scripts/" + name + ".groovy");
                JsonObject all = approvals();
                if (approve) {
                    if (!Files.isRegularFile(p)) return "No script " + name + " in config/almighty/scripts";
                    all.addProperty(name, fullSha(Files.readString(p, StandardCharsets.UTF_8)));
                } else if (all.remove(name) == null) {
                    return "Script " + name + " was not approved";
                }
                Path f = dir.resolve("scripts").resolve("approved.json");
                Files.createDirectories(f.getParent());
                Files.writeString(f, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(all), StandardCharsets.UTF_8);
                return null;
            } catch (IOException | RpcException e) {
                return e.getMessage();
            }
        }
    }

    /** Approved names (and whether each file still matches). */
    public JsonObject approvedList() throws IOException {
        JsonObject out = new JsonObject();
        for (var e : approvals().entrySet()) {
            Path p = dir.resolve("scripts").resolve(e.getKey() + ".groovy");
            out.addProperty(e.getKey(), Files.isRegularFile(p) && e.getValue().getAsString().equals(fullSha(Files.readString(p, StandardCharsets.UTF_8)))
                    ? "approved" : "changed or missing: approve again");
        }
        return out;
    }

    private JsonObject approvals() throws IOException {
        Path f = dir.resolve("scripts").resolve("approved.json");
        if (!Files.isRegularFile(f)) return new JsonObject();
        try {
            return com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("scripts/approved.json is not readable: " + e.getMessage());
        }
    }

    static String fullSha(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A unique, sortable history name (two writes in one millisecond must not collide). */
    private static String stamp(Path h) {
        long t = System.currentTimeMillis();
        // Never before the newest entry (a clock that went back would misorder the walk).
        try {
            List<Path> all = new ArrayList<>();
            try (DirectoryStream<Path> s = Files.newDirectoryStream(h, "*.toml")) {
                s.forEach(all::add);
            }
            for (Path q : all) {
                String n = q.getFileName().toString().replace(".toml", "");
                if (n.matches("\\d{15}")) t = Math.max(t, Long.parseLong(n) + 1);
            }
        } catch (IOException e) {
            // Best effort: the time alone.
        }
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
