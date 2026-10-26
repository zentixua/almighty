package ua.zentix.almighty.feed;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/**
 * GM event feed: a ring of the last {@value #CAPACITY} events with sequential numbers. The server thread writes
 * ({@link FeedListeners}, GM work); the bridge thread long-polls: {@link #read} waits for events after
 * {@code after} up to a deadline. Each server run has its own {@code boot}: clients detect a restart from it
 * (sequence numbers start over).
 */
public final class Feed {
    static final int CAPACITY = 4096;

    private final JsonObject[] ring = new JsonObject[CAPACITY];
    private final String boot;
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
    public synchronized void add(String type, JsonObject data) {
        JsonObject event = new JsonObject();
        event.addProperty("seq", next);
        event.addProperty("time", System.currentTimeMillis());
        event.addProperty("type", type);
        for (Map.Entry<String, JsonElement> e : data.entrySet()) event.add(e.getKey(), e.getValue());
        ring[(int) (next % CAPACITY)] = event;
        next++;
        notifyAll();
    }

    /**
     * Events after {@code after}, at most {@code limit}; if none exist, wait up to {@code waitMillis}.
     * An {@code after} beyond the last number (another server run) means "from the beginning".
     * Events evicted from the ring are counted in {@code dropped}.
     */
    public synchronized JsonObject read(long after, int limit, long waitMillis) throws InterruptedException {
        if (after >= next) after = 0;
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (next - 1 <= after) {
            long left = deadline - System.nanoTime();
            if (left <= 0) break;
            wait(Math.max(1, left / 1_000_000L));
        }
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
