package ua.zentix.airstrikegm.building;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ua.zentix.airstrikegm.bridge.Args;
import ua.zentix.airstrikegm.bridge.RpcException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * План постройки: операции по порядку, каждая выдаёт места по одному (постройка идёт частями между тиками). Блок —
 * строкой, как в {@code /setblock} ({@code oak_stairs[facing=east]}, {@code chest{...}}); строки разбирает
 * {@link Resolver}, план от мира не зависит (юнит-тесты без игры).
 * <ul>
 * <li>{@code {"op": "fill", "from": [x,y,z], "to": [x,y,z], "block": "...", "mode": "replace|keep|hollow|outline"}} —
 * как {@code /fill}: keep — только на место воздуха, hollow — оболочка блоком, внутри воздух, outline — только
 * оболочка;</li>
 * <li>{@code {"op": "set", "pos": [x,y,z], "block": "..."}};</li>
 * <li>{@code {"op": "layers", "origin": [x,y,z], "palette": {"#": "stone", ...}, "layers": [[строки], ...]}} — слои
 * снизу вверх от {@code origin}, строка — z с севера на юг, символ — x с запада на восток; символ не из палитры
 * (пробел, точка) — место не трогать. Слой может быть и объектом {@code {"rows": [...]}} — как отдаёт {@code blocks}.</li>
 * </ul>
 * Места идут снизу вверх, внутри слоя — с севера на юг и с запада на восток.
 */
public final class BuildPlan<B> {
    static final int MAX_OPS = 10_000;

    @FunctionalInterface
    public interface Resolver<B> {
        B resolve(String block) throws RpcException;
    }

    /** Место постройки; {@code keep} — ставить, только если там воздух. */
    public static final class Placement<B> {
        public int x, y, z;
        public B block;
        public boolean keep;

