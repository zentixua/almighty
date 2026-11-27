package ua.zentix.almighty.feed;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GM event feed: a ring of the last {@value #CAPACITY} events with sequential numbers. The server thread writes
 * ({@link FeedListeners}, GM work); the bridge thread long-polls: {@link #read} waits for events after
 * {@code after} up to a deadline. Each server run has its own {@code boot}: clients detect a restart from it
 * (sequence numbers start over). A lock and condition rather than {@code synchronized}/{@code wait}: waiting long polls
 * then never pin a carrier thread if the bridge moves to virtual threads (Java 21, JEP 491).
 */
public final class Feed {
    static final int CAPACITY = 4096;

    private final JsonObject[] ring = new JsonObject[CAPACITY];
    private final String boot;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition added = lock.newCondition();
    /** Next event number; the first is 1. */
    private long next = 1;

    public Feed() {
        byte[] random = new byte[6];
        new SecureRandom().nextBytes(random);
        this.boot = HexFormat.of().formatHex(random);
    }

    public String boot() {
        return boot;
    }

    /** Add an event: {@code data} fields go alongside the sequence number, time and type. */
    public void add(String type, JsonObject data) {
        lock.lock();
        try {
            JsonObject event = new JsonObject();
            event.addProperty("seq", next);
            event.addProperty("time", System.currentTimeMillis());
            event.addProperty("type", type);
            for (Map.Entry<String, JsonElement> e : data.entrySet()) event.add(e.getKey(), e.getValue());
            ring[(int) (next % CAPACITY)] = event;
            next++;
            added.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Events after {@code after}, at most {@code limit}; if none exist, wait up to {@code waitMillis}.
     * An {@code after} beyond the last number (another server run) means "from the beginning".
     * Events evicted from the ring are counted in {@code dropped}.
     */
    public JsonObject read(long after, int limit, long waitMillis) throws InterruptedException {
        lock.lock();
        try {
            return readLocked(after, limit, waitMillis);
        } finally {
            lock.unlock();
        }
    }

    private JsonObject readLocked(long after, int limit, long waitMillis) throws InterruptedException {
        if (after >= next) after = 0;
        long left = TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (next - 1 <= after && left > 0) left = added.awaitNanos(left);
        long oldest = Math.max(1, next - CAPACITY);
        long from = Math.max(after + 1, oldest);
        JsonArray events = new JsonArray();
        long seq = from;
        for (; seq < next && events.size() < limit; seq++) events.add(ring[(int) (seq % CAPACITY)]);
        JsonObject page = new JsonObject();
        page.addProperty("boot", boot);
        page.addProperty("next", seq - 1);
        page.addProperty("dropped", from - after - 1);
        page.add("events", events);
        return page;
    }
}
