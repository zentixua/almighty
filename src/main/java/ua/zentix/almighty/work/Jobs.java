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
 * Очередь работ ведущего: в конце тика сервера работы идут по порядку постановки, пока есть время тика; первая — хотя
 * бы одну единицу, так что очередь движется и в тяжёлом тике. Ждущие загрузки чанков проверяют готовность и уступают.
 * Кончившиеся — в историю ({@value #HISTORY} последних), снимки откатов в ней — не больше {@value #RETAINED} мест вместе.
 * Только поток сервера.
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

    /** Работать в этом тике не дольше {@code nanos}. */
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

    /** Отменить работу; кончившаяся — false. */
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

    /** Остановка сервера: все работы — отменены, их тикеты отпущены. */
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
