package ua.zentix.airstrikegm.feed;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/**
 * Лента событий для ведущего: кольцо последних {@value #CAPACITY} событий с номерами по порядку. Пишет поток сервера
 * ({@link FeedListeners}, работа ведущего), читает поток моста долгим опросом: {@link #read} ждёт событий после
 * номера {@code after}, не дольше срока. {@code boot} — свой у каждого запуска сервера: клиент по нему видит перезапуск
 * (номера пошли с начала).
 */
public final class Feed {
    static final int CAPACITY = 4096;

    private final JsonObject[] ring = new JsonObject[CAPACITY];
    private final String boot;
    /** Номер следующего события; первое — 1. */
    private long next = 1;

    public Feed() {
        byte[] random = new byte[6];
        new SecureRandom().nextBytes(random);
        this.boot = HexFormat.of().formatHex(random);
    }

    public String boot() {
        return boot;
    }

    /** Добавить событие: поля {@code data} — рядом с номером, временем и типом. */
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
     * События с номером больше {@code after}, не больше {@code limit}; пока их нет — ждать до {@code waitMillis}.
     * {@code after} больше последнего номера (другой запуск сервера) читается как «с начала». Ушедшие из кольца
     * события — счётчиком {@code dropped}.
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
