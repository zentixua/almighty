package ua.zentix.almighty.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;
import ua.zentix.almighty.bridge.RpcException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunks held by the game master for building, undo and prepared areas ({@code area.prepare}). Each lease is a
 * chunk rectangle with a one-chunk neighbor ring: block writes read neighbors (neighbor shapes, neighbor updates,
 * Sable up to two blocks away), and a neighboring chunk that is not full would load synchronously.
 *
 * <p>Each chunk gets a vanilla radius-0 region ticket ({@code ServerChunkCache.addRegionTicket}) at level 33:
 * the chunk becomes full ({@code getChunkNow}) without ticking blocks, block entities or entities (level 33 is also
 * below ticking in {@code TickingTracker}); generation and disk reads run in the background. A radius above 0 would
 * start ticking immediately without ready neighbors. The entry point is shared by mod tickets: mods managing their
 * own chunks refuse tickets there (Sable rejects tickets on ship plots: it inserts and removes their holders in
 * vanilla's chunk map itself; a vanilla ticket bypassing that entry point, {@code DistanceManager.addTicket}, starts
 * generation there, and the generation task cannot find a holder removed by Sable: NPE in
 * {@code ChunkMap.acquireGeneration}, stopping the server). Ticket acceptance is visible only in vanilla's ticket
 * queue ({@code DistanceManager.tickets}, read-only): a lease remembers a chunk without its ticket as refused and
 * does not remove the ticket there. Removing a nonexistent ticket still recalculates the chunk's ticket level
 * ({@code DistanceManager.removeTicket}), which would unload the ship holder that tickets do not retain.
 * A lease cannot await a refused, unready chunk: jobs refuse immediately ({@link Lease#refusal}).
 * NeoForge's {@code TicketController.forceChunk} loads chunks synchronously. Identical vanilla tickets merge, so
 * leases are counted here: the first lease adds the chunk ticket, and the release queue removes it after the last.
 *
 * <p>Release at most {@value #RELEASE_PER_TICK} chunks per tick: vanilla unloads over 2000 holders at once in a
 * single tick (Incoming salvo shutdown: a 6.8 s tick). Server shutdown removes everything immediately
 * ({@link #releaseAll}) in {@code ServerStoppingEvent}, before Incoming's {@code StopDrain}; otherwise it would
 * wait for generation under GM tickets. Server thread only.
 */
public final class Areas {
    private static final TicketType<Long> TICKET = TicketType.create("almighty", Long::compare);
    /** Region ticket radius: 0 means level 33, a full chunk without ticking. */
    private static final int RADIUS = 0;
    private static final Logger LOG = LogUtils.getLogger();
    /** Vanilla ticket queue; if the field is unavailable, every ticket is considered accepted (refusals are not checked). */
    private static final Field TICKETS = ticketsField();
    static final int RELEASE_PER_TICK = 64;
    /** Neighbor ring around the lease rectangle, in chunks. */
    static final int MARGIN = 1;

    /** Lease: chunk rectangle with a ring. Expiry is a server tick; {@link Long#MAX_VALUE} means until released. */
    public final class Lease {
        private final long id;
        private final ServerLevel level;
        private String owner;
        private final int minX, minZ, maxX, maxZ;
        private long expires;
        private boolean released;

        private Lease(long id, ServerLevel level, String owner, int minX, int minZ, int maxX, int maxZ, long expires) {
            this.id = id;
            this.level = level;
            this.owner = owner;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.expires = expires;
        }

        public long id() {
            return id;
        }

        public int chunks() {
            return (maxX - minX + 1) * (maxZ - minZ + 1);
        }

        /** How many leased chunks (including the ring) are already full. */
        public int ready() {
            int n = 0;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (level.getChunkSource().getChunkNow(x, z) != null) n++;
                }
            }
            return n;
        }

        public boolean isReady() {
            return ready() == chunks();
        }

        /** How many leased chunks refused the ticket (managed by another mod). */
        public int refused() {
            return count(false);
        }

        /** Why the lease cannot await its chunks (refused and unready), or {@code null}. */
        public String refusal() {
            int n = count(true);
            if (n == 0) return null;
            return n + " of " + chunks() + " chunks (with the ring of neighbours) refuse loading because another mod manages them"
                    + " (for example, a ship plot): a job will not wait for them; use commands there, in chunks that are already loaded";
        }

        private int count(boolean unready) {
            LongOpenHashSet set = refused.get(level);
            if (set == null || set.isEmpty()) return 0;
            int n = 0;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (set.contains(ChunkPos.asLong(x, z)) && !(unready && level.getChunkSource().getChunkNow(x, z) != null)) n++;
                }
            }
            return n;
        }

        /** Lease owner in the {@code areas} list. */
        public void owner(String owner) {
            this.owner = owner;
        }

        public boolean released() {
            return released;
        }

        public void release() {
            Areas.this.release(this);
        }

        public JsonObject describe() {
            JsonObject out = new JsonObject();
            out.addProperty("id", id);
            out.addProperty("owner", owner);
            out.addProperty("dimension", level.dimension().location().toString());
            JsonArray from = new JsonArray();
            from.add(minX + MARGIN);
            from.add(minZ + MARGIN);
            JsonArray to = new JsonArray();
            to.add(maxX - MARGIN);
            to.add(maxZ - MARGIN);
            out.add("chunk_from", from);
            out.add("chunk_to", to);
            out.addProperty("chunks", chunks());
            out.addProperty("ready", ready());
            int no = refused();
            if (no > 0) out.addProperty("refused", no);
            String refusal = refusal();
            if (refusal != null) out.addProperty("refusal", refusal);
            if (expires != Long.MAX_VALUE) out.addProperty("expires_in_ticks", Math.max(0, expires - now));
            return out;
        }
    }

    private final int maxChunks;
    private final Map<ServerLevel, Long2IntOpenHashMap> refs = new HashMap<>();
    /** Chunks without leases whose tickets remain, in release order. */
    private final Map<ServerLevel, LongLinkedOpenHashSet> releasing = new HashMap<>();
    /** Leased chunks where tickets were refused: do not remove them. */
    private final Map<ServerLevel, LongOpenHashSet> refused = new HashMap<>();
    private final Map<Long, Lease> leases = new LinkedHashMap<>();
    private long nextId = 1;
    private long now;

    public Areas(int maxChunks) {
        this.maxChunks = maxChunks;
    }

    /**
     * Acquire chunks containing blocks {@code [x1..x2] x [z1..z2]}, with a neighbor ring. Duration is in ticks;
     * {@code 0} means until released.
     */
    public Lease acquire(ServerLevel level, String owner, int x1, int z1, int x2, int z2, long ttlTicks) throws RpcException {
        int minX = (Math.min(x1, x2) >> 4) - MARGIN, maxX = (Math.max(x1, x2) >> 4) + MARGIN;
        int minZ = (Math.min(z1, z2) >> 4) - MARGIN, maxZ = (Math.max(z1, z2) >> 4) + MARGIN;
        long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        Long2IntOpenHashMap counts = refs.computeIfAbsent(level, l -> new Long2IntOpenHashMap());
        long fresh = 0;
        if (area <= maxChunks) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) if (!counts.containsKey(ChunkPos.asLong(x, z))) fresh++;
            }
        }
        if (area > maxChunks || held() + fresh > maxChunks) {
            throw RpcException.conflict("The game master holds " + held() + " chunks and asks for " + (area > maxChunks ? area : fresh)
                    + " more (with the ring of neighbours); the limit is " + maxChunks + ": release areas (area.release) or take a smaller one");
        }
        LongLinkedOpenHashSet queue = releasing.computeIfAbsent(level, l -> new LongLinkedOpenHashSet());
        LongOpenHashSet no = refused.computeIfAbsent(level, l -> new LongOpenHashSet());
        ServerChunkCache chunks = level.getChunkSource();
        Long2ObjectMap<SortedArraySet<Ticket<?>>> tickets = TICKETS == null ? null : tickets(chunks);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                long chunk = ChunkPos.asLong(x, z);
                int before = counts.addTo(chunk, 1);
                // The ticket still exists if the chunk was waiting for release.
                if (before == 0 && !queue.remove(chunk)) {
                    chunks.addRegionTicket(TICKET, new ChunkPos(chunk), RADIUS, chunk);
                    if (tickets != null && !has(tickets.get(chunk))) no.add(chunk);
                }
            }
        }
        Lease lease = new Lease(nextId++, level, owner, minX, minZ, maxX, maxZ,
                ttlTicks <= 0 ? Long.MAX_VALUE : now + ttlTicks);
        leases.put(lease.id, lease);
        return lease;
    }

    public Lease get(long id) {
        return leases.get(id);
    }

    private static Field ticketsField() {
        try {
            return ObfuscationReflectionHelper.findField(DistanceManager.class, "tickets");
        } catch (RuntimeException | LinkageError e) {
            LOG.error("GM: DistanceManager.tickets not found; leases cannot see where a mod refused a ticket (Sable ship plots)"
                    + " and remove tickets there too", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Long2ObjectMap<SortedArraySet<Ticket<?>>> tickets(ServerChunkCache chunks) {
        try {
            return (Long2ObjectMap<SortedArraySet<Ticket<?>>>) TICKETS.get(chunks.chunkMap.getDistanceManager());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean has(SortedArraySet<Ticket<?>> set) {
        if (set != null) {
            for (Ticket<?> t : set) if (t.getType() == TICKET) return true;
        }
        return false;
    }

    private void release(Lease lease) {
        if (lease.released) return;
        lease.released = true;
        leases.remove(lease.id);
        Long2IntOpenHashMap counts = refs.get(lease.level);
        LongLinkedOpenHashSet queue = releasing.get(lease.level);
        LongOpenHashSet no = refused.get(lease.level);
        for (int x = lease.minX; x <= lease.maxX; x++) {
            for (int z = lease.minZ; z <= lease.maxZ; z++) {
                long chunk = ChunkPos.asLong(x, z);
                if (counts.addTo(chunk, -1) == 1) {
                    counts.remove(chunk);
                    if (!no.remove(chunk)) queue.add(chunk);
                }
            }
        }
    }

    /** Server tick end: lease expiry and queued release. */
    public void tick(long gameTick) {
        now = gameTick;
        List<Lease> expired = new ArrayList<>();
        for (Lease lease : leases.values()) if (lease.expires <= now) expired.add(lease);
        expired.forEach(this::release);
        int budget = RELEASE_PER_TICK;
        for (Map.Entry<ServerLevel, LongLinkedOpenHashSet> e : releasing.entrySet()) {
            ServerChunkCache chunks = e.getKey().getChunkSource();
            for (Iterator<Long> it = e.getValue().iterator(); it.hasNext() && budget > 0; budget--) {
                long chunk = it.next();
                it.remove();
                chunks.removeRegionTicket(TICKET, new ChunkPos(chunk), RADIUS, chunk);
            }
        }
    }

    /** Server shutdown: remove all tickets immediately and release all leases. */
    public void releaseAll() {
        for (Lease lease : leases.values()) lease.released = true;
        leases.clear();
        for (Map.Entry<ServerLevel, Long2IntOpenHashMap> e : refs.entrySet()) {
            ServerChunkCache chunks = e.getKey().getChunkSource();
            LongOpenHashSet no = refused.get(e.getKey());
            for (long chunk : e.getValue().keySet()) {
                if (no == null || !no.contains(chunk)) chunks.removeRegionTicket(TICKET, new ChunkPos(chunk), RADIUS, chunk);
            }
        }
        for (Map.Entry<ServerLevel, LongLinkedOpenHashSet> e : releasing.entrySet()) {
            ServerChunkCache chunks = e.getKey().getChunkSource();
            for (long chunk : e.getValue()) chunks.removeRegionTicket(TICKET, new ChunkPos(chunk), RADIUS, chunk);
        }
        refs.clear();
        releasing.clear();
        refused.clear();
    }

    /** Leased chunks (including those where tickets were refused). */
    public int held() {
        int n = 0;
        for (Long2IntOpenHashMap counts : refs.values()) n += counts.size();
        return n;
    }

    /** Tickets still present: leased and awaiting release. */
    public int ticketed() {
        int n = held();
        for (LongOpenHashSet no : refused.values()) n -= no.size();
        for (LongLinkedOpenHashSet queue : releasing.values()) n += queue.size();
        return n;
    }

    public JsonArray describe() {
        JsonArray out = new JsonArray();
        for (Lease lease : leases.values()) out.add(lease.describe());
        return out;
    }
}
