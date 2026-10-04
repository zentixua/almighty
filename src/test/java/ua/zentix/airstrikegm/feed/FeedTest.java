package ua.zentix.airstrikegm.feed;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedTest {
    private static JsonObject data(int i) {
        JsonObject d = new JsonObject();
        d.addProperty("i", i);
        return d;
    }

    @Test
    void readsAfterSequence() throws Exception {
        Feed feed = new Feed();
        for (int i = 0; i < 5; i++) feed.add("t", data(i));
        JsonObject page = feed.read(2, 100, 0);
        JsonArray events = page.getAsJsonArray("events");
        assertEquals(3, events.size());
        assertEquals(3, events.get(0).getAsJsonObject().get("seq").getAsLong());
        assertEquals(2, events.get(0).getAsJsonObject().get("i").getAsInt());
        assertEquals(5, page.get("next").getAsLong());
        assertEquals(0, page.get("dropped").getAsLong());
        assertEquals(feed.boot(), page.get("boot").getAsString());
        JsonObject limited = feed.read(0, 2, 0);
        assertEquals(2, limited.getAsJsonArray("events").size());
        assertEquals(2, limited.get("next").getAsLong(), "следующая страница — после второго");
    }

    @Test
    void ringDropsOldest() throws Exception {
        Feed feed = new Feed();
        for (int i = 0; i < Feed.CAPACITY + 10; i++) feed.add("t", data(i));
        JsonObject page = feed.read(0, 5, 0);
        assertEquals(10, page.get("dropped").getAsLong());
        assertEquals(11, page.getAsJsonArray("events").get(0).getAsJsonObject().get("seq").getAsLong());
    }

    @Test
    void afterFromAnotherBootStartsOver() throws Exception {
        Feed feed = new Feed();
        feed.add("t", data(0));
        JsonObject page = feed.read(500, 10, 0);
        assertEquals(1, page.getAsJsonArray("events").size());
    }

    @Test
    void longPollWakesOnEvent() throws Exception {
        Feed feed = new Feed();
        CompletableFuture<JsonObject> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return feed.read(0, 10, 20_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(100);
        feed.add("chat", data(7));
        JsonObject page = waiting.get(10, TimeUnit.SECONDS);
        assertEquals(1, page.getAsJsonArray("events").size());
        assertTrue(page.getAsJsonArray("events").get(0).getAsJsonObject().get("time").getAsLong() > 0);
    }

    @Test
    void emptyWaitReturnsNothing() throws Exception {
        Feed feed = new Feed();
        feed.add("t", data(0));
        JsonObject page = feed.read(1, 10, 50);
        assertEquals(0, page.getAsJsonArray("events").size());
        assertEquals(1, page.get("next").getAsLong(), "следующий запрос — с того же номера");
    }
}
