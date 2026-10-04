package ua.zentix.almighty.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.Api;
import ua.zentix.almighty.GmServer;
import ua.zentix.almighty.bridge.Args;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.rules.Rules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static ua.zentix.almighty.gametest.GmGameTests.call;
import static ua.zentix.almighty.gametest.GmGameTests.check;
import static ua.zentix.almighty.gametest.GmGameTests.gm;
import static ua.zentix.almighty.gametest.GmGameTests.now;

/**
 * Скрипты и правила ведущего в мире. Правила вешаются на общую шину сервера: каждая проверка снимает свои в
 * {@code finally}, иначе упавшая проверка оставила бы их ловить события следующих.
 */
@GameTestHolder(Almighty.ID)
@PrefixGameTestTemplate(false)
public final class GmScriptGameTests {
    private GmScriptGameTests() {}

    private static JsonObject script(GameTestHelper h, String code, JsonObject args, int timeoutMs) {
        JsonObject p = new JsonObject();
        p.addProperty("code", code);
        if (args != null) p.add("args", args);
        if (timeoutMs > 0) p.addProperty("timeout_ms", timeoutMs);
        return now(call(h, "script", p)).getAsJsonObject();
    }

    private static JsonObject rule(GameTestHelper h, String event, String name, String script, Object... more) {
        JsonObject p = new JsonObject();
        p.addProperty("event", event);
        p.addProperty("name", name);
        if (script != null) p.addProperty("script", script);
        for (int i = 0; i < more.length; i += 2) {
            Object v = more[i + 1];
            if (v instanceof Number n) p.addProperty((String) more[i], n);
            else if (v instanceof Boolean b) p.addProperty((String) more[i], b);
            else p.addProperty((String) more[i], String.valueOf(v));
        }
        return now(call(h, "rule.add", p)).getAsJsonObject();
    }

    /** Ошибка метода при разборе параметров (до потока сервера): её текст. */
    private static String rejected(GameTestHelper h, String method, JsonObject params) {
        try {
            Api.methods(gm(h)).get(method).call(new Args(params));
        } catch (RpcException e) {
            return e.getMessage();
        }
        throw new GameTestAssertException(method + " принял " + params);
    }

    private static void drop(GameTestHelper h, String... names) {
        GmServer gm = GmServer.of(h.getLevel().getServer());
        if (gm == null) return;
        for (String name : names) {
            JsonObject p = new JsonObject();
            p.addProperty("name", name);
            try {
                gm.rules().remove(new Args(p));
            } catch (RpcException e) {
                // уже снято (limit) или не успело встать
            }
        }
    }

    private static long feedEnd(GmServer gm) {
        try {
            return gm.feed().read(0, 4096, 0).get("next").getAsLong();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameTestAssertException("лента прервана");
        }
    }

