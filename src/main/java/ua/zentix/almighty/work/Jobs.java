package ua.zentix.almighty.work;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ua.zentix.almighty.feed.Feed;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * GM job queue: at server tick end, jobs run in submission order while time remains; the first gets at least
 * one work unit, so the queue progresses even in a heavy tick. Jobs awaiting chunks check readiness and yield.
 * Completed jobs enter history (last {@value #HISTORY}); their undo snapshots total at most {@value #RETAINED}
 * positions. Server thread only.
 */
public final class Jobs {
    static final int HISTORY = 50;
    static final long RETAINED = 4_000_000L;

    private final Feed feed;
    private final LongSupplier clock;
    private final List<Job> active = new ArrayList<>();
    private final Deque<Job> history = new ArrayDeque<>();
    private long nextId = 1;

    public Jobs(Feed feed, LongSupplier clock) {
        this.feed = feed;
        this.clock = clock;
    }

    public <J extends Job> J submit(J job) {
        job.assign(nextId++);
        active.add(job);
        return job;
    }

    /** Work for no more than {@code nanos} this tick. */
    public void tick(long nanos) {
        if (active.isEmpty()) return;
        Budget budget = new Budget(clock, nanos);
        for (int i = 0; i < active.size(); i++) {
            Job job = active.get(i);
            if (i == 0 || budget.left()) job.run(budget);
        }
        active.removeIf(job -> {
            if (!job.state().finished()) return false;
            ended(job);
            return true;
        });
    }

    public Job get(long id) {
        for (Job job : active) if (job.id() == id) return job;
        for (Job job : history) if (job.id() == id) return job;
        return null;
    }

    /** Cancel a job; false if already finished. */
    public boolean cancel(long id) {
        for (Job job : active) {
            if (job.id() == id) {
                job.cancel();
                active.remove(job);
                ended(job);
                return true;
            }
        }
        return false;
    }

    /** Server shutdown: cancel every job and release their tickets. */
    public void cancelAll() {
        for (Job job : active) job.cancel();
        active.clear();
    }

    public int running() {
        return active.size();
    }

    public JsonArray describe() {
        JsonArray out = new JsonArray();
        for (Job job : active) out.add(job.describe());
        for (Job job : history.reversed()) out.add(job.describe());
        return out;
    }

    private void ended(Job job) {
        history.addLast(job);
        while (history.size() > HISTORY) history.removeFirst().dropRetained();
        long retained = 0;
        for (Job j : history) retained += j.retained();
        for (Job old : history) {
            if (retained <= RETAINED) break;
            retained -= old.retained();
            old.dropRetained();
        }
        if (!job.announce()) return;
        JsonObject event = job.describe();
        event.addProperty("job", event.remove("id").getAsLong());
        feed.add("job", event);
    }
}
