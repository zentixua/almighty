package ua.zentix.almighty.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The change journal (plan 4.9, step 8): every block write made while a GM call or GM job runs — builds, commands,
 * functions, scripts alike — is recorded with its state before and after (and the block entity data before), in
 * {@code <world>/almighty/changes/<id>.bin}, appended as it happens, so a crash mid-build leaves an undoable change.
 * Undo is conflict-aware: a position goes back only if it still holds what the change put there. Entities and
 * inventories are not recorded (only block states and block entity data).
 *
 * <p>Recording is switched on per thread ({@link #current}) by the code that runs GM work, and read by the one hook
 * on {@code LevelChunk.setBlockState}; with nothing recording, the hook costs one volatile read. Server thread only,
 * except {@link #read} and the index, which are synchronized.
 */
public final class Journal {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** Positions kept over all changes; the oldest changes are dropped beyond it (like build snapshots). */
    public static final long MAX_POSITIONS = 4_000_000;

    private static final CompoundTag NONE = new CompoundTag();

    /** Any change open on any thread: the hook's fast path. */
    private static volatile int open;
    private static final ThreadLocal<Change> CURRENT = new ThreadLocal<>();

    public static boolean recording() {
        return open > 0;
    }

    public static Change current() {
        return CURRENT.get();
    }

    /** Run with {@code change} recording on this thread (null: nothing recorded), restoring the previous one. */
    public static <T, E extends Exception> T with(Change change, ua.zentix.almighty.bridge.Dispatch.ThrowingSupplier<T, E> work) throws E {
        Change previous = CURRENT.get();
        if (change == null && previous == null) return work.get();
        CURRENT.set(change);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** One change: the writes of one GM call and the jobs it started. */
    public final class Change {
        public final long id;
        public final String agent;
        public final String method;
        public final UUID requester;
        public final long time;
        private final Path file;
        private DataOutputStream out;
        private final Map<BlockState, Integer> palette = new IdentityHashMap<>();
        private final List<String> dims = new ArrayList<>();
        private long count;
        private int refs = 1;
        private boolean closed;
        /**
         * Block entity data before each write in progress (pushed by the hook before the write, popped after it). A
         * stack: a write can cause nested writes ({@code onPlace} of neighbours) before it returns.
         */
        private final java.util.ArrayDeque<CompoundTag> pending = new java.util.ArrayDeque<>();

        public void before(CompoundTag tag) {
            pending.push(tag == null ? NONE : tag);
        }

        public CompoundTag takeBefore() {
            CompoundTag t = pending.poll();
            return t == NONE ? null : t;
        }

        Change(long id, String agent, String method, UUID requester) {
            this.id = id;
            this.agent = agent;
            this.method = method;
            this.requester = requester;
            this.time = System.currentTimeMillis();
            this.file = dir.resolve(id + ".bin");
        }

        public long count() {
            return count;
        }

        /** A job continues this change: it ends when the call and every job of it have ended. */
        public Change retain() {
            refs++;
            return this;
        }

        public void release() {
            if (--refs > 0 || closed) return;
            closed = true;
            open--;
            try {
                if (out != null) out.close();
            } catch (IOException e) {
                LOG.warn("Almighty journal: closing change {}: {}", id, e.toString());
            }
            out = null;
            ended(this);
        }

        /** The hook: one block write of this change. */
        public void record(String dim, BlockPos pos, BlockState before, BlockState after, CompoundTag tagBefore) {
            if (closed) return;
            try {
                if (out == null) {
                    Files.createDirectories(dir);
                    out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file.toFile(), true), 1 << 16));
                    // Who and what, first in the file: after a crash the change is known even if the index never saw it end.
                    JsonObject head = new JsonObject();
                    head.addProperty("id", id);
                    head.addProperty("agent", agent);
                    head.addProperty("method", method);
                    if (requester != null) head.addProperty("requester", requester.toString());
                    head.addProperty("time", time);
                    out.writeByte(4);
                    out.writeUTF(head.toString());
                }
                int d = dims.indexOf(dim);
                if (d < 0) {
                    dims.add(dim);
                    d = dims.size() - 1;
                    out.writeByte(1);
                    out.writeUTF(dim);
                }
                int b = state(before), a = state(after);
                out.writeByte(tagBefore == null ? 2 : 3);
                out.writeByte(d);
                out.writeLong(pos.asLong());
                out.writeInt(b);
                out.writeInt(a);
                if (tagBefore != null) NbtIo.write(tagBefore, out);
                count++;
                total++;
            } catch (IOException e) {
                LOG.error("Almighty journal: change {} cannot be written ({}); it will not be fully undoable", id, e.toString());
                closed = true;
            }
        }

        private int state(BlockState s) throws IOException {
            Integer i = palette.get(s);
            if (i != null) return i;
            int n = palette.size();
            palette.put(s, n);
            out.writeByte(0);
            NbtIo.write(NbtUtils.writeBlockState(s), out);
            return n;
        }

        /** Push buffered records to the file (tick end), so a crash loses at most one tick. */
        void flush() {
            try {
                if (out != null) out.flush();
            } catch (IOException e) {
                LOG.warn("Almighty journal: flushing change {}: {}", id, e.toString());
            }
        }
    }

    /** A change read back for undo: positions with their states, in write order. */
    public record Records(List<String> dims, int[] dim, long[] pos, BlockState[] before, BlockState[] after, CompoundTag[] tag, int size) {}

    private final Path dir;
    private final Map<Long, JsonObject> index = new LinkedHashMap<>();
    private final List<Change> active = new ArrayList<>();
    private long next = 1;
    private long total;

    public Journal(Path dir) {
        this.dir = dir;
        load();
    }

    /** Open a change for a call with effects (server or bridge thread). */
    public synchronized Change begin(String agent, String method, UUID requester) {
        Change c = new Change(next++, agent, method, requester);
        JsonObject e = new JsonObject();
        e.addProperty("id", c.id);
        e.addProperty("agent", agent);
        e.addProperty("method", method);
        if (requester != null) e.addProperty("requester", requester.toString());
        e.addProperty("time", c.time);
        e.addProperty("open", true);
        index.put(c.id, e);
        active.add(c);
        open++;
        return c;
    }

    synchronized void ended(Change c) {
        active.remove(c);
        JsonObject e = index.get(c.id);
        if (e == null) return;
        if (c.count == 0) {
            index.remove(c.id);
            try {
                Files.deleteIfExists(c.file);
            } catch (IOException ignored) {
                // Nothing written.
            }
        } else {
            e.remove("open");
            e.addProperty("positions", c.count);
        }
        trim();
        save();
    }

    /** Tick end: flush what was recorded this tick. */
    public synchronized void flush() {
        for (Change c : active) c.flush();
    }

    /** Server stop: close open changes (their files stay undoable). */
    public synchronized void stop() {
        for (Change c : new ArrayList<>(active)) {
            c.refs = 1;
            c.release();
        }
    }

    public synchronized JsonObject describe(long id) {
        JsonObject e = index.get(id);
        return e == null ? null : e.deepCopy();
    }

    /** The newest changes, newest first. */
    public synchronized JsonArray list(int limit) {
        List<JsonObject> all = new ArrayList<>(index.values());
        JsonArray out = new JsonArray();
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) out.add(all.get(i).deepCopy());
        return out;
    }

    /** Mark a change undone (its undo is a change of its own). */
    public synchronized void undone(long id, long by) {
        JsonObject e = index.get(id);
        if (e != null) {
            e.addProperty("undone_by", by);
            save();
        }
    }

    /** Read a change's records ({@code states}: block state from its saved form). */
    public Records read(long id, Function<CompoundTag, BlockState> states) throws IOException {
        Path f = dir.resolve(id + ".bin");
        List<String> dims = new ArrayList<>();
        List<BlockState> palette = new ArrayList<>();
        int cap = 1024, n = 0;
        int[] dim = new int[cap];
        long[] pos = new long[cap];
        BlockState[] before = new BlockState[cap], after = new BlockState[cap];
        CompoundTag[] tag = new CompoundTag[cap];
        try (InputStream raw = Files.newInputStream(f); DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw))) {
            while (true) {
                int type;
                try {
                    type = in.readUnsignedByte();
                } catch (EOFException end) {
                    break;
                }
                try {
                    switch (type) {
                        case 0 -> palette.add(states.apply(NbtIo.read(in, NbtAccounter.unlimitedHeap())));
                        case 1 -> dims.add(in.readUTF());
                        case 4 -> in.readUTF();
                        case 2, 3 -> {
                            if (n == cap) {
                                cap *= 2;
                                dim = java.util.Arrays.copyOf(dim, cap);
                                pos = java.util.Arrays.copyOf(pos, cap);
                                before = java.util.Arrays.copyOf(before, cap);
                                after = java.util.Arrays.copyOf(after, cap);
                                tag = java.util.Arrays.copyOf(tag, cap);
                            }
                            dim[n] = in.readUnsignedByte();
                            pos[n] = in.readLong();
                            before[n] = palette.get(in.readInt());
                            after[n] = palette.get(in.readInt());
                            tag[n] = type == 3 ? NbtIo.read(in, NbtAccounter.unlimitedHeap()) : null;
                            n++;
                        }
                        default -> throw new IOException("unknown record " + type);
                    }
                } catch (EOFException torn) {
                    // A crash mid-write: everything before the torn record is undoable.
                    break;
                }
            }
        }
        return new Records(dims, dim, pos, before, after, tag, n);
    }

    private void trim() {
        long kept = 0;
        List<Long> ids = new ArrayList<>(index.keySet());
        for (int i = ids.size() - 1; i >= 0; i--) {
            JsonObject e = index.get(ids.get(i));
            kept += e.has("positions") ? e.get("positions").getAsLong() : 0;
            if (kept > MAX_POSITIONS && !e.has("open")) {
                index.remove(ids.get(i));
                try {
                    Files.deleteIfExists(dir.resolve(ids.get(i) + ".bin"));
                } catch (IOException ignored) {
                    // Gone already.
                }
            }
        }
        total = Math.min(kept, MAX_POSITIONS);
    }

    private void load() {
        Path f = dir.resolve("index.json");
        if (!Files.isRegularFile(f)) return;
        try {
            JsonArray a = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var el : a) {
                JsonObject e = el.getAsJsonObject();
                long id = e.get("id").getAsLong();
                if (e.has("open")) {
                    // Open at the last stop or crash: what reached the file is undoable.
                    e.remove("open");
                    e.addProperty("interrupted", true);
                }
                index.put(id, e);
                next = Math.max(next, id + 1);
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("Almighty journal: cannot read {}; earlier changes cannot be undone: {}", f, e.toString());
        }
        recover();
    }

    /** Change files the index does not list (a crash before the change ended): listed again from their header. */
    private void recover() {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.newDirectoryStream(dir, "*.bin")) {
            for (Path f : files) {
                long id;
                try {
                    id = Long.parseLong(f.getFileName().toString().replace(".bin", ""));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (index.containsKey(id)) continue;
                try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
                    if (in.readUnsignedByte() != 4) continue;
                    JsonObject head = JsonParser.parseString(in.readUTF()).getAsJsonObject();
                    head.addProperty("interrupted", true);
                    index.put(id, head);
                    next = Math.max(next, id + 1);
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Almighty journal: {} has no readable header: {}", f, e.toString());
                }
            }
        } catch (IOException e) {
            LOG.warn("Almighty journal: cannot list {}: {}", dir, e.toString());
        }
    }

    private void save() {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("index.json.tmp");
            JsonArray a = new JsonArray();
            for (JsonObject e : index.values()) a.add(e);
            Files.writeString(tmp, GSON.toJson(a), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Almighty journal: cannot save the index: {}", e.toString());
        }
    }
}
