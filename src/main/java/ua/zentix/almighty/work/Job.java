package ua.zentix.almighty.work;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Работа ведущего под бюджетом тика (постройка, откат, снимок): шаги в потоке сервера, пока есть время, — не меньше
 * одной единицы за тик, единица ограничена (десятки мест). Конец — {@link #done()}: мост ждёт его, лента получает
 * событие {@code job} (кроме снимков: {@link #announce}).
 */
public abstract class Job {
    private static final Logger LOG = LogUtils.getLogger();

    public enum State {
        /** Ждёт загрузки своих чанков. */
        WAITING,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED;

        public boolean finished() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String kind;
    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private long id;
    private State state = State.WAITING;
    private String error;

    protected Job(String kind) {
        this.kind = kind;
    }

    void assign(long id) {
        this.id = id;
        submitted();
    }

    /** Работа получила номер и встала в очередь (поток сервера). */
    protected void submitted() {}

    public long id() {
        return id;
    }

    public String kind() {
        return kind;
    }

    public State state() {
        return state;
    }

    /** Будущее с описанием работы, когда она кончилась (удачно или нет). */
    public CompletableFuture<JsonObject> done() {
        return done;
    }

    /**
     * Работать, пока есть время (хотя бы одну единицу). Состояние меняют {@link #running}, {@link #finish},
     * {@link #fail}.
     */
    protected abstract void step(Budget budget);

    /** Отпустить то, что держит работа (тикеты чанков); зовётся один раз при любом конце. */
    protected abstract void release();

    /** Подробности для описания: прогресс, итог. */
    protected abstract void details(JsonObject out);

    /**
     * Итог, который получает только ждущий вызов, а не список работ и лента (картинка снимка): зовётся один раз при
     * конце; работа может тут же его забыть.
     */
    protected void payload(JsonObject out) {}

    /** Сколько мест работа держит в памяти (снимки отката); 0 — ничего. После конца — только то, что нужно для отката. */
    public long retained() {
        return 0;
    }

    /** Сообщать ли конец работы в ленту (событие {@code job}). */
    public boolean announce() {
        return true;
    }

    /** Выбросить то, что держится после конца (старые снимки при нехватке места). */
    public void dropRetained() {}

    protected final void running() {
        if (state == State.WAITING) state = State.RUNNING;
    }

    protected final void finish() {
        end(State.DONE, null);
    }

    protected final void fail(String message) {
        end(State.FAILED, message);
    }

    final void cancel() {
        end(State.CANCELLED, null);
    }

    final void run(Budget budget) {
        try {
            step(budget);
        } catch (RuntimeException e) {
            LOG.warn("Работа ведущего {} №{} упала", kind, id, e);
            fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void end(State to, String message) {
        if (state.finished()) return;
        state = to;
        error = message;
        release();
        JsonObject result = describe();
        if (to == State.DONE) payload(result);
        done.complete(result);
    }

    public JsonObject describe() {
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("kind", kind);
        out.addProperty("state", state.id());
        if (error != null) out.addProperty("error", error);
        details(out);
        return out;
    }
}
