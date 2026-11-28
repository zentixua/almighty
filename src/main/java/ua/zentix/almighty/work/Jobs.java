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

    /** Agents whose jobs wait without running (paused by the owner); never null. */
    private java.util.function.Predicate<String> paused = a -> false;

    public void paused(java.util.function.Predicate<String> paused) {
        this.paused = paused;
    }

    public <J extends Job> J submit(J job) {
        ua.zentix.almighty.bridge.Caller by = ua.zentix.almighty.bridge.Dispatch.current();
        return submit(job, by.name(), by.requester());
    }

    <J extends Job> J submit(J job, String agent, java.util.UUID requester) {
        job.assign(nextId++, agent, requester);
        ua.zentix.almighty.audit.Journal.Change c = ua.zentix.almighty.audit.Journal.current();
        if (c != null && job.effects()) job.change = c.retain();
        active.add(job);
        return job;
    }

    /** Which agent's jobs go first this tick when several agents have work (rotates every tick). */
    private long turn;

    /**
     * Work for no more than {@code nanos} this tick. One agent: its jobs in submission order, the first at least one
     * unit. Several agents (plan S6, F2): each gets an equal share and at least one unit for its first job, the first
     * turn rotating every tick, so one agent's huge build never starves another's; time a share leaves unused goes to
     * the jobs in order.
     */
    public void tick(long nanos) {
        if (active.isEmpty()) return;
        List<Job> runnable = new ArrayList<>();
        java.util.LinkedHashSet<String> agents = new java.util.LinkedHashSet<>();
        for (Job job : active) {
            // A paused agent's builds and undos are frozen in place (resume continues them, cancel ends them); views
            // and area preparation go on, so the paused agent can still look.
            if (job.effects() && paused.test(job.agent())) continue;
            runnable.add(job);
            agents.add(job.agent());
        }
        if (agents.size() <= 1) {
            Budget budget = new Budget(clock, nanos);
            boolean first = true;
            for (Job job : runnable) {
                if (first || budget.left()) run(job, budget);
                first = false;
            }
        } else {
            Budget all = new Budget(clock, nanos);
            List<String> order = new ArrayList<>(agents);
            java.util.Collections.rotate(order, -(int) (turn++ % order.size()));
            long share = nanos / order.size();
            for (String agent : order) {
                Budget mine = new Budget(clock, share);
                boolean first = true;
                for (Job job : runnable) {
                    if (!job.agent().equals(agent)) continue;
                    if (first || mine.left()) run(job, mine);
                    first = false;
                }
            }
            for (Job job : runnable) {
                if (!all.left()) break;
                // Only jobs already running: a waiting job counts its wait per step, so a second step would halve its timeout.
                if (job.state() == Job.State.RUNNING) run(job, all);
            }
        }
        active.removeIf(job -> {
            if (!job.state().finished()) return false;
            ended(job);
            return true;
        });
    }

    private static void run(Job job, Budget budget) {
        ua.zentix.almighty.audit.Journal.with(job.change, () -> {
            job.run(budget);
            return null;
        });
    }

    /** A job left the queue: its share of the change ends. */
    private static void releaseChange(Job job) {
        if (job.change != null) {
            job.change.release();
            job.change = null;
        }
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
        releaseChange(job);
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
