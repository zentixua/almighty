package ua.zentix.almighty.rules;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import ua.zentix.almighty.GmConfig;
import ua.zentix.almighty.bot.Bot;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.feed.Feed;
import ua.zentix.almighty.script.Json;
import ua.zentix.almighty.script.ScriptApi;
import ua.zentix.almighty.script.Scripts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Правила ведущего: реакция на любое событие игровой шины прямо на сервере, в том же тике. Без скрипта — свойства
 * события в ленту ({@code emit}); со скриптом — Groovy с переменной {@code event}, его {@code gm.emit} пишет в ленту,
 * {@code event.canceled = true} отменяет отменяемое. Правило выключается само и пишет {@code rule.off}: в среднем
 * дольше {@code budget_ms} за тик по последним 20 тикам, один запуск дольше {@link #runLimitMs} (его обрывает срок
 * скрипта; первый такой обрыв — только ошибка), {@value #ERRORS_OFF} ошибок подряд, больше {@value #EMITS_PER_SECOND} записей в ленту за 20 тиков, событие
 * не из потока сервера. Ботов, которых его скрипт вёл ({@code bot.act}, {@code bot.send}), выключение останавливает:
 * программы сняты, клавиши отпущены. Первый запуск в счёт времени не идёт: в нём Groovy связывает вызовы (до десятков мс). Время —
 * настенное: пауза сборщика мусора внутри запуска в него попадает, поэтому счёт средний, а не по одному тику. Всё — в
 * потоке сервера: разбор и компиляция — в потоке моста ({@link #parse}), остальное — {@code GmServer.onMain}.
 */
public final class Rules {
    private static final Logger LOG = LogUtils.getLogger();
    static final int MAX_RULES = 100;
    public static final int ERRORS_OFF = 10;
    static final int EMITS_PER_SECOND = 200;
    /** Окно счёта времени и записей в ленту, тиков. */
    static final int WINDOW = 20;
    /** Наибольший текст скрипта (разового и правила). */
    public static final int MAX_SCRIPT = 64 * 1024;

    private final MinecraftServer server;
    private final IEventBus bus;
    private final Feed feed;
    private final Map<String, Object> state;
    private final Path store;
    private final Map<Long, Rule> rules = new LinkedHashMap<>();
    /** Сохранённые правила, что не встали при запуске (скрипты выключены, нет события): в файле остаются, пока их не уберут. */
    private final List<JsonObject> unloaded = new ArrayList<>();
    private long next = 1;
    private boolean loading;

    public Rules(MinecraftServer server, IEventBus bus, Feed feed, Map<String, Object> state, Path store) {
        this.server = server;
        this.bus = bus;
        this.feed = feed;
        this.state = state;
        this.store = store;
    }

    /** Разобранное правило: параметры как пришли (для сохранения), событие, скрипт. */
    public record Spec(JsonObject params, EventTypes.Found event, String code, Scripts.Compiled script, String name, int every,
                       long limit, EventPriority priority, boolean canceled, double budgetMs, boolean persist) {}

    /** Разбор и компиляция в потоке моста: мир не трогает. */
    public static Spec parse(Args a) throws RpcException {
        JsonObject params = new JsonObject();
        for (String key : List.of("event", "script", "name", "every", "limit", "priority", "canceled", "budget_ms", "persist")) {
            if (a.has(key)) params.add(key, a.raw().get(key));
        }
        EventTypes.Found event = EventTypes.resolve(a.string("event"));
        String name = a.string("name", null);
        if (name != null && (name.isBlank() || name.length() > 64)) throw RpcException.badRequest("name: от 1 до 64 символов");
        int every = a.integer("every", 0, 0, 72_000);
        long limit = a.longValue("limit", 0, 0, 1_000_000_000L);
        EventPriority priority;
        try {
            priority = EventPriority.valueOf(a.string("priority", "lowest").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw RpcException.badRequest("priority: highest, high, normal, low или lowest");
        }
        boolean canceled = a.bool("canceled", false);
        double budget = a.number("budget_ms", 5, 0.1, 1000);
        boolean persist = a.bool("persist", false);
        String code = a.string("script", null);
        Scripts.Compiled script = null;
        if (code != null) {
            if (!GmConfig.SCRIPTS_ENABLED.get()) throw scriptsOff();
            if (code.length() > MAX_SCRIPT) throw RpcException.badRequest("script: не больше " + MAX_SCRIPT / 1024 + " КБ");
            try {
                script = Scripts.compile("rule", code, runLimitMs(budget));
            } catch (Scripts.CompileError e) {
                throw RpcException.badRequest("Скрипт правила не разобран: " + e.getMessage());
            }
        }
        return new Spec(params, event, code, script, name, every, limit, priority, canceled, budget, persist);
    }

    /** Срок одного запуска: бюджет всего окна, не меньше 50 мс и не больше 10 с. */
    static long runLimitMs(double budgetMs) {
        return Math.min(10_000, Math.max(50, (long) Math.ceil(budgetMs * WINDOW)));
    }

    public static RpcException scriptsOff() {
        return RpcException.unavailable("Скрипты выключены: scripts.enabled = false в config/almighty-common.toml");
    }

    /** Поставить правило (поток сервера). То же имя — замена прежнего. */
    public JsonObject add(Spec spec) throws RpcException {
        Rule old = spec.name() == null ? null : byName(spec.name());
        if (old == null && rules.size() >= MAX_RULES) {
            if (spec.script() != null) spec.script().close();
            throw RpcException.conflict("Правил уже " + MAX_RULES + ": убрать ненужные (rule.remove)");
        }
        Rule rule = new Rule(next, spec);
        try {
            rule.listen();
        } catch (IllegalArgumentException e) {
            if (spec.script() != null) spec.script().close();
            throw RpcException.badRequest("Шина не принимает " + spec.event().name() + ": " + e.getMessage());
        }
        next++;
        if (old != null) drop(old);
        boolean replacedSaved = spec.name() != null && unloaded.removeIf(u -> spec.name().equals(savedName(u)));
        rules.put(rule.id, rule);
        LOG.info("Ведущий: правило №{} на {}{}", rule.id, spec.event().name(), spec.code() == null ? "" : ", скрипт:\n" + spec.code());
        if (spec.persist() || old != null && old.spec.persist() || replacedSaved) save();
        return rule.describe();
    }

    /** Убрать по номеру или имени (поток сервера). */
    public JsonObject remove(Args a) throws RpcException {
        Rule rule;
        String name = a.has("id") ? null : a.string("name", null);
        if (a.has("id")) rule = rules.get(a.longValue("id", 0, 1, Long.MAX_VALUE));
        else if (name != null) rule = byName(name);
        else throw RpcException.badRequest("rule.remove: id или name");
        if (rule == null && name != null && unloaded.removeIf(u -> name.equals(savedName(u)))) {
            save();
            JsonObject out = new JsonObject();
            out.addProperty("removed", name);
            return out;
        }
        if (rule == null) throw RpcException.notFound("Нет такого правила; список — rules");
        drop(rule);
        if (rule.spec.persist()) save();
        JsonObject out = new JsonObject();
        out.addProperty("removed", rule.id);
        return out;
    }

    public JsonArray describe() {
        JsonArray out = new JsonArray();
        for (Rule r : rules.values()) out.add(r.describe());
        for (JsonObject u : unloaded) {
            JsonObject d = new JsonObject();
            if (savedName(u) != null) d.addProperty("name", savedName(u));
            d.addProperty("state", "unloaded");
            d.add("error", u.get("error"));
            d.add("spec", u.get("spec"));
            out.add(d);
        }
        return out;
    }

    /** Сохранённые правила этого мира (запуск сервера, поток сервера): ошибка одного — в ленту и лог, остальные встают. */
    public void load() {
        if (!Files.isRegularFile(store)) return;
        JsonArray saved;
        try {
            saved = JsonParser.parseString(Files.readString(store, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("rules");
            if (saved == null) throw new IllegalStateException("нет списка rules");
        } catch (IOException | RuntimeException e) {
            // следующее сохранение перепишет файл: испорченный — в сторону
            Path bad = store.resolveSibling(store.getFileName() + ".bad");
            LOG.error("Правила ведущего не прочитаны из {}, файл перенесён в {}", store, bad, e);
            try {
                Files.move(store, bad, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                LOG.error("Не перенесён {}", store, ex);
            }
            return;
        }
        loading = true;
        try {
            for (JsonElement e : saved) {
                try {
                    Spec spec = parse(new Args(e.getAsJsonObject()));
                    add(spec);
                } catch (RpcException | RuntimeException ex) {
                    LOG.warn("Сохранённое правило не встало: {} — {}", e, ex.getMessage());
                    JsonObject d = new JsonObject();
                    d.add("spec", e);
                    d.addProperty("error", ex.getMessage());
                    feed.add("rule.error", d);
                    if (e.isJsonObject()) unloaded.add(d.deepCopy());
                }
            }
        } finally {
            loading = false;
        }
    }

    /** Остановка сервера: правила снимаются с шины (статической — в одиночной игре она переживает мир). */
    public void stop() {
        for (Rule r : List.copyOf(rules.values())) r.unlisten();
        rules.clear();
    }

    private Rule byName(String name) {
        for (Rule r : rules.values()) {
            if (name.equals(r.spec.name())) return r;
        }
        return null;
    }

    private void drop(Rule rule) {
        rule.unlisten();
        rule.driven.clear();
        rules.remove(rule.id);
    }

    private static String savedName(JsonObject unloaded) {
        JsonElement name = unloaded.getAsJsonObject("spec").get("name");
        return name != null && name.isJsonPrimitive() ? name.getAsString() : null;
    }

    private void save() {
        if (loading) return;
        JsonArray list = new JsonArray();
        for (Rule r : rules.values()) {
            if (r.spec.persist()) list.add(r.spec.params());
        }
        for (JsonObject u : unloaded) list.add(u.get("spec"));
        JsonObject root = new JsonObject();
        root.add("rules", list);
        try {
            Files.createDirectories(store.getParent());
            Path tmp = store.resolveSibling(store.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("Правила ведущего не сохранены в {}", store, e);
        }
    }

    /** Одно правило: подписка, счёт, бюджет. Поля — только поток сервера. */
    private final class Rule {
        final long id;
        final Spec spec;
        final ScriptApi api;
        Consumer<? extends Event> listener;
        String off;
        long fired, emitted, errors;
        int errorsInRow;
        long lastRun = Long.MIN_VALUE;
        /** Начала окон счёта (номер тика; до первого — раньше нуля на окно) и ошибки в ленте. */
        long timeTick = -WINDOW, timeNanos, emitTick = -WINDOW, lastErrorTick = -WINDOW;
        int windowEmits;
        boolean running, warm;
        volatile boolean wrongThread;
        /** Боты, которых скрипт правила вёл ({@code act}, {@code send}): выключилось правило — они останавливаются. */
        final Set<Bot> driven = Collections.newSetFromMap(new IdentityHashMap<>());

        Rule(long id, Spec spec) {
            this.id = id;
            this.spec = spec;
            this.api = new ScriptApi(server, true, this::emit);
        }

        void listen() {
            listener = subscribe(spec.event().type());
        }

        private <T extends Event> Consumer<T> subscribe(Class<T> type) {
            Consumer<T> c = this::fire;
            bus.addListener(spec.priority(), spec.canceled(), type, c);
            return c;
        }

        void unlisten() {
            if (listener != null) bus.unregister(listener);
            listener = null;
            if (spec.script() != null) spec.script().close();
        }

        void fire(Event event) {
            if (off != null || running) return;
            if (!server.isSameThread()) {
                if (!wrongThread) {
                    wrongThread = true;
                    String thread = Thread.currentThread().getName();
                    server.execute(() -> turnOff("событие пришло не из потока сервера (" + thread + "): правила работают только в нём"));
                }
                return;
            }
            long tick = server.getTickCount();
            if (spec.every() > 0 && lastRun != Long.MIN_VALUE && tick - lastRun < spec.every()) return;
            lastRun = tick;
            if (tick - timeTick >= WINDOW) {
                timeTick = tick;
                timeNanos = 0;
            }
            running = true;
            long start = System.nanoTime();
            try {
                if (spec.script() == null) {
                    emit(Json.fields(event));
                } else {
                    Set<Bot> outer = Bot.driving(driven);
                    Scripts.Result r;
                    try {
                        r = Scripts.run(spec.script(), Map.of("event", event, "server", server, "gm", api, "state", state, "rule", id));
                    } finally {
                        Bot.driving(outer);
                    }
                    if (r.ok()) errorsInRow = 0;
                    else error(r, tick);
                }
            } finally {
                running = false;
                if (warm) timeNanos += System.nanoTime() - start;
                warm = true;
                fired++;
            }
            if (off != null) return;
            if (timeNanos > spec.budgetMs() * 1e6 * WINDOW) {
                turnOff(String.format(Locale.ROOT, "%.1f мс за %d тиков при бюджете %.1f мс за тик (budget_ms)", timeNanos / 1e6, WINDOW, spec.budgetMs()));
            } else if (spec.limit() > 0 && fired >= spec.limit()) {
                drop(this);
                JsonObject d = who();
                d.addProperty("reason", "исчерпан limit = " + spec.limit());
                feed.add("rule.off", d);
                if (spec.persist()) save();
            }
        }

        private void error(Scripts.Result r, long tick) {
            errors++;
            errorsInRow++;
            if (errorsInRow >= ERRORS_OFF) {
                turnOff(ERRORS_OFF + " ошибок подряд, последняя: " + r.message() + (r.line() > 0 ? " (строка " + r.line() + ")" : ""));
                return;
            }
            // первый запуск может съесть срок на связывании вызовов Groovy: его обрыв — ошибка, выключает — второй
            if (r.timedOut() && warm) {
                turnOff(r.message() + " (budget_ms)");
                return;
            }
            // ошибку — в ленту не чаще раза в секунду: у частого события их сотни
            if (tick - lastErrorTick < WINDOW) return;
            lastErrorTick = tick;
            JsonObject d = who();
            d.addProperty("error", r.message());
            if (r.line() > 0) d.addProperty("line", r.line());
            d.addProperty("errors", errors);
            feed.add("rule.error", d);
        }

        void emit(JsonElement data) {
            long tick = server.getTickCount();
            if (tick - emitTick >= WINDOW) {
                emitTick = tick;
                windowEmits = 0;
            }
            if (++windowEmits > EMITS_PER_SECOND) {
                if (off == null) turnOff("больше " + EMITS_PER_SECOND + " записей в ленту за " + WINDOW + " тиков: сузить условие или задать every");
                return;
            }
            emitted++;
            JsonObject d = who();
            d.add("data", data);
            feed.add("emit", d);
        }

        /** Выключить: снять с шины, оставить в списке с причиной (видно в rules); сохранённое встанет при перезапуске. */
        void turnOff(String reason) {
            if (off != null) return;
            off = reason;
            if (listener != null) bus.unregister(listener);
            listener = null;
            LOG.warn("Правило ведущего №{} выключено: {}", id, reason);
            JsonObject d = who();
            d.addProperty("reason", reason);
            JsonArray stopped = new JsonArray();
            // «мёртвая рука»: бот автопилота не держит последний ввод (самолёт с рулём вниз ушёл в море)
            for (Bot bot : driven) {
                if (!bot.online()) continue;
                bot.stop("правило " + (spec.name() != null ? "«" + spec.name() + "»" : "№" + id) + " выключено: " + reason);
                stopped.add(bot.name());
            }
            driven.clear();
            if (!stopped.isEmpty()) d.add("bots_stopped", stopped);
            feed.add("rule.off", d);
        }

        JsonObject who() {
            JsonObject d = new JsonObject();
            d.addProperty("rule", id);
            if (spec.name() != null) d.addProperty("name", spec.name());
            return d;
        }

        JsonObject describe() {
            JsonObject out = who();
            out.addProperty("event", spec.event().name());
            out.addProperty("mod", spec.event().mod());
            out.addProperty("state", off == null ? "on" : "off");
            if (off != null) out.addProperty("reason", off);
            out.addProperty("script", spec.code() != null);
            if (spec.every() > 0) out.addProperty("every", spec.every());
            if (spec.limit() > 0) out.addProperty("limit", spec.limit());
            out.addProperty("priority", spec.priority().name().toLowerCase(Locale.ROOT));
            if (spec.canceled()) out.addProperty("canceled", true);
            out.addProperty("budget_ms", spec.budgetMs());
            if (spec.persist()) out.addProperty("persist", true);
            out.addProperty("fired", fired);
            out.addProperty("emitted", emitted);
            out.addProperty("errors", errors);
            return out;
        }
    }
}
