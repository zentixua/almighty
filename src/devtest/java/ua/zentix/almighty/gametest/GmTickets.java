package ua.zentix.almighty.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.SortedArraySet;

import java.lang.reflect.Field;

/** Тикеты ведущего в очереди ванили ({@code DistanceManager.tickets}, только чтение). */
final class GmTickets {
    private static final Field TICKETS;

    static {
        try {
            TICKETS = DistanceManager.class.getDeclaredField("tickets");
            TICKETS.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private GmTickets() {}

    @SuppressWarnings("unchecked")
    static int count(ServerLevel level) {
        try {
            var map = (Long2ObjectMap<SortedArraySet<Ticket<?>>>) TICKETS.get(level.getChunkSource().chunkMap.getDistanceManager());
            int n = 0;
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) if (t.getType().toString().equals("almighty")) n++;
            }
            return n;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
