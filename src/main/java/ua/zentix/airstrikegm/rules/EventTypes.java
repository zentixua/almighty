package ua.zentix.airstrikegm.rules;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import ua.zentix.airstrikegm.bridge.RpcException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Все события игровой шины, какие есть у NeoForge и модов: наследники {@link Event} по данным сканирования FML
 * (классы каждого файла мода и их предки), без загрузки классов. Имя события — короткое ({@code BlockEvent.BreakEvent})
 * или полное; {@code tick} — {@code ServerTickEvent.Post}, конец каждого тика сервера.
 */
public final class EventTypes {
    static final String TICK = "net.neoforged.neoforge.event.tick.ServerTickEvent$Post";
    private static final String EVENT = "net.neoforged.bus.api.Event";
    private static volatile Map<String, String> index;

    private EventTypes() {}

    /** Найденное событие: класс и мод, откуда он. */
    public record Found(Class<? extends Event> type, String name, String mod) {}

    /** Имя класса (через {@code $}) → мод; только наследники {@link Event}. */
    static Map<String, String> index() {
        Map<String, String> built = index;
        if (built != null) return built;
        Map<String, String> parent = new HashMap<>(), mod = new HashMap<>();
        for (ModFileScanData data : ModList.get().getAllScanData()) {
            String owner = data.getIModInfoData().stream().flatMap(i -> i.getMods().stream()).map(IModInfo::getModId)
                    .findFirst().orElse("?");
            for (ModFileScanData.ClassData c : data.getClasses()) {
                parent.put(c.clazz().getClassName(), c.parent().getClassName());
                mod.put(c.clazz().getClassName(), owner);
            }
        }
        Map<String, Boolean> isEvent = new HashMap<>();
        Map<String, String> out = new TreeMap<>();
        for (String name : parent.keySet()) {
            if (event(name, parent, isEvent)) out.put(name, mod.get(name));
        }
        index = built = Map.copyOf(out);
        return built;
    }

    private static boolean event(String name, Map<String, String> parent, Map<String, Boolean> memo) {
        Boolean known = memo.get(name);
        if (known != null) return known;
        // цепочку предков — без рекурсии: у событий она бывает длинной
        List<String> chain = new ArrayList<>();
        String at = name;
        Boolean result = null;
        while (result == null) {
            if (EVENT.equals(at)) result = true;
            else if (memo.containsKey(at)) result = memo.get(at);
            else if (!parent.containsKey(at) || chain.size() > 64) result = false;
            else {
                chain.add(at);
                at = parent.get(at);
            }
        }
        for (String c : chain) memo.put(c, result);
        return result;
    }

    /** Короткое имя: класс без пакета, вложенный — через точку ({@code BlockEvent.BreakEvent}). */
    public static String shortName(String className) {
        return className.substring(className.lastIndexOf('.') + 1).replace('$', '.');
    }

    /** Событие по имени; нет или неоднозначно — ошибка со списком похожих. */
    public static Found resolve(String name) throws RpcException {
        String query = name.strip();
        if (query.equalsIgnoreCase("tick")) query = TICK;
        String dotted = query.replace('$', '.');
        Map<String, String> all = index();
        List<String> hits = new ArrayList<>();
        for (String c : all.keySet()) {
            String d = c.replace('$', '.');
            if (d.equals(dotted)) {
                hits.clear();
                hits.add(c);
                break;
            }
            if (d.endsWith("." + dotted)) hits.add(c);
        }
        if (hits.isEmpty()) {
            throw RpcException.notFound("Нет события «" + name + "»" + similar(dotted, all) + "; поиск — event.types");
        }
        if (hits.size() > 1) {
            List<String> names = hits.stream().map(c -> c.replace('$', '.')).limit(10).toList();
            throw RpcException.badRequest("Событие «" + name + "» неоднозначно: " + String.join(", ", names));
        }
        String className = hits.get(0);
        Class<?> type;
        try {
            type = Class.forName(className, false, EventTypes.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw RpcException.notFound("Класс события " + className + " не загружается: " + e);
        }
        if (!Event.class.isAssignableFrom(type)) throw RpcException.badRequest(className + " — не событие");
        if (IModBusEvent.class.isAssignableFrom(type)) {
            throw RpcException.badRequest(shortName(className) + " — событие загрузки модов (шина мода), после запуска его не бывает");
        }
        @SuppressWarnings("unchecked")
        Class<? extends Event> event = (Class<? extends Event>) type;
        return new Found(event, shortName(className), all.get(className));
    }

    /** События, в имени которых есть {@code query} (без учёта регистра): имя, полный класс, мод, отменяемое ли. */
    public static List<Map<String, Object>> search(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT).replace('$', '.');
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : index().entrySet()) {
            if (!e.getKey().replace('$', '.').toLowerCase(Locale.ROOT).contains(q)) continue;
            if (out.size() == limit) break;
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("event", shortName(e.getKey()));
            row.put("class", e.getKey());
            row.put("mod", e.getValue());
            try {
                Class<?> type = Class.forName(e.getKey(), false, EventTypes.class.getClassLoader());
                row.put("cancellable", ICancellableEvent.class.isAssignableFrom(type));
                if (IModBusEvent.class.isAssignableFrom(type)) row.put("mod_bus", true);
                if (java.lang.reflect.Modifier.isAbstract(type.getModifiers())) row.put("abstract", true);
            } catch (ClassNotFoundException | LinkageError ex) {
                row.put("error", "не загружается");
            }
            out.add(row);
        }
        return out;
    }

    private static String similar(String dotted, Map<String, String> all) {
        String tail = dotted.substring(dotted.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        List<String> like = all.keySet().stream().filter(c -> c.toLowerCase(Locale.ROOT).contains(tail))
                .map(EventTypes::shortName).limit(8).toList();
        return like.isEmpty() ? "" : " (похожие: " + String.join(", ", like) + ")";
    }
}
