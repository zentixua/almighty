package ua.zentix.airstrikegm.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;
import ua.zentix.airstrikegm.bridge.RpcException;
import ua.zentix.airstrikegm.work.Budget;
import ua.zentix.airstrikegm.world.Dims;

import java.util.Locale;

/**
 * Вид на рамку сбоку, сверху или снизу (фасад, разрез, план этажа): из каждой точки ближней грани рамки луч идёт
 * вглубь до первого блока с цветом карты; цвет темнее с глубиной. Блоки без цвета карты (стекло, барьер) луч проходит,
 * но оставляет налёт. Блок — клетка картинки. Неготовый чанк на пути — шахматка, пустой луч — цвет неба.
 */
public final class LookView extends View {
    static final int MAX_FACE = 256;
    static final int MAX_DEPTH = 256;
    static final long MAX_VOLUME = 4_000_000L;
    private static final int SKY = 0x87AEDB;
    /** Налёт блоков без цвета карты (стекло, барьер) на то, что за ними. */
    private static final int GLASS = 0xF0FAFF;

    /** Взгляд: куда смотрит камера; оси картинки — вправо {@code u}, вниз {@code v}, вглубь {@code d}. */
    public enum Look {
        NORTH(new int[] {1, 0, 0}, new int[] {0, -1, 0}, new int[] {0, 0, -1}),
        SOUTH(new int[] {-1, 0, 0}, new int[] {0, -1, 0}, new int[] {0, 0, 1}),
        EAST(new int[] {0, 0, 1}, new int[] {0, -1, 0}, new int[] {1, 0, 0}),
        WEST(new int[] {0, 0, -1}, new int[] {0, -1, 0}, new int[] {-1, 0, 0}),
        DOWN(new int[] {1, 0, 0}, new int[] {0, 0, 1}, new int[] {0, -1, 0}),
        UP(new int[] {-1, 0, 0}, new int[] {0, 0, 1}, new int[] {0, 1, 0});

        final int[] u, v, d;

        Look(int[] u, int[] v, int[] d) {
            this.u = u;
            this.v = v;
            this.d = d;
        }

        public static Look parse(String s) throws RpcException {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw RpcException.badRequest("look: north, south, east, west, down или up");
            }
        }
    }

    private final ServerLevel level;
    private final Look look;
    private final int[] origin = new int[3];
    private final int width, height, depth;
    private final Png png;
    private int next;

    public LookView(ServerLevel level, int[] from, int[] to, Look look) throws RpcException {
        super("look");
        this.level = level;
        this.look = look;
        int[] min = new int[3], size = new int[3];
        for (int a = 0; a < 3; a++) {
            min[a] = Math.min(from[a], to[a]);
            size[a] = Math.abs(from[a] - to[a]) + 1;
        }
        // угол (0, 0, 0) картинки: по каждой оси — край рамки, от которого идут u, v, d
        for (int a = 0; a < 3; a++) {
            int dir = look.u[a] + look.v[a] + look.d[a];
            origin[a] = dir < 0 ? min[a] + size[a] - 1 : min[a];
        }
        this.width = extent(look.u, size);
        this.height = extent(look.v, size);
        this.depth = extent(look.d, size);
        if (width > MAX_FACE || height > MAX_FACE) throw RpcException.badRequest("Грань рамки — не больше " + MAX_FACE + "×" + MAX_FACE + " блоков");
        if (depth > MAX_DEPTH) throw RpcException.badRequest("Глубина рамки — не больше " + MAX_DEPTH + " блоков");
        if ((long) width * height * depth > MAX_VOLUME) throw RpcException.badRequest("Рамка — не больше " + MAX_VOLUME + " блоков");
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight() - 1;
        if (min[1] > maxY || min[1] + size[1] - 1 < minY) throw RpcException.badRequest("Рамка вне высот мира " + minY + ".." + maxY);
        this.png = new Png(width, height);
    }

    private static int extent(int[] axis, int[] size) {
        for (int a = 0; a < 3; a++) if (axis[a] != 0) return size[a];
        throw new IllegalStateException();
    }

    @Override
    protected void step(Budget budget) {
        running();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight() - 1;
        do {
            int i = next % width, j = next / width;
            png.set(i, j, ray(i, j, pos, minY, maxY));
            if (++next == width * height) {
                complete();
                return;
            }
        } while (budget.left());
    }

    private int ray(int i, int j, BlockPos.MutableBlockPos pos, int minY, int maxY) {
        LevelChunk chunk = null;
        int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        boolean glass = false;
        for (int k = 0; k < depth; k++) {
            int x = origin[0] + i * look.u[0] + j * look.v[0] + k * look.d[0];
            int y = origin[1] + i * look.u[1] + j * look.v[1] + k * look.d[1];
            int z = origin[2] + i * look.u[2] + j * look.v[2] + k * look.d[2];
            if (y < minY || y > maxY) continue;
            if (x >> 4 != cx || z >> 4 != cz) {
                cx = x >> 4;
                cz = z >> 4;
                chunk = level.getChunkSource().getChunkNow(cx, cz);
            }
            if (chunk == null) return ((i / 4 + j / 4) & 1) == 0 ? 0x303030 : 0x484848;
            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            MapColor color = state.getMapColor(chunk, pos);
            if (color != MapColor.NONE) return tinted(Png.shade(color.col, 1.0 - 0.6 * k / Math.max(1, depth - 1)), glass);
            // стекло, барьер и прочее без цвета карты не прячут то, что за ними, но видны налётом
            if (!state.isAir()) glass = true;
        }
        return tinted(SKY, glass);
    }

    private static int tinted(int color, boolean glass) {
        if (!glass) return color;
        int r = ((color >> 16) & 255) * 2 / 3 + (GLASS >> 16 & 255) / 3;
        int g = ((color >> 8) & 255) * 2 / 3 + (GLASS >> 8 & 255) / 3;
        int b = (color & 255) * 2 / 3 + (GLASS & 255) / 3;
        return r << 16 | g << 8 | b;
    }

    private void complete() {
        legend.addProperty("dimension", Dims.id(level));
        legend.addProperty("look", look.name().toLowerCase(Locale.ROOT));
        legend.add("cell_0_0_front_block", vector(origin));
        legend.add("right", vector(look.u));
        legend.add("down", vector(look.v));
        legend.add("depth_dir", vector(look.d));
        legend.addProperty("cells", width + "×" + height);
        legend.addProperty("depth", depth);
        legend.addProperty("shading", "чем темнее, тем дальше от ближней грани; голубой — насквозь пусто; белёсый налёт — стекло, барьер и другие блоки без цвета карты на пути; шахматка — чанк не загружен");
        image(png, 0, 0, 0);
        finish();
    }

    private static JsonArray vector(int[] v) {
        JsonArray out = new JsonArray();
        for (int c : v) out.add(c);
        return out;
    }

    @Override
    protected void details(JsonObject out) {
        out.addProperty("cells_done", next);
        out.addProperty("cells", width * height);
    }
}
