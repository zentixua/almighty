package ua.zentix.almighty.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Field;

/** Тикеты в очереди ванили ({@code DistanceManager.tickets}, только чтение): ведущего и по типу на чанке. */
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

    static int count(ServerLevel level) {
        int n = 0;
        for (SortedArraySet<Ticket<?>> set : tickets(level).values()) {
            for (Ticket<?> t : set) if (t.getType().toString().equals("almighty")) n++;
        }
        return n;
    }

    /** Тикеты типа {@code type} на чанке. */
    static int at(ServerLevel level, TicketType<?> type, int chunkX, int chunkZ) {
        SortedArraySet<Ticket<?>> set = tickets(level).get(ChunkPos.asLong(chunkX, chunkZ));
        int n = 0;
        if (set != null) for (Ticket<?> t : set) if (t.getType() == type) n++;
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Long2ObjectMap<SortedArraySet<Ticket<?>>> tickets(ServerLevel level) {
        try {
            return (Long2ObjectMap<SortedArraySet<Ticket<?>>>) TICKETS.get(level.getChunkSource().chunkMap.getDistanceManager());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
