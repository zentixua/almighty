package ua.zentix.almighty.policy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Answers of a permission handler about players who are offline, for the server thread: asked in the background and
 * cached. A handler may load an offline user from its storage before answering (LuckPerms does), which on the server
 * thread would stall the tick (stability audit M10): {@link #get} never waits. An answer older than
 * {@link #FRESH_MS} is still used while a new one is asked. A change of the policy or a role ({@code generation})
 * makes every answer stale at once; until the new answer arrives, a last known "no" still holds (never wider than
 * the handler said), and only a player never asked about, or last told "yes", gets null (the role ladder decides).
 * Callers off the server thread ask the handler directly instead.
 */
public final class OfflineAnswers {
    /** How long an answer counts as current before it is asked again. */
    public static final long FRESH_MS = 30_000;
    /** At most this many answers kept; beyond it the cache starts over (players, not nodes, make it grow). */
    static final int MAX = 4096;

    private record Key(UUID player, String node) {}

    private static final class Entry {
        /** The handler's latest answer, whatever generation asked it; null before any. */
        Boolean last;
        /** When {@link #last} was answered for {@link #answered}; 0 for never. */
        long at;
        long answered = -1;
        /** The generation an ask is running for; -1 for none. */
        long asking = -1;
    }

    private final Map<Key, Entry> answers = new ConcurrentHashMap<>();
    private final Executor background;
    private final LongSupplier clock;

    public OfflineAnswers(Executor background, LongSupplier clock) {
        this.background = background;
        this.clock = clock;
    }

    /** One daemon thread: lookups are rare (offline creators of rules) and must never pile up threads. */
    public static OfflineAnswers background() {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "almighty offline permissions");
            t.setDaemon(true);
            return t;
        });
        return new OfflineAnswers(pool, System::currentTimeMillis);
    }

    /** The answer to use now (see the class comment); asks again in the background when stale. Never waits. */
    public Boolean get(UUID player, String node, long generation, Supplier<Boolean> ask) {
        if (answers.size() > MAX) answers.clear();
        Entry e = answers.computeIfAbsent(new Key(player, node), k -> new Entry());
        Boolean answer;
        boolean start;
        synchronized (e) {
            boolean current = e.answered == generation && e.at != 0;
            boolean fresh = current && clock.getAsLong() - e.at < FRESH_MS;
            start = !fresh && e.asking != generation;
            if (start) e.asking = generation;
            answer = current ? e.last : Boolean.FALSE.equals(e.last) ? Boolean.FALSE : null;
        }
        if (start) {
            try {
                background.execute(() -> {
                    Boolean v = null;
                    boolean ok = false;
                    try {
                        v = ask.get();
                        ok = true;
                    } catch (RuntimeException ignored) {
                        // No handler yet or nodes not gathered: keep what we had.
                    }
                    synchronized (e) {
                        if (ok) e.last = v;
                        // Only the generation that asked is answered by it; a newer one has its own ask running.
                        if (e.asking == generation) {
                            e.asking = -1;
                            if (ok || e.answered != generation) {
                                e.answered = generation;
                                e.at = clock.getAsLong();
                                if (!ok) e.last = Boolean.FALSE.equals(e.last) ? Boolean.FALSE : null;
                            }
                        }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                synchronized (e) {
                    if (e.asking == generation) e.asking = -1;
                }
            }
        }
        return answer;
    }
}
