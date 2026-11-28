package ua.zentix.almighty.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import ua.zentix.almighty.bridge.RpcException;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The loader's game event bus as rules see it: discover event types by name, subscribe and unsubscribe, cancel,
 * read an event's place. Events are plain objects to the core; {@link ua.zentix.almighty.script.Json} reads their
 * getters up to {@link #base()}.
 */
public interface GameEvents {
    enum Priority { HIGHEST, HIGH, NORMAL, LOW, LOWEST }

    /**
     * A discovered event type: its class (NeoForge: the event class; Fabric: the callback interface), short name, source
     * mod, and the loader's own handle for subscribing (NeoForge: none; Fabric: the {@code Event} field).
     */
    record Found(Class<?> type, String name, String mod, Object handle) {
        public Found(Class<?> type, String name, String mod) {
            this(type, name, mod, null);
        }
    }

    /** Event by short or full name; {@code tick} is the end of each server tick. Missing or ambiguous: an error naming similar ones. */
    Found resolve(String name) throws RpcException;

    /** Events whose names contain {@code query}: name, class, mod, cancellable (and loader-specific flags). */
    List<Map<String, Object>> search(String query, int limit);

    /** Short name: class without its package, nested classes separated by dots ({@code BlockEvent.BreakEvent}). */
    static String shortName(String className) {
        return className.substring(className.lastIndexOf('.') + 1).replace('$', '.');
    }

    /** All events: class name (with {@code $}) to mod id, sorted. */
    Map<String, String> all();

    /**
     * Subscribe {@code listener} to {@code type}; returns the handle for {@link #unsubscribe}.
     * @throws IllegalArgumentException if the bus does not accept the type
     */
    Object subscribe(Found event, Priority priority, boolean receiveCanceled, Consumer<Object> listener);

    void unsubscribe(Object handle);

    boolean cancellable(Object event);

    /** Cancel the event; the caller checked {@link #cancellable}. */
    void cancel(Object event);

    /** Whether a value is an event of this bus (scripts and the feed turn it into its properties). */
    boolean isEvent(Object value);

    /**
     * Properties an event has beyond its getters (Fabric: the callback's arguments by name, so a tick rule sees
     * {@code server} as on NeoForge); the feed and declarative rules read them as fields, scripts as properties.
     */
    /** The command line of a command event (a player's or the server's), or null for any other event. */
    default String command(Object event) {
        return null;
    }

    default Map<String, Object> properties(Object event) {
        return Map.of();
    }

    /** The bus's event base class: its getters and those above it are not event properties. */
    Class<?> base();

    /** An event's place in the world: a block event's or interaction's target position, else its entity's; null if none. */
    record Place(ServerLevel level, BlockPos pos) {}

    Place place(Object event);

    /** Whether events of this type have a place ({@link #place}): a block's or an entity's. */
    boolean placed(Class<?> type);
}
