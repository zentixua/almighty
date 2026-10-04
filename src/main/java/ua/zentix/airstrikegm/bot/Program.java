package ua.zentix.airstrikegm.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Программа бота — шаги одного вызова {@code bot.act}: идут по тикам бота, мгновенные — подряд в одном тике (не
 * больше {@link #MAX_INSTANT}). Ошибка шага останавливает программу. Итог — по шагу на каждый пройденный шаг.
 */
final class Program {
    static final int MAX_INSTANT = 64;

    final long id;
    private final List<Steps.Step> steps;
    private final JsonArray results = new JsonArray();
    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private JsonObject out = new JsonObject();
    private int index;
    private String state = "queued", error;
    /** Вызов уже вернулся, не дождавшись: конец — событием {@code bot_done} в ленте. */
    volatile boolean detached;

    Program(long id, List<Steps.Step> steps) {
        this.id = id;
        this.steps = steps;
    }

    CompletableFuture<JsonObject> done() {
        return done;
    }

    /** Тик программы; true — кончилась (вся, с ошибкой). */
    boolean tick(Bot bot) {
        state = "running";
        int instant = 0;
        while (index < steps.size()) {
            ServerPlayer p = bot.player();
            boolean finished;
            try {
                finished = steps.get(index).tick(bot, p, out);
            } catch (Steps.Failed e) {
                out.addProperty("step", index);
                out.addProperty("error", e.getMessage());
                results.add(out);
                error = "шаг " + index + ": " + e.getMessage();
                state = "failed";
                return true;
            }
            if (!finished) return false;
            out.addProperty("step", index);
            results.add(out);
            out = new JsonObject();
            index++;
            if (++instant >= MAX_INSTANT && index < steps.size()) return false;
        }
        state = "done";
        return true;
    }

    /** Снять программу (новая с {@code replace}, бот ушёл): шаг отпускает то, что держит сам. */
    void cancel(Bot bot, String why) {
        if (index < steps.size() && state.equals("running")) steps.get(index).cancel(bot, bot.player());
        state = "cancelled";
        error = why;
    }

    void complete() {
        done.complete(describe());
    }

    JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("program", id);
        o.addProperty("state", state);
        o.addProperty("steps_done", index);
        o.addProperty("steps", steps.size());
        if (error != null) o.addProperty("error", error);
        o.add("results", results.deepCopy());
        return o;
    }
}
