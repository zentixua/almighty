package ua.zentix.almighty.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import ua.zentix.almighty.bridge.Caller;
import ua.zentix.almighty.bridge.RpcException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Audit log (plan 4.9): one JSON line per GM call with effects, and per refused call, in
 * {@code logs/almighty/audit-<date>.jsonl}: time, agent, address, method, parameters (clipped), outcome, status,
 * milliseconds, the requester's ticket and the job it started. Files older than {@code audit.days} are deleted.
 * Writing happens on its own thread (never the server thread); the last {@value #KEEP} entries stay in memory for
 * {@code /almighty log} and the {@code audit} method. Read-only calls that succeed are not logged (an agent's long
 * poll would flood the file).
 *
 * <p>Plan 4.9 named a log4j2 appender; a plain appending writer was chosen instead because configuring an appender
 * programmatically changes the server's global logging configuration, which other mods and panels also own.
 */
public final class Audit {
    static final int KEEP = 1000;
    static final int MAX_PARAMS = ua.zentix.almighty.bridge.Dispatch.MAX_PARAMS_TEXT;
    /** Queued after the last line at stop: the writer finishes and exits without being interrupted. */
    private static final String END = "\u0000end";
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path dir;
    private final int days;
    private final Deque<JsonObject> recent = new ArrayDeque<>();
    private final BlockingQueue<String> pending = new ArrayBlockingQueue<>(10_000);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong seq = new AtomicLong();
    private final Thread writer;
    private volatile boolean running = true;

    /** The day of each file: policy.toml {@code timezone}, else the machine's. */
    private volatile java.util.function.Supplier<ZoneId> zone = ZoneId::systemDefault;

    public void zone(java.util.function.Supplier<ZoneId> zone) {
        this.zone = zone;
    }

    /** {@code dir} null: memory only (tests). */
    public Audit(Path dir, int days) {
        this.dir = dir;
        this.days = days;
        this.writer = new Thread(this::write, "almighty audit");
        writer.setDaemon(true);
    }

    private void startWriter() {
        if (dir == null || writer.getState() != Thread.State.NEW) return;
        synchronized (writer) {
            if (writer.getState() == Thread.State.NEW) writer.start();
        }
    }

    /** Record a finished call; {@code readOnly}: a successful read is not recorded. */
    public void ended(Caller caller, String method, JsonObject params, String paramsText, JsonElement result, Throwable error,
                      long nanos, boolean readOnly, long change) {
        if (readOnly && error == null) return;
        JsonObject e = new JsonObject();
        e.addProperty("n", seq.incrementAndGet());
        e.addProperty("time", Instant.now().toString());
        e.addProperty("agent", caller.name());
        if (caller.requester() != null) e.addProperty("requester", caller.requester().toString());
        e.addProperty("address", caller.address());
        e.addProperty("method", method);
        e.addProperty("params", paramsText);
        if (change > 0) e.addProperty("change", change);
        if (params.has("for") && params.get("for").isJsonPrimitive()) e.addProperty("ticket", params.get("for").getAsString());
        if (error == null) {
            e.addProperty("outcome", "ok");
            if (result instanceof JsonObject o && o.has("kind") && o.get("id") instanceof com.google.gson.JsonPrimitive id && id.isNumber()) {
                e.addProperty("job", id.getAsLong());
            }
        } else if (error instanceof RpcException r) {
            e.addProperty("outcome", r.code());
            e.addProperty("status", r.status());
            e.addProperty("error", r.getMessage());
        } else {
            e.addProperty("outcome", "internal");
            e.addProperty("error", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        e.addProperty("ms", Math.round(nanos / 1e4) / 100.0);
        record(e);
    }

    /** Record an owner action (pause, revoke, undo …) from the console or a command. */
    public void owner(String who, String action, String detail) {
        JsonObject e = new JsonObject();
        e.addProperty("n", seq.incrementAndGet());
        e.addProperty("time", Instant.now().toString());
        e.addProperty("agent", "owner");
        e.addProperty("by", who);
        e.addProperty("method", action);
        e.addProperty("params", detail);
        e.addProperty("outcome", "ok");
        record(e);
    }

    private void record(JsonObject e) {
        synchronized (recent) {
            recent.addLast(e);
            while (recent.size() > KEEP) recent.removeFirst();
        }
        if (dir == null) return;
        // Started on the first entry: a server that never starts (a constructor failing later) leaks no thread.
        startWriter();
        if (!pending.offer(GSON.toJson(e))) dropped.incrementAndGet();
    }

    /** The last {@code limit} entries passing {@code filter}, oldest first. */
    public JsonArray recent(int limit, Predicate<JsonObject> filter) {
        List<JsonObject> out = new ArrayList<>();
        synchronized (recent) {
            for (var it = recent.descendingIterator(); it.hasNext() && out.size() < limit; ) {
                JsonObject e = it.next();
                if (filter.test(e)) out.add(e);
            }
        }
        JsonArray a = new JsonArray();
        for (int i = out.size() - 1; i >= 0; i--) a.add(out.get(i).deepCopy());
        return a;
    }

    public long dropped() {
        return dropped.get();
    }

    public void stop() {
        running = false;
        if (writer.getState() == Thread.State.NEW) return;
        // No interrupt: writing through an interruptible channel would close it and lose what is still queued.
        pending.offer(END);
        try {
            writer.join(5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private Path file(LocalDate day) {
        return dir.resolve("audit-" + day + ".jsonl");
    }

    private void write() {
        prune();
        LocalDate day = null;
        java.io.Writer out = null;
        boolean warned = false;
        while (true) {
            String line;
            try {
                line = pending.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                line = pending.poll();
            }
            if (line == null) {
                if (!running) break;
                try {
                    if (out != null) out.flush();
                } catch (IOException ignored) {
                    // Reported on the next write.
                }
                continue;
            }
            if (line.equals(END)) break;
            try {
                LocalDate today = LocalDate.now(zone.get());
                if (out == null || !today.equals(day)) {
                    if (out != null) out.close();
                    Files.createDirectories(dir);
                    // FileOutputStream, not a channel: an interrupt cannot close it under us.
                    out = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(file(today).toFile(), true), StandardCharsets.UTF_8));
                    if (day != null && !today.equals(day)) prune();
                    day = today;
                }
                out.write(line);
                out.write('\n');
                if (pending.isEmpty()) out.flush();
                warned = false;
            } catch (IOException e) {
                // Keep running: reopen on the next line (the disk may come back).
                if (!warned) LOG.error("Almighty audit: cannot write to {}: {}", dir, e.toString());
                warned = true;
                dropped.incrementAndGet();
                try {
                    if (out != null) out.close();
                } catch (IOException ignored) {
                    // Already failing.
                }
                out = null;
            }
        }
        try {
            if (out != null) out.close();
        } catch (IOException e) {
            LOG.warn("Almighty audit: closing {}: {}", dir, e.toString());
        }
    }

    /** Delete audit files older than {@code days}. */
    private void prune() {
        if (!Files.isDirectory(dir)) return;
        LocalDate oldest = LocalDate.now(zone.get()).minusDays(days);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "audit-*.jsonl")) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                try {
                    LocalDate d = LocalDate.parse(name.substring("audit-".length(), name.length() - ".jsonl".length()));
                    if (d.isBefore(oldest)) Files.deleteIfExists(f);
                } catch (RuntimeException ignored) {
                    // Not ours.
                }
            }
        } catch (IOException e) {
            LOG.warn("Almighty audit: cannot prune {}: {}", dir, e.toString());
        }
    }
}
