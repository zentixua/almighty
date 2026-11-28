package ua.zentix.almighty.policy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Answers of a permission handler about players who are offline, asked in the background and cached. A handler
 * may load an offline user from its storage before answering (LuckPerms does), which on the server thread would
 * stall the tick (stability audit M10): {@link #get} never waits. Until the first answer arrives it returns null
 * (the role ladder decides); an answer older than {@link #FRESH_MS} is still returned while a new one is asked.
 * A change of the policy or a role ({@code generation}) drops every answer: the ladder decides until asked again.
 */
public final class OfflineAnswers {
    /** How long an answer counts as current before it is asked again. */
    public static final long FRESH_MS = 30_000;
    /** At most this many answers kept; beyond it the cache starts over (players, not nodes, make it grow). */
    static final int MAX = 4096;

    private record Key(UUID player, String node) {}

    private static final class Entry {
        volatile Boolean value;
        volatile long at;
        volatile boolean asking;
        volatile long generation;
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

    /** The last answer for this player and node, or null before one arrived; asks again in the background when stale. */
    public Boolean get(UUID player, String node, long generation, Supplier<Boolean> ask) {
        if (answers.size() > MAX) answers.clear();
        Entry e = answers.computeIfAbsent(new Key(player, node), k -> new Entry());
        if (e.generation != generation) {
            e.generation = generation;
            e.value = null;
            e.at = 0;
        }
        long now = clock.getAsLong();
        boolean stale = e.at == 0 || now - e.at >= FRESH_MS;
        if (stale && !e.asking) {
            synchronized (e) {
                if (!e.asking) {
                    e.asking = true;
                    try {
                        background.execute(() -> {
                            try {
                                Boolean v = ask.get();
                                if (e.generation == generation) e.value = v;
                            } catch (RuntimeException ignored) {
                                // No handler yet or nodes not gathered: keep what we had (null: the ladder decides).
                            } finally {
                                e.at = clock.getAsLong();
                                e.asking = false;
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        e.asking = false;
                    }
                }
            }
        }
        return e.value;
    }
}