        void set(int x, int y, int z, B block, boolean keep) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.keep = keep;
        }
    }

    private interface Op<B> {
        long size();

        /** Следующее место в {@code out}; false — операция кончилась. */
        boolean next(Placement<B> out);

        int[] bounds();
    }

    private final List<Op<B>> ops;
    private final long size;
    private final int[] bounds;
    private int current;

    private BuildPlan(List<Op<B>> ops) {
        this.ops = ops;
        long n = 0;
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Op<B> op : ops) {
            n += op.size();
            int[] o = op.bounds();
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], o[a]);
                b[a + 3] = Math.max(b[a + 3], o[a + 3]);
            }
        }
        this.size = n;
        this.bounds = b;
    }

    /** Сколько мест всего. */
    public long size() {
        return size;
    }

    /** {@code [minX, minY, minZ, maxX, maxY, maxZ]} по всем операциям. */
    public int[] bounds() {
        return bounds.clone();
    }

    public boolean next(Placement<B> out) {
        while (current < ops.size()) {
            if (ops.get(current).next(out)) return true;
            current++;
        }
        return false;
    }

    /** Разобрать операции; высоты — в {@code [minY, maxY]}, мест — не больше {@code maxBlocks}. */
    public static <B> BuildPlan<B> parse(JsonArray json, Resolver<B> resolver, int minY, int maxY, long maxBlocks) throws RpcException {
        if (json.isEmpty() || json.size() > MAX_OPS) throw RpcException.badRequest("ops: от 1 до " + MAX_OPS + " операций");
        Map<String, B> cache = new HashMap<>();
        Resolver<B> cached = s -> {
            B b = cache.get(s);
            if (b == null) {
                b = resolver.resolve(s);
                cache.put(s, b);
            }
            return b;
        };
        List<Op<B>> ops = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < json.size(); i++) {
            JsonElement e = json.get(i);
            if (!e.isJsonObject()) throw RpcException.badRequest("ops[" + i + "]: ожидается объект");
            Op<B> op;
            try {
                op = op(new Args(e.getAsJsonObject()), cached);
            } catch (RpcException x) {
                throw RpcException.badRequest("ops[" + i + "]: " + x.getMessage());
            }
            int[] b = op.bounds();
            if (op.size() > 0 && (b[1] < minY || b[4] > maxY)) {
                throw RpcException.badRequest("ops[" + i + "]: высоты " + b[1] + ".." + b[4] + " вне мира " + minY + ".." + maxY);
            }
            total += op.size();
            if (total > maxBlocks) throw RpcException.badRequest("Постройка больше " + maxBlocks + " мест: разбить на части");
            if (op.size() > 0) ops.add(op);
        }
        if (ops.isEmpty()) throw RpcException.badRequest("В постройке нет ни одного места");
        return new BuildPlan<>(ops);
    }

    private static <B> Op<B> op(Args args, Resolver<B> resolver) throws RpcException {
        String kind = args.string("op");
        return switch (kind) {
            case "fill" -> {
                String mode = args.string("mode", "replace").toLowerCase(Locale.ROOT);
                if (!List.of("replace", "keep", "hollow", "outline").contains(mode)) {
                    throw RpcException.badRequest("mode: replace, keep, hollow или outline");
                }
                B air = mode.equals("hollow") ? resolver.resolve("minecraft:air") : null;
                yield new Fill<>(args.ints("from", 3), args.ints("to", 3), resolver.resolve(args.string("block")), mode, air);
            }
            case "set" -> {
                int[] p = args.ints("pos", 3);
                yield new Fill<>(p, p, resolver.resolve(args.string("block")), "replace", null);
            }
            case "layers" -> layers(args, resolver);
            default -> throw RpcException.badRequest("op: fill, set или layers, а не " + kind);
        };
    }

    /** Коробка: снизу вверх, с севера на юг, с запада на восток; outline пропускает внутренность строки прыжком. */
    private static final class Fill<B> implements Op<B> {
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private final B block, air;
        private final String mode;
        private int x, y, z;

        Fill(int[] a, int[] b, B block, String mode, B air) {
            minX = Math.min(a[0], b[0]);
            minY = Math.min(a[1], b[1]);
            minZ = Math.min(a[2], b[2]);
            maxX = Math.max(a[0], b[0]);
            maxY = Math.max(a[1], b[1]);
            maxZ = Math.max(a[2], b[2]);
            this.block = block;
            this.mode = mode;
            this.air = air;
            x = minX;
            y = minY;
            z = minZ;
        }

        @Override
        public long size() {
            long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (!mode.equals("outline")) return volume;
            return volume - (long) Math.max(0, maxX - minX - 1) * Math.max(0, maxY - minY - 1) * Math.max(0, maxZ - minZ - 1);
        }

        @Override
        public boolean next(Placement<B> out) {
            if (y > maxY) return false;
            boolean shell = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
            switch (mode) {
                case "keep" -> out.set(x, y, z, block, true);
                case "hollow" -> out.set(x, y, z, shell ? block : air, false);
                default -> out.set(x, y, z, block, false);
            }
            // outline: на внутренних строках после западной стенки — сразу к восточной
            if (mode.equals("outline") && x == minX && maxX > minX && y > minY && y < maxY && z > minZ && z < maxZ) x = maxX;
            else x++;
            if (x > maxX) {
                x = minX;
                if (++z > maxZ) {
                    z = minZ;
                    y++;
                }
            }
            return true;
        }

        @Override
        public int[] bounds() {
            return new int[] {minX, minY, minZ, maxX, maxY, maxZ};
        }
    }

    private static <B> Op<B> layers(Args args, Resolver<B> resolver) throws RpcException {
        int[] origin = args.ints("origin", 3);
        JsonObject paletteJson = args.object("palette");
        Map<Character, B> palette = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : paletteJson.entrySet()) {
            if (e.getKey().length() != 1) throw RpcException.badRequest("palette: ключ — один символ, а не «" + e.getKey() + "»");
            if (!e.getValue().isJsonPrimitive()) throw RpcException.badRequest("palette." + e.getKey() + ": ожидается строка блока");
            palette.put(e.getKey().charAt(0), resolver.resolve(e.getValue().getAsString()));
        }
        JsonArray layersJson = args.array("layers");
        List<String[]> layers = new ArrayList<>();
        for (int i = 0; i < layersJson.size(); i++) {
            JsonElement layer = layersJson.get(i);
            JsonElement rows = layer.isJsonObject() ? layer.getAsJsonObject().get("rows") : layer;
            if (rows == null || !rows.isJsonArray()) throw RpcException.badRequest("layers[" + i + "]: ожидается массив строк или {\"rows\": [...]}");
            List<String> list = new ArrayList<>();
            for (JsonElement row : rows.getAsJsonArray()) {
                if (!row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString()) throw RpcException.badRequest("layers[" + i + "]: строки — строками");
                list.add(row.getAsString());
            }
            layers.add(list.toArray(String[]::new));
        }
        return new Layers<>(origin, layers, palette);
    }

    private static final class Layers<B> implements Op<B> {
        private final int[] origin;
        private final List<String[]> layers;
        private final Map<Character, B> palette;
        private final long size;
        private final int[] bounds;
        private int layer, row, col;

        Layers(int[] origin, List<String[]> layers, Map<Character, B> palette) {
            this.origin = origin;
            this.layers = layers;
            this.palette = palette;
            long n = 0;
            int maxRow = 0, maxCol = 0;
            for (String[] rows : layers) {
                maxRow = Math.max(maxRow, rows.length);
                for (String r : rows) {
                    maxCol = Math.max(maxCol, r.length());
                    for (int i = 0; i < r.length(); i++) if (palette.containsKey(r.charAt(i))) n++;
                }
            }
            this.size = n;
            this.bounds = new int[] {origin[0], origin[1], origin[2],
                    origin[0] + Math.max(0, maxCol - 1), origin[1] + Math.max(0, layers.size() - 1), origin[2] + Math.max(0, maxRow - 1)};
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public boolean next(Placement<B> out) {
            while (layer < layers.size()) {
                String[] rows = layers.get(layer);
                if (row >= rows.length) {
                    layer++;
                    row = 0;
                    col = 0;
                    continue;
                }
                String r = rows[row];
                if (col >= r.length()) {
                    row++;
                    col = 0;
                    continue;
                }
                B block = palette.get(r.charAt(col));
                int c = col++;
                if (block != null) {
                    out.set(origin[0] + c, origin[1] + layer, origin[2] + row, block, false);
                    return true;
                }
            }
            return false;
        }

        @Override
        public int[] bounds() {
            return bounds;
        }
    }
}
