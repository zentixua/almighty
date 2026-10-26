package ua.zentix.almighty.work;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * A GM job within the tick budget (build, undo, snapshot): steps on the server thread while time remains,
 * at least one work unit per tick; each unit is bounded (tens of positions). Completion uses {@link #done()}:
 * the bridge waits for it, and the feed receives a {@code job} event (except snapshots: {@link #announce}).
 */
public abstract class Job {
    private static final Logger LOG = LogUtils.getLogger();

    public enum State {
        /** Waiting for its chunks to load. */
        WAITING,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED;

        public boolean finished() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String kind;
    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private long id;
    private State state = State.WAITING;
    private String error;

    protected Job(String kind) {
        this.kind = kind;
    }

    void assign(long id) {
        this.id = id;
        submitted();
    }

    /** The job has received an id and entered the queue (server thread). */
    protected void submitted() {}

    public long id() {
        return id;
    }

    public String kind() {
        return kind;
    }

    public State state() {
        return state;
    }

    /** Future with the job description on completion (successful or otherwise). */
    public CompletableFuture<JsonObject> done() {
        return done;
    }

    /** Work while time remains (at least one work unit). State changes use {@link #running}, {@link #finish}, {@link #fail}. */
    protected abstract void step(Budget budget);

    /** Release resources held by the job (chunk tickets); called once on any completion. */
    protected abstract void release();

    /** Description details: progress and result. */
    protected abstract void details(JsonObject out);

    /**
     * A result delivered only to the waiting call, not to the job list or feed (snapshot image): called once
     * on completion; the job can discard it immediately.
     */
    protected void payload(JsonObject out) {}

    /** How many positions the job retains in memory (undo snapshots); 0 means none. After completion, only data needed for undo. */
    public long retained() {
        return 0;
    }

    /** Whether to announce job completion in the feed (a {@code job} event). */
    public boolean announce() {
        return true;
    }

    /** Discard resources retained after completion (old snapshots when space runs out). */
    public void dropRetained() {}

    protected final void running() {
        if (state == State.WAITING) state = State.RUNNING;
    }

    protected final void finish() {
        end(State.DONE, null);
    }

    protected final void fail(String message) {
        end(State.FAILED, message);
    }

    final void cancel() {
        end(State.CANCELLED, null);
    }

    final void run(Budget budget) {
        try {
            step(budget);
        } catch (RuntimeException e) {
            LOG.warn("GM job {} #{} failed", kind, id, e);
            fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void end(State to, String message) {
        if (state.finished()) return;
        state = to;
        error = message;
        release();
        JsonObject result = describe();
        if (to == State.DONE) payload(result);
        done.complete(result);
    }

    public JsonObject describe() {
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("kind", kind);
        out.addProperty("state", state.id());
        if (error != null) out.addProperty("error", error);
        details(out);
        return out;
    }
}
