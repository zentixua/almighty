package ua.zentix.almighty.fabric;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.platform.GameEvents;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * {@link GameEvents} on Fabric: rules subscribe to any Fabric API or mod callback, that is any public static
 * {@code Event<T>} field of a loaded mod (found by scanning the mods' classes whose names mention an event or a
 * callback, without running them). An event's name is {@code <Owner>.<FIELD>} ({@code ServerTickEvents.END_SERVER_TICK},
 * {@code PlayerBlockBreakEvents.BEFORE}); a callback interface with one {@code EVENT} also answers to its own name
 * ({@code UseBlockCallback}).
 *
 * <p>This is narrower than NeoForge's "any event": Fabric has no single bus, so only callbacks are reachable, not
 * vanilla code paths without one; Fabric callbacks cannot be unregistered, so each callback gets one dispatcher,
 * registered once, that forwards to the current rules; priority and "receive canceled" have no Fabric equivalent and
 * are ignored. A rule sees a {@link FabricEvent}: the callback's arguments by name, plus {@code player}, {@code level},
 * {@code pos} and {@code entity} when an argument has that type. Cancelling makes the callback return its "stop"
 * value: {@code false} for a boolean callback (Fabric's {@code ALLOW_*}/{@code BEFORE} convention),
 * {@link InteractionResult#FAIL}, or {@link TriState#FALSE}; a callback returning anything else cannot be observed.
 */
final class FabricEvents implements GameEvents {
    private static final Logger LOG = LoggerFactory.getLogger("almighty");
    static final String TICK = "ServerTickEvents.END_SERVER_TICK";

    /** A callback: its {@code Event} field, listener interface and owning mod. */
    record Callback(String name, Field field, Class<?> listener, Method method, String mod) {}

    private volatile Map<String, Callback> index;
    private final Map<Field, Dispatcher> dispatchers = new ConcurrentHashMap<>();

    // --- Discovery ---

    private Map<String, Callback> index() {
        Map<String, Callback> built = index;
        if (built != null) return built;
        synchronized (this) {
            if (index != null) return index;
            Map<String, Callback> out = new TreeMap<>();
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String id = mod.getMetadata().getId();
                if (id.equals("minecraft") || id.equals("java")) continue;
                for (Path root : mod.getRootPaths()) scan(root, id, out);
            }
            index = Map.copyOf(out);
            LOG.info("GM: {} Fabric callbacks for rules", out.size());
            return index;
        }
    }

    private static void scan(Path root, String mod, Map<String, Callback> out) {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> {
                String f = p.getFileName() == null ? "" : p.getFileName().toString();
                return f.endsWith(".class") && !f.equals("module-info.class") && (f.contains("Event") || f.contains("Callback"));
            }).forEach(p -> {
                String name = root.relativize(p).toString().replace('\\', '/');
                name = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                // Mixin classes must never be loaded directly, and client classes do not exist on a server.
                if (name.contains(".mixin.") || name.contains(".client.")) return;
                collect(name, mod, out);
            });
        } catch (IOException | RuntimeException e) {
            LOG.debug("GM: cannot scan {} of {}: {}", root, mod, e.toString());
        }
    }

    private static void collect(String className, String mod, Map<String, Callback> out) {
        Class<?> owner;
        try {
            owner = Class.forName(className, false, FabricEvents.class.getClassLoader());
        } catch (Throwable e) {
            // Client-only classes on a server (Knot refuses them with a RuntimeException), Mixin's refusal (an Error),
            // broken references: such a class has no callbacks for rules.
            return;
        }
        Field[] fields;
        try {
            fields = owner.getFields();
        } catch (Throwable e) {
            return;
        }
        List<Field> events = new ArrayList<>();
        for (Field f : fields) {
            if (f.getType() == Event.class && Modifier.isStatic(f.getModifiers()) && f.getDeclaringClass() == owner) events.add(f);
        }
        for (Field f : events) {
            if (!(f.getGenericType() instanceof ParameterizedType pt) || !(pt.getActualTypeArguments()[0] instanceof Class<?> listener)) continue;
            Method method = single(listener);
            if (method == null) continue;
            String shortOwner = GameEvents.shortName(owner.getName());
            out.put(shortOwner + "." + f.getName(), new Callback(shortOwner + "." + f.getName(), f, listener, method, mod));
            if (events.size() == 1 && f.getName().equals("EVENT")) out.putIfAbsent(shortOwner, new Callback(shortOwner, f, listener, method, mod));
        }
    }

    /** The one abstract method of a functional callback interface, or null. */
    private static Method single(Class<?> listener) {
        if (!listener.isInterface()) return null;
        Method found = null;
        for (Method m : listener.getMethods()) {
            if (!Modifier.isAbstract(m.getModifiers())) continue;
            if (found != null) return null;
            found = m;
        }
        return found;
    }

    private static boolean observable(Method m) {
        Class<?> r = m.getReturnType();
        return r == void.class || r == boolean.class || r == InteractionResult.class || r == TriState.class;
    }

    // --- GameEvents ---

    @Override
    public Found resolve(String name) throws RpcException {
        String query = name.strip();
        if (query.equalsIgnoreCase("tick")) query = TICK;
        Map<String, Callback> all = index();
        Callback hit = all.get(query);
        if (hit == null) hit = qualified(query, all);
        if (hit == null) {
            List<String> tail = all.keySet().stream().filter(k -> k.endsWith("." + name.strip()) || k.equalsIgnoreCase(name.strip())).toList();
            if (tail.size() == 1) hit = all.get(tail.get(0));
            else if (tail.size() > 1) throw RpcException.badRequest("Event \"" + name + "\" is ambiguous: " + String.join(", ", tail.stream().limit(10).toList()));
        }
        if (hit == null) throw RpcException.notFound("No event \"" + name + "\" among Fabric callbacks" + similar(name, all) + "; search with event.types");
        if (!observable(hit.method())) {
            throw RpcException.badRequest(hit.name() + " returns " + hit.method().getReturnType().getSimpleName()
                    + "; rules observe only callbacks that return nothing, a boolean, an InteractionResult or a TriState");
        }
        return new Found(hit.listener(), hit.name(), hit.mod(), hit);
    }

    /** A fully qualified name, {@code <owner class>.<FIELD>} (as event.types gives it in {@code class}). */
    private static Callback qualified(String query, Map<String, Callback> all) {
        int dot = query.lastIndexOf('.');
        if (dot <= 0) return null;
        String owner = query.substring(0, dot), field = query.substring(dot + 1);
        Callback c = all.get(GameEvents.shortName(owner) + "." + field);
        return c != null && c.field().getDeclaringClass().getName().replace('$', '.').equals(owner.replace('$', '.')) ? c : null;
    }

    @Override
    public List<Map<String, Object>> search(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Callback c : index().values()) {
            if (!c.name().toLowerCase(Locale.ROOT).contains(q)) continue;
            if (out.size() == limit) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event", c.name());
            row.put("class", c.field().getDeclaringClass().getName() + "." + c.field().getName());
            row.put("mod", c.mod());
            Class<?> r = c.method().getReturnType();
            row.put("cancellable", r == boolean.class || r == InteractionResult.class || r == TriState.class);
            if (!observable(c.method())) row.put("observable", false);
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, String> all() {
        Map<String, String> out = new TreeMap<>();
        index().forEach((k, c) -> out.put(k, c.mod()));
        return out;
    }

    @Override
    public Object subscribe(Found event, Priority priority, boolean receiveCanceled, Consumer<Object> listener) {
        if (!(event.handle() instanceof Callback c)) throw new IllegalArgumentException("not a Fabric callback: " + event.name());
        Dispatcher d = dispatchers.computeIfAbsent(c.field(), f -> new Dispatcher(c));
        Subscription s = new Subscription(d, listener);
        d.listeners.add(s);
        return s;
    }

    @Override
    public void unsubscribe(Object handle) {
        if (handle instanceof Subscription s) s.dispatcher.listeners.remove(s);
    }

    @Override
    public boolean cancellable(Object event) {
        return event instanceof FabricEvent e && e.cancellable();
    }

    @Override
    public void cancel(Object event) {
        ((FabricEvent) event).cancel();
    }

    @Override
    public boolean isEvent(Object value) {
        return value instanceof FabricEvent;
    }

    @Override
    public Map<String, Object> properties(Object event) {
        return event instanceof FabricEvent e ? e.getArguments() : Map.of();
    }

    @Override
    public Class<?> base() {
        return Object.class;
    }

    @Override
    public Place place(Object event) {
        if (!(event instanceof FabricEvent e)) return null;
        Level level = e.getLevel();
        BlockPos pos = e.getPos();
        if (level instanceof ServerLevel l && pos != null) return new Place(l, pos);
        Entity entity = e.getEntity() != null ? e.getEntity() : e.getPlayer();
        if (entity != null && entity.level() instanceof ServerLevel l) return new Place(l, entity.blockPosition());
        return null;
    }

    @Override
    public boolean placed(Class<?> type) {
        Method m = single(type);
        if (m == null) return false;
        for (Class<?> p : m.getParameterTypes()) if (BlockPos.class.isAssignableFrom(p) || Entity.class.isAssignableFrom(p)) return true;
        return false;
    }

    private record Subscription(Dispatcher dispatcher, Consumer<Object> listener) {}

    /** One registered listener per callback; forwards to the rules subscribed now. */
    private static final class Dispatcher implements InvocationHandler {
        final Callback callback;
        final List<Subscription> listeners = new CopyOnWriteArrayList<>();

        Dispatcher(Callback callback) {
            this.callback = callback;
            Object proxy = Proxy.newProxyInstance(callback.listener().getClassLoader(), new Class<?>[]{callback.listener()}, this);
            try {
                @SuppressWarnings("unchecked")
                Event<Object> e = (Event<Object>) callback.field().get(null);
                e.register(proxy);
            } catch (IllegalAccessException | RuntimeException e) {
                throw new IllegalArgumentException(e.toString(), e);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "almighty rules on " + callback.name();
                };
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            FabricEvent event = null;
            if (!listeners.isEmpty()) {
                event = new FabricEvent(callback.name(), method, args == null ? new Object[0] : args);
                for (Subscription s : listeners) s.listener().accept(event);
            }
            boolean stop = event != null && event.canceled();
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return !stop;
            if (r == InteractionResult.class) return stop ? InteractionResult.FAIL : InteractionResult.PASS;
            if (r == TriState.class) return stop ? TriState.FALSE : TriState.DEFAULT;
            return null;
        }
    }

    private static String similar(String name, Map<String, Callback> all) {
        String tail = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        List<String> like = all.keySet().stream().filter(c -> c.toLowerCase(Locale.ROOT).contains(tail)).limit(8).toList();
        return like.isEmpty() ? "" : " (similar: " + String.join(", ", like) + ")";
    }
}
