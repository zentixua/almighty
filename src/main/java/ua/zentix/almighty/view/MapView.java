package ua.zentix.almighty.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import ua.zentix.almighty.work.Budget;
import ua.zentix.almighty.world.Dims;

import java.util.List;
import java.util.Locale;

/**
 * Карта сверху, как у ванильной карты ({@code MapItem.update}): верхний блок с цветом карты, глубина воды, светотень по
 * разнице высот с соседом к северу. Север — вверх, восток — вправо. Только загруженные чанки ({@code getChunkNow}),
 * остальное — шахматкой. {@code below} — смотреть под потолок: столбец начинается с этой высоты (пещеры, Незер,
 * этажи). Игроки и метки ведущего — квадратами своего цвета, их список — в легенде.
 */
public final class MapView extends View {
    public static final int MAX_SIDE = 1024;
    static final int MAX_PIXELS = 512;
    /** Столбцов в порции. */
    static final int UNIT = 64;
    private static final int[] MARKERS = {0xFF2020, 0xFF40FF, 0x20FFFF, 0xFFFF20, 0xFFFFFF, 0xFF9020, 0x40FF40, 0x4080FF};

    private final ServerLevel level;
    private final int x0, z0, step, width, height;
    private final Integer below;
    private final List<Mark> marks;
    private final Png png;
    private final double[] north;
    private int row = -1, col;

    /** Метка ведущего на карте. */
    public record Mark(String label, int x, int z) {}

    /** Блоки {@code [x0, x0 + sizeX) × [z0, z0 + sizeZ)}. */
    public MapView(ServerLevel level, int x0, int z0, int sizeX, int sizeZ, Integer below, List<Mark> marks) {
        super("map");
        this.level = level;
        this.x0 = x0;
        this.z0 = z0;
        this.step = (Math.max(sizeX, sizeZ) + MAX_PIXELS - 1) / MAX_PIXELS;
        this.width = (sizeX + step - 1) / step;
        this.height = (sizeZ + step - 1) / step;
        this.below = below;
        this.marks = marks;
        this.png = new Png(width, height);
        this.north = new double[width];
    }

    @Override
    protected void step(Budget budget) {
        running();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        do {
            // строка −1 — соседи к северу для светотени первой строки; порция — до UNIT столбцов
            int z = z0 + row * step;
            for (int end = Math.min(width, col + UNIT); col < end; col++) {
                int color = column(x0 + col * step, z, pos, col);
                if (row >= 0) png.set(col, row, color);
            }
            if (col < width) continue;
            col = 0;
            if (++row == height) {
                complete();
                return;
            }
        } while (budget.left());
    }

    /** Цвет столбца; высота — в {@code north[px]} для следующей строки (NaN — чанк не загружен). */
    private int column(int x, int z, BlockPos.MutableBlockPos pos, int px) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        int py = Math.max(row, 0);
        if (chunk == null) {
            north[px] = Double.NaN;
            return ((px / 4 + py / 4) & 1) == 0 ? 0x303030 : 0x484848;
        }
        int min = level.getMinBuildHeight();
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1;
        if (below != null) y = Math.min(y, below + 1);
        BlockState state;
        int depth = 0;
        if (y <= min + 1) {
            state = Blocks.BEDROCK.defaultBlockState();
        } else {
            do {
                pos.set(x, --y, z);
                state = chunk.getBlockState(pos);
            } while (state.getMapColor(chunk, pos) == MapColor.NONE && y > min);
            if (y > min && !state.getFluidState().isEmpty()) {
                int yy = y - 1;
                BlockState under;
                do {
                    under = chunk.getBlockState(new BlockPos(x, yy--, z));
                    depth++;
                } while (yy > min && !under.getFluidState().isEmpty());
                state = fluidSurface(chunk, state, pos);
            }
        }
        MapColor color = state.getMapColor(chunk, pos);
        double previous = north[px];
        north[px] = y;
        if (color == MapColor.NONE) return 0x000000;
        MapColor.Brightness brightness;
        if (color == MapColor.WATER) {
            double d = depth * 0.1 + ((px + py) & 1) * 0.2;
            brightness = d < 0.5 ? MapColor.Brightness.HIGH : d > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        } else {
            double d = (Double.isNaN(previous) ? 0 : (y - previous) * 4.0 / (step + 4)) + (((px + py) & 1) - 0.5) * 0.4;
            brightness = d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        }
        return Png.shade(color.col, brightness.modifier / 255.0);
    }

    /** Как {@code MapItem.getCorrectStateForFluidBlock}: вода над блоком без твёрдого верха — водой. */
    private static BlockState fluidSurface(LevelChunk chunk, BlockState state, BlockPos pos) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !state.isFaceSturdy(chunk, pos, Direction.UP) ? fluid.createLegacyBlock() : state;
    }

    private void complete() {
        legend.addProperty("dimension", Dims.id(level));
        JsonArray origin = new JsonArray();
        origin.add(x0);
        origin.add(z0);
        legend.add("top_left_block", origin);
        legend.addProperty("blocks_per_cell", step);
        legend.addProperty("cells", width + "×" + height);
        legend.addProperty("axes", "север вверху, восток справа; клетка (i, j) — блок x = x0 + i·blocks_per_cell, z = z0 + j·blocks_per_cell");
        legend.addProperty("unloaded", "шахматка серым — чанк не загружен (area.prepare загрузит)");
        if (below != null) legend.addProperty("below", below);
        JsonArray markers = new JsonArray();
        int n = 0;
        for (ServerPlayer p : level.players()) {
            if (marker(p.getGameProfile().getName(), "player", p.getBlockX(), p.getBlockZ(), MARKERS[n % MARKERS.length], markers)) n++;
        }
        for (Mark m : marks) {
            if (marker(m.label(), "mark", m.x(), m.z(), MARKERS[n % MARKERS.length], markers)) n++;
        }
        legend.add("markers", markers);
        image(png, step == 1 ? 16 : 0, Math.floorMod(x0, 16), Math.floorMod(z0, 16));
        if (step == 1 && upscale() >= 3) legend.addProperty("grid", "линии — границы чанков");
        finish();
    }

    /** Квадрат 3×3 клетки в чёрной кайме; вне карты — нет. */
    private boolean marker(String label, String kind, int x, int z, int color, JsonArray out) {
        int px = Math.floorDiv(x - x0, step), py = Math.floorDiv(z - z0, step);
        if (px < 0 || py < 0 || px >= width || py >= height) return false;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int ix = px + dx, iy = py + dy;
                if (ix < 0 || iy < 0 || ix >= width || iy >= height) continue;
                boolean edge = Math.abs(dx) == 2 || Math.abs(dy) == 2;
                png.set(ix, iy, edge ? 0x000000 : color);
            }
        }
        JsonObject m = new JsonObject();
        m.addProperty("kind", kind);
        m.addProperty("label", label);
        m.addProperty("color", String.format(Locale.ROOT, "#%06X", color));
        m.addProperty("x", x);
        m.addProperty("z", z);
        out.add(m);
        return true;
    }

    @Override
    protected void details(JsonObject out) {
        out.addProperty("rows_done", Math.max(row, 0));
        out.addProperty("rows", height);
    }
}
