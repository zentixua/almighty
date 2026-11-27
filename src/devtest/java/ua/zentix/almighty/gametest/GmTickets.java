package ua.zentix.almighty.gametest;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
//? if >=26.1 {
/*import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.TicketStorage;
import ua.zentix.almighty.world.Areas;

import java.lang.reflect.Field;
import java.util.List;
*///?} else {
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.util.SortedArraySet;

import java.lang.reflect.Field;
//?}

/**
 * GM tickets in vanilla's queue ({@code DistanceManager.tickets}, read-only; 26.x: the level's
 * {@code TicketStorage.tickets}).
 */
final class GmTickets {
    private static final Field TICKETS;

    static {
        try {
            //? if >=26.1 {
            /*TICKETS = TicketStorage.class.getDeclaredField("tickets");
            *///?} else {
            TICKETS = DistanceManager.class.getDeclaredField("tickets");
            //?}
            TICKETS.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private GmTickets() {}

    @SuppressWarnings("unchecked")
    static int count(ServerLevel level) {
        try {
            //? if >=26.1 {
            /*var map = (Long2ObjectMap<List<Ticket>>) TICKETS.get(level.getChunkSource().getDataStorage().get(TicketStorage.TYPE));
            int n = 0;
            for (List<Ticket> set : map.values()) {
                for (Ticket t : set) if (t.getType() == Areas.TICKET) n++;
            }
            *///?} else {
            var map = (Long2ObjectMap<SortedArraySet<Ticket<?>>>) TICKETS.get(level.getChunkSource().chunkMap.getDistanceManager());
            int n = 0;
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) if (t.getType().toString().equals("almighty")) n++;
            }
            //?}
            return n;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
