package ua.zentix.almighty.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.almighty.bridge.RpcException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Чанки, которые держит ведущий: под постройку, откат и подготовленные районы ({@code area.prepare}). Каждая аренда —
 * прямоугольник чанков и кольцо соседей в один чанк: запись блока читает соседей (форма по соседям, обновление соседей,
 * Sable — до двух блоков), и соседний чанк не в полной загрузке грузился бы синхронно.
 *
 * <p>Тикет — свой на каждый чанк, ванильный тикет загрузки ({@code DistanceManager.addTicket}) уровня 33: чанк
 * становится полным ({@code getChunkNow}), но не тикает — ни блоки, ни блок-сущности, ни сущности; генерация и чтение
 * с диска идут в фоне. Тикет региона ({@code addRegionTicket}) не годится: он пускает тик сразу, без готовых соседей.
 * {@code TicketController.forceChunk} NeoForge грузит чанк синхронно. Одинаковые ванильные тикеты сливаются в один,
 * поэтому аренды считаются здесь: тикет ставит первая аренда чанка, снимает очередь отпуска после последней.
 *
 * <p>Отпуск — не больше {@value #RELEASE_PER_TICK} чанков за тик: ваниль выгружает больше 2000 держателей разом в одном
 * тике (Отбой залпа Airstrike стоил тика 6,8 с). Остановка сервера снимает всё сразу ({@link #releaseAll}), в
 * {@code ServerStoppingEvent} до {@code StopDrain} Airstrike: иначе он ждал бы генерацию по тикетам ведущего.
 * Только поток сервера.
 */
public final class Areas {
    private static final TicketType<Long> TICKET = TicketType.create("almighty", Long::compare);
    /** 33: полный чанк без тика. */
    private static final int LEVEL = ChunkLevel.byStatus(FullChunkStatus.FULL);
    static final int RELEASE_PER_TICK = 64;
    /** Кольцо соседей вокруг прямоугольника аренды, чанков. */
    static final int MARGIN = 1;

    /** Аренда: прямоугольник чанков с кольцом. Срок — тик сервера, {@link Long#MAX_VALUE} — пока не отпустят. */
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

        /** Сколько чанков аренды (с кольцом) уже полные. */
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

        /** Чья аренда — в списке {@code areas}. */
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
            if (expires != Long.MAX_VALUE) out.addProperty("expires_in_ticks", Math.max(0, expires - now));
            return out;
        }
    }

    private final int maxChunks;
    private final Map<ServerLevel, Long2IntOpenHashMap> refs = new HashMap<>();
    /** Чанки без аренд, чей тикет ещё стоит, — по порядку отпуска. */
    private final Map<ServerLevel, LongLinkedOpenHashSet> releasing = new HashMap<>();
    private final Map<Long, Lease> leases = new LinkedHashMap<>();
    private long nextId = 1;
    private long now;

    public Areas(int maxChunks) {
        this.maxChunks = maxChunks;
    }

    /**
     * Взять чанки, на которые приходятся блоки {@code [x1..x2] × [z1..z2]}, с кольцом соседей. Срок — в тиках,
     * {@code 0} — пока не отпустят.
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
            throw RpcException.conflict("Ведущий держит " + held() + " чанков, просится ещё " + (area > maxChunks ? area : fresh)
                    + " (с кольцом соседей), предел " + maxChunks + ": отпустить районы (area.release) или взять меньше");
        }
        LongLinkedOpenHashSet queue = releasing.computeIfAbsent(level, l -> new LongLinkedOpenHashSet());
        DistanceManager distances = distances(level);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                long chunk = ChunkPos.asLong(x, z);
                int before = counts.addTo(chunk, 1);
                // тикет ещё стоит, если чанк ждал отпуска
                if (before == 0 && !queue.remove(chunk)) distances.addTicket(TICKET, new ChunkPos(chunk), LEVEL, chunk);
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

    private void release(Lease lease) {
        if (lease.released) return;
        lease.released = true;
        leases.remove(lease.id);
        Long2IntOpenHashMap counts = refs.get(lease.level);
        LongLinkedOpenHashSet queue = releasing.get(lease.level);
        for (int x = lease.minX; x <= lease.maxX; x++) {
            for (int z = lease.minZ; z <= lease.maxZ; z++) {
                long chunk = ChunkPos.asLong(x, z);
                if (counts.addTo(chunk, -1) == 1) {
                    counts.remove(chunk);
                    queue.add(chunk);
                }
            }
        }
    }

    /** Конец тика сервера: сроки аренд, отпуск по очереди. */
    public void tick(long gameTick) {
        now = gameTick;
        List<Lease> expired = new ArrayList<>();
        for (Lease lease : leases.values()) if (lease.expires <= now) expired.add(lease);
        expired.forEach(this::release);
        int budget = RELEASE_PER_TICK;
        for (Map.Entry<ServerLevel, LongLinkedOpenHashSet> e : releasing.entrySet()) {
            DistanceManager distances = distances(e.getKey());
            for (Iterator<Long> it = e.getValue().iterator(); it.hasNext() && budget > 0; budget--) {
                long chunk = it.next();
                it.remove();
                distances.removeTicket(TICKET, new ChunkPos(chunk), LEVEL, chunk);
            }
        }
    }

    /** Остановка сервера: все тикеты — сразу, аренды — отпущены. */
    public void releaseAll() {
        for (Lease lease : leases.values()) lease.released = true;
        leases.clear();
        for (Map.Entry<ServerLevel, Long2IntOpenHashMap> e : refs.entrySet()) {
            DistanceManager distances = distances(e.getKey());
            for (long chunk : e.getValue().keySet()) distances.removeTicket(TICKET, new ChunkPos(chunk), LEVEL, chunk);
        }
        for (Map.Entry<ServerLevel, LongLinkedOpenHashSet> e : releasing.entrySet()) {
            DistanceManager distances = distances(e.getKey());
            for (long chunk : e.getValue()) distances.removeTicket(TICKET, new ChunkPos(chunk), LEVEL, chunk);
        }
        refs.clear();
        releasing.clear();
    }

    /** Чанки с арендой. */
    public int held() {
        int n = 0;
        for (Long2IntOpenHashMap counts : refs.values()) n += counts.size();
        return n;
    }

    /** Тикеты, которые ещё стоят: с арендой и ждущие отпуска. */
    public int ticketed() {
        int n = held();
        for (LongLinkedOpenHashSet queue : releasing.values()) n += queue.size();
        return n;
    }

    public JsonArray describe() {
        JsonArray out = new JsonArray();
        for (Lease lease : leases.values()) out.add(lease.describe());
        return out;
    }

    private static DistanceManager distances(ServerLevel level) {
        return level.getChunkSource().chunkMap.getDistanceManager();
    }
}