    /** Событие ленты после {@code after} с этим типом и правилом, подходящее под условие; нет — null. */
    private static JsonObject find(GmServer gm, long after, String type, Predicate<JsonObject> test) {
        JsonArray events;
        try {
            events = gm.feed().read(after, 4096, 0).getAsJsonArray("events");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameTestAssertException("лента прервана");
        }
        for (JsonElement e : events) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("type").getAsString().equals(type) && test.test(o)) return o;
        }
        return null;
    }

    private static boolean of(JsonObject event, JsonObject rule) {
        return event.has("rule") && event.get("rule").getAsLong() == rule.get("rule").getAsLong();
    }

    private static JsonObject described(GmServer gm, String name) {
        for (JsonElement e : gm.rules().describe()) {
            JsonObject r = e.getAsJsonObject();
            if (r.has("name") && r.get("name").getAsString().equals(name)) return r;
        }
        return null;
    }

    /**
     * Скрипт достаёт до мира: ставит блок и читает его, {@code println} — в вывод, значение — JSON (позиция
     * {@code [x, y, z]}, блок строкой); {@code state} живёт между скриптами; {@code gm.command} и {@code gm.emit}.
     * Ошибка — {@code ok: false} с номером строки, ошибка разбора — с текстом, бесконечный цикл обрывается сроком.
     */
    @GameTest(template = "floor", batch = "gm_script", skyAccess = true)
    public static void scriptsReachTheGame(GameTestHelper h) {
        GmServer gm = gm(h);
        long after = feedEnd(gm);
        BlockPos p = h.absolutePos(new BlockPos(3, 2, 3));
        JsonObject args = new JsonObject();
        args.addProperty("x", p.getX());
        args.addProperty("y", p.getY());
        args.addProperty("z", p.getZ());
        JsonObject r = script(h, """
                def p = new BlockPos(args.x, args.y, args.z)
                server.overworld().setBlock(p, Blocks.GOLD_BLOCK.defaultBlockState(), 3)
                println "поставил"
                gm.emit([hello: 'мир', at: p])
                [pos: p, block: server.overworld().getBlockState(p), n: 5, time: gm.command('time query daytime')[0].ok]
                """, args, 0);
        check(r.get("ok").getAsBoolean(), "скрипт: " + r);
        JsonObject value = r.getAsJsonObject("value");
        check(value.get("pos").toString().equals("[" + p.getX() + "," + p.getY() + "," + p.getZ() + "]"), "позиция: " + value);
        check(value.get("block").getAsString().equals("minecraft:gold_block") && value.get("n").getAsInt() == 5, "значение: " + value);
        check(value.get("time").getAsBoolean(), "gm.command: " + value);
        check(r.get("output").getAsString().contains("поставил"), "вывод: " + r);
        h.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(3, 2, 3));
        JsonObject emit = find(gm, after, "emit", e -> e.has("script"));
        check(emit != null && emit.getAsJsonObject("data").get("hello").getAsString().equals("мир"), "gm.emit: " + emit);

        String count = "state.gmScriptTest = (state.gmScriptTest ?: 0) + 1";
        check(script(h, count, null, 0).get("value").getAsInt() == 1, "state: первый раз");
        check(script(h, count, null, 0).get("value").getAsInt() == 2, "state не живёт между скриптами");

        JsonObject npe = script(h, "def x = null\nx.foo()", null, 0);
        check(!npe.get("ok").getAsBoolean() && npe.get("line").getAsInt() == 2 && npe.get("error").getAsString().contains("NullPointerException"),
                "ошибка: " + npe);
        JsonObject parse = script(h, "def (", null, 0);
        check(!parse.get("ok").getAsBoolean() && parse.get("error").getAsString().startsWith("Скрипт не разобран"), "разбор: " + parse);
        JsonObject loop = script(h, "while (true) {}", null, 50);
        check(!loop.get("ok").getAsBoolean() && loop.get("error").getAsString().contains("прерван") && loop.get("ms").getAsDouble() >= 50,
                "срок: " + loop);
        h.succeed();
    }

    /**
     * Правила — в том же тике, что событие: страж с приоритетом {@code highest} отменяет появление зомби, другое правило
     * метит свинью командой от её лица. То же имя — замена; снятое правило больше не срабатывает. Поиск событий и
     * ошибка неизвестного имени.
     */
    @GameTest(template = "floor", batch = "gm_rules", skyAccess = true)
    public static void rulesReactInTheSameTick(GameTestHelper h) {
        GmServer gm = gm(h);
        try {
            long after = feedEnd(gm);
            BlockPos base = h.absolutePos(BlockPos.ZERO);
            String near = String.format(Locale.ROOT, "event.entity.blockPosition().closerThan(new BlockPos(%d, %d, %d), 32)",
                    base.getX(), base.getY(), base.getZ());
            JsonObject guard = rule(h, "EntityJoinLevelEvent", "gm-test-guard",
                    "if (event.entity.type == EntityType.ZOMBIE && " + near + ") { event.canceled = true; gm.emit([blocked: event.entity]) }",
                    "priority", "highest");
            rule(h, "EntityJoinLevelEvent", "gm-test-tag",
                    "if (event.entity.type == EntityType.PIG && " + near + ") gm.commandAs(event.entity, 'tag @s add gm_seen')");
            h.spawn(EntityType.ZOMBIE, 4, 2, 4);
            Pig pig = h.spawn(EntityType.PIG, 6, 2, 6);
            check(pig.getTags().contains("gm_seen"), "свинья без метки: " + pig.getTags());
            h.assertEntityNotPresent(EntityType.ZOMBIE);
            JsonObject blocked = find(gm, after, "emit", e -> of(e, guard));
            check(blocked != null && blocked.getAsJsonObject("data").getAsJsonObject("blocked").get("type").getAsString().equals("minecraft:zombie"),
                    "страж не записал зомби: " + blocked);

            JsonObject replaced = rule(h, "EntityJoinLevelEvent", "gm-test-tag",
                    "if (event.entity.type == EntityType.PIG && " + near + ") gm.commandAs(event.entity, 'tag @s add gm_second')");
            long named = gm.rules().describe().asList().stream()
                    .filter(e -> e.getAsJsonObject().has("name") && e.getAsJsonObject().get("name").getAsString().equals("gm-test-tag")).count();
            check(named == 1 && described(gm, "gm-test-tag").get("rule").getAsLong() == replaced.get("rule").getAsLong(), "замена по имени: " + gm.rules().describe());
            Pig second = h.spawn(EntityType.PIG, 8, 2, 8);
            check(second.getTags().contains("gm_second") && !second.getTags().contains("gm_seen"), "заменённое правило: " + second.getTags());
            drop(h, "gm-test-tag");
            Pig third = h.spawn(EntityType.PIG, 10, 2, 10);
            check(third.getTags().isEmpty(), "снятое правило сработало: " + third.getTags());

            JsonObject query = new JsonObject();
            query.addProperty("query", "BlockEvent.BreakEvent");
            JsonArray types = now(call(h, "event.types", query)).getAsJsonArray();
            boolean found = false;
            for (JsonElement e : types) {
                JsonObject t = e.getAsJsonObject();
                found |= t.get("event").getAsString().equals("BlockEvent.BreakEvent") && t.get("cancellable").getAsBoolean()
                        && t.get("mod").getAsString().equals("neoforge");
            }
            check(found, "event.types: " + types);
            // событие по полному имени — то же, что по короткому
            JsonObject full = rule(h, "net.neoforged.neoforge.event.ServerChatEvent", "gm-test-full", null);
            JsonObject brief = rule(h, "ServerChatEvent", "gm-test-full", null);
            check(full.get("event").getAsString().equals("ServerChatEvent") && brief.get("event").getAsString().equals("ServerChatEvent")
                    && full.get("mod").getAsString().equals("neoforge"), "полное и короткое имя: " + full + ", " + brief);
            drop(h, "gm-test-full");
            JsonObject unknown = new JsonObject();
            unknown.addProperty("event", "NoSuchEventAtAll");
            check(rejected(h, "rule.add", unknown).startsWith("Нет события"), "неизвестное событие принято");

            // событие из команды ведущего: команды правила выполняются сразу (а не в очередь внешней команды),
            // вызванное ими то же событие правило не запускает снова
            BlockPos gold = h.absolutePos(new BlockPos(2, 2, 12)), spot = h.absolutePos(new BlockPos(3, 2, 12));
            rule(h, "EntityJoinLevelEvent", "gm-test-nested", String.format(Locale.ROOT, """
                    if (!event.entity.tags.contains('gm_nested') || (state.gmNested ?: 0) >= 3) return
                    state.gmNested = (state.gmNested ?: 0) + 1
                    gm.command('setblock %1$d %2$d %3$d gold_block', 'summon marker %4$d %5$d %6$d {Tags:["gm_nested"]}')
                    state.gmGold = event.level.getBlockState(new BlockPos(%1$d, %2$d, %3$d)).is(Blocks.GOLD_BLOCK)
                    """, gold.getX(), gold.getY(), gold.getZ(), spot.getX(), spot.getY(), spot.getZ()));
            JsonObject summon = new JsonObject();
            JsonArray commands = new JsonArray();
            commands.add(String.format(Locale.ROOT, "summon marker %d %d %d {Tags:[\"gm_nested\"]}", spot.getX(), spot.getY(), spot.getZ()));
            summon.add("commands", commands);
            now(call(h, "command", summon));
            int markers = h.getLevel().getEntities(EntityType.MARKER, e -> e.getTags().contains("gm_nested")).size();
            check(Integer.valueOf(1).equals(gm.state().get("gmNested")) && Boolean.TRUE.equals(gm.state().get("gmGold")) && markers == 2,
                    "правило внутри команды: запусков " + gm.state().get("gmNested") + ", золото " + gm.state().get("gmGold") + ", меток " + markers);
        } finally {
            drop(h, "gm-test-guard", "gm-test-tag", "gm-test-nested", "gm-test-full");
            h.getLevel().getEntities(EntityType.MARKER, e -> e.getTags().contains("gm_nested")).forEach(Entity::discard);
        }
        h.succeed();
    }

    /**
     * Правило на тик: {@code every} — не чаще раза в 5 тиков, {@code limit} — снимается после трёх запусков
     * ({@code rule.off}); сохранённое с миром встаёт из файла и пропадает из него со снятием. Без скрипта — свойства
     * события в ленту.
     */
    @GameTest(template = "floor", batch = "gm_rule_tick", timeoutTicks = 100, skyAccess = true)
    public static void tickRulesKeepPaceAndLimit(GameTestHelper h) {
        GmServer gm = gm(h);
        long after = feedEnd(gm);
        Path store = h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("almighty").resolve("rules.json");
        JsonObject every;
        try {
            every = rule(h, "tick", "gm-test-every", "(state.gmTicks = state.gmTicks ?: []) << server.tickCount",
                    "every", 5, "limit", 3, "persist", true);
            check(read(store).contains("gm-test-every"), "правило не сохранено: " + read(store));
            Rules copy = new Rules(h.getLevel().getServer(), NeoForge.EVENT_BUS, gm.feed(), new HashMap<>(), store);
            copy.load();
            JsonArray loaded = copy.describe();
            copy.stop();
            check(loaded.size() == 1 && loaded.get(0).getAsJsonObject().get("name").getAsString().equals("gm-test-every")
                    && loaded.get(0).getAsJsonObject().get("limit").getAsInt() == 3, "из файла: " + loaded);
            rule(h, "tick", "gm-test-watch", null, "limit", 1);
        } catch (RuntimeException e) {
            drop(h, "gm-test-every", "gm-test-watch");
            throw e;
        }
        h.startSequence()
                .thenWaitUntil(() -> check(gm.state().get("gmTicks") instanceof List<?> l && l.size() == 3, "запусков: " + gm.state().get("gmTicks")))
                .thenExecuteAfter(10, () -> {
                    List<?> ticks = (List<?>) gm.state().get("gmTicks");
                    check(ticks.size() == 3, "после limit запусков: " + ticks);
                    for (int i = 1; i < ticks.size(); i++) {
                        check(((Number) ticks.get(i)).longValue() - ((Number) ticks.get(i - 1)).longValue() >= 5, "every 5: " + ticks);
                    }
                    check(described(gm, "gm-test-every") == null && described(gm, "gm-test-watch") == null, "после limit: " + gm.rules().describe());
                    JsonObject off = find(gm, after, "rule.off", e -> of(e, every));
                    check(off != null && off.get("reason").getAsString().contains("limit"), "rule.off: " + off);
                    JsonObject watch = find(gm, after, "emit", e -> e.has("name") && e.get("name").getAsString().equals("gm-test-watch"));
                    check(watch != null && watch.getAsJsonObject("data").get("server").getAsString().equals("server"), "свойства тика: " + watch);
                    check(!read(store).contains("gm-test-every"), "снятое правило осталось в файле: " + read(store));
                })
                .thenSucceed();
    }

    /**
     * Правило выключается само: запуск дольше своего срока (20 бюджетов, не меньше 50 мс) прерывается (первый такой — только
     * ошибка: в нём могло уйти время на связывание вызовов Groovy, второй — выключает); в среднем больше
     * {@code budget_ms} за тик — выключено со второго запуска (первый, с связыванием вызовов Groovy, не в счёт); ошибка —
     * в ленту с номером строки, {@value Rules#ERRORS_OFF} ошибок подряд — выключено. Выключенное видно в списке с причиной.
     */
    @GameTest(template = "floor", batch = "gm_rule_budget", timeoutTicks = 100, skyAccess = true)
    public static void rulesTurnThemselvesOff(GameTestHelper h) {
        GmServer gm = gm(h);
        long after = feedEnd(gm);
        JsonObject slow, heavy, failing;
        try {
            slow = rule(h, "tick", "gm-test-slow", "long t = System.nanoTime(); while (System.nanoTime() - t < 200_000_000L) {}", "budget_ms", 1);
            heavy = rule(h, "tick", "gm-test-heavy", "long t = System.nanoTime(); while (System.nanoTime() - t < 15_000_000L) {}", "budget_ms", 0.5);
            failing = rule(h, "tick", "gm-test-throw", "throw new IllegalStateException('нарочно')");
        } catch (RuntimeException e) {
            drop(h, "gm-test-slow", "gm-test-heavy", "gm-test-throw");
            throw e;
        }
        h.startSequence()
                .thenWaitUntil(() -> check(described(gm, "gm-test-slow").get("state").getAsString().equals("off")
                        && described(gm, "gm-test-heavy").get("state").getAsString().equals("off")
                        && described(gm, "gm-test-throw").get("state").getAsString().equals("off"), "правила ещё работают: " + gm.rules().describe()))
                .thenExecute(() -> {
                    try {
                        JsonObject slowOff = find(gm, after, "rule.off", e -> of(e, slow));
                        JsonObject slowCold = find(gm, after, "rule.error", e -> of(e, slow));
                        check(slowCold != null && slowCold.get("error").getAsString().contains("прерван"), "первый обрыв — не ошибка: " + slowCold);
                        check(slowOff != null && slowOff.get("reason").getAsString().contains("прерван"), "долгое правило: " + slowOff);
                        check(described(gm, "gm-test-slow").get("fired").getAsInt() == 2, "долгое правило: запусков " + described(gm, "gm-test-slow"));
                        JsonObject heavyOff = find(gm, after, "rule.off", e -> of(e, heavy));
                        check(heavyOff != null && heavyOff.get("reason").getAsString().contains("budget_ms")
                                && described(gm, "gm-test-heavy").get("fired").getAsInt() == 2, "тяжёлое правило: " + heavyOff + ", " + described(gm, "gm-test-heavy"));
                        JsonObject error = find(gm, after, "rule.error", e -> of(e, failing));
                        check(error != null && error.get("error").getAsString().contains("нарочно") && error.get("line").getAsInt() == 1,
                                "ошибка правила: " + error);
                        JsonObject off = find(gm, after, "rule.off", e -> of(e, failing));
                        check(off != null && off.get("reason").getAsString().startsWith(Rules.ERRORS_OFF + " ошибок подряд"), "выключение по ошибкам: " + off);
                        check(described(gm, "gm-test-throw").get("errors").getAsInt() == Rules.ERRORS_OFF, "ошибок: " + described(gm, "gm-test-throw"));
                    } finally {
                        drop(h, "gm-test-slow", "gm-test-heavy", "gm-test-throw");
                    }
                })
                .thenSucceed();
    }

    /**
     * Сохранённое правило, что не встало (нет события, скрипты выключены), не пропадает из файла при следующем
     * сохранении: видно в списке как {@code unloaded}, убирается по имени. Испорченный файл переносится в сторону.
     */
    @GameTest(template = "floor", batch = "gm_rule_store", skyAccess = true)
    public static void savedRulesSurviveFailedLoad(GameTestHelper h) {
        Path dir = h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("almighty");
        Path store = dir.resolve("rules-store-test.json"), broken = dir.resolve("rules-broken-test.json");
        GmServer gm = gm(h);
        Rules rules = new Rules(h.getLevel().getServer(), NeoForge.EVENT_BUS, gm.feed(), new HashMap<>(), store);
        try {
            Files.createDirectories(dir);
            Files.writeString(store, "{\"rules\": [{\"event\": \"NoSuchGmTestEvent\", \"name\": \"gm-test-lost\", \"persist\": true}]}");
            rules.load();
            JsonArray list = rules.describe();
            check(list.size() == 1 && list.get(0).getAsJsonObject().get("state").getAsString().equals("unloaded")
                    && list.get(0).getAsJsonObject().get("error").getAsString().startsWith("Нет события"), "не вставшее: " + list);
            JsonObject p = new JsonObject();
            p.addProperty("event", "tick");
            p.addProperty("name", "gm-test-kept");
            p.addProperty("persist", true);
            rules.add(Rules.parse(new Args(p)));
            check(read(store).contains("gm-test-lost") && read(store).contains("gm-test-kept"), "после сохранения: " + read(store));
            JsonObject r = new JsonObject();
            r.addProperty("name", "gm-test-lost");
            rules.remove(new Args(r));
            check(!read(store).contains("gm-test-lost") && rules.describe().size() == 1, "убранное: " + read(store));

            Files.writeString(broken, "{\"rules\": [");
            Rules other = new Rules(h.getLevel().getServer(), NeoForge.EVENT_BUS, gm.feed(), new HashMap<>(), broken);
            other.load();
            check(!Files.exists(broken) && read(dir.resolve("rules-broken-test.json.bad")).equals("{\"rules\": ["), "испорченный файл не перенесён");
            Files.writeString(broken, "{}");
            new Rules(h.getLevel().getServer(), NeoForge.EVENT_BUS, gm.feed(), new HashMap<>(), broken).load();
            check(!Files.exists(broken) && read(dir.resolve("rules-broken-test.json.bad")).equals("{}"), "файл без списка rules не перенесён");
        } catch (IOException | RpcException e) {
            throw new GameTestAssertException(e.toString());
        } finally {
            rules.stop();
        }
        h.succeed();
    }

    private static String read(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new GameTestAssertException("не прочитать " + p + ": " + e);
        }
    }
}
