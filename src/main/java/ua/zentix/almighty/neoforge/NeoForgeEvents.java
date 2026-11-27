package ua.zentix.almighty.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.platform.GameEvents;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** {@link GameEvents} over {@link NeoForge#EVENT_BUS} and FML scan data ({@link EventTypes}). */
final class NeoForgeEvents implements GameEvents {
    private final IEventBus bus = NeoForge.EVENT_BUS;

    @Override
    public Found resolve(String name) throws RpcException {
        return EventTypes.resolve(name);
    }

    @Override
    public List<Map<String, Object>> search(String query, int limit) {
        return EventTypes.search(query, limit);
    }

    @Override
    public Map<String, String> all() {
        return EventTypes.all();
    }

    @Override
    public Object subscribe(Found event, Priority priority, boolean receiveCanceled, Consumer<Object> listener) {
        return add(event.type().asSubclass(Event.class), EventPriority.valueOf(priority.name()), receiveCanceled, listener);
    }

    private <T extends Event> Consumer<T> add(Class<T> type, EventPriority priority, boolean receiveCanceled, Consumer<Object> listener) {
        Consumer<T> c = listener::accept;
        bus.addListener(priority, receiveCanceled, type, c);
        return c;
    }

    @Override
    public void unsubscribe(Object handle) {
        bus.unregister(handle);
    }

    @Override
    public boolean cancellable(Object event) {
        return event instanceof ICancellableEvent;
    }

    @Override
    public void cancel(Object event) {
        ((ICancellableEvent) event).setCanceled(true);
    }

    @Override
    public boolean isEvent(Object value) {
        return value instanceof Event;
    }

    @Override
    public Class<?> base() {
        return Event.class;
    }

    @Override
    public Place place(Object e) {
        // An interaction's place is the block it targets, not where the player stands.
        if (e instanceof PlayerInteractEvent pi && pi.getLevel() instanceof ServerLevel l) return new Place(l, pi.getPos());
        if (e instanceof BlockEvent b && b.getLevel() instanceof ServerLevel l) return new Place(l, b.getPos());
        if (e instanceof EntityEvent ee && ee.getEntity() != null && ee.getEntity().level() instanceof ServerLevel l) {
            return new Place(l, ee.getEntity().blockPosition());
        }
        return null;
    }

    @Override
    public boolean placed(Class<?> type) {
        return BlockEvent.class.isAssignableFrom(type) || EntityEvent.class.isAssignableFrom(type);
    }
}
