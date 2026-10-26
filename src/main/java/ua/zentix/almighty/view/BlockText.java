package ua.zentix.almighty.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import ua.zentix.almighty.bridge.RpcException;
import ua.zentix.almighty.world.Dims;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Blocks in a box as text: a "character to block" palette and layers from bottom to top; rows run north to south
 * (increasing z), characters west to east (increasing x). {@code '.'} is air, {@code '?'} an unloaded chunk.
 * Building accepts the same format ({@code layers} operation): to copy a box including air, add
 * {@code ".": "minecraft:air"} to the palette. Server thread; the small box is read immediately.
 */
public final class BlockText {
    static final int MAX_EDGE = 64;
    static final int MAX_BLOCKS = 32_768;
    /** Palette characters: exclude '.', '?', space, quote and backslash (which would require JSON escaping). */
    static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#@%&*+=-~^:;!$<>/|()[]{},_'`";

    private BlockText() {}

    public static JsonElement read(ServerLevel level, int[] from, int[] to, boolean properties) throws RpcException {
        int minX = Math.min(from[0], to[0]), minY = Math.min(from[1], to[1]), minZ = Math.min(from[2], to[2]);
        int sx = Math.abs(from[0] - to[0]) + 1, sy = Math.abs(from[1] - to[1]) + 1, sz = Math.abs(from[2] - to[2]) + 1;
        if (sx > MAX_EDGE || sy > MAX_EDGE || sz > MAX_EDGE) throw RpcException.badRequest("Box: at most " + MAX_EDGE + " blocks along each axis");
        if ((long) sx * sy * sz > MAX_BLOCKS) throw RpcException.badRequest("Box: at most " + MAX_BLOCKS + " blocks");
        Map<String, Character> symbols = new LinkedHashMap<>();
        Map<BlockState, Character> cache = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        JsonArray layers = new JsonArray();
        int minBuild = level.getMinBuildHeight(), maxBuild = level.getMaxBuildHeight() - 1;
        for (int y = minY; y < minY + sy; y++) {
            JsonArray rows = new JsonArray();
            for (int z = minZ; z < minZ + sz; z++) {
                StringBuilder row = new StringBuilder(sx);
                for (int x = minX; x < minX + sx; x++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) {
                        row.append('?');
                        continue;
                    }
                    if (y < minBuild || y > maxBuild) {
                        row.append('.');
                        continue;
                    }
                    BlockState state = chunk.getBlockState(pos.set(x, y, z));
                    if (state.isAir()) {
                        row.append('.');
                        continue;
                    }
                    Character c = cache.get(state);
                    if (c == null) {
                        String name = properties ? BlockStateParser.serialize(state) : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        c = symbols.get(name);
                        if (c == null) {
                            c = symbol(name, symbols);
                            symbols.put(name, c);
                        }
                        cache.put(state, c);
                    }
                    row.append(c.charValue());
                }
                rows.add(row.toString());
            }
            JsonObject layer = new JsonObject();
            layer.addProperty("y", y);
            layer.add("rows", rows);
            layers.add(layer);
        }
        JsonObject palette = new JsonObject();
        symbols.forEach((name, c) -> palette.addProperty(String.valueOf(c), name));
        JsonObject out = new JsonObject();
        out.addProperty("dimension", Dims.id(level));
        JsonArray origin = new JsonArray();
        origin.add(minX);
        origin.add(minY);
        origin.add(minZ);
        out.add("origin", origin);
        JsonArray size = new JsonArray();
        size.add(sx);
        size.add(sy);
        size.add(sz);
        out.add("size", size);
        out.add("palette", palette);
        out.add("layers", layers);
        out.addProperty("legend", "layers from bottom to top; rows are z from north to south; characters are x from west to east; '.' air, '?' not loaded");
        return out;
    }

    /** First letter of the block path (lowercase, then uppercase), if free; otherwise the first free character. */
    private static char symbol(String name, Map<String, Character> taken) throws RpcException {
        String path = name.substring(name.indexOf(':') + 1);
        char first = path.isEmpty() ? 'a' : path.charAt(0);
        for (char c : new char[] {Character.toLowerCase(first), Character.toUpperCase(first)}) {
            if (SYMBOLS.indexOf(c) >= 0 && !taken.containsValue(c)) return c;
        }
        for (int i = 0; i < SYMBOLS.length(); i++) {
            char c = SYMBOLS.charAt(i);
            if (!taken.containsValue(c)) return c;
        }
        throw RpcException.badRequest("The box has more than " + SYMBOLS.length() + " different blocks: use a smaller box or properties: false");
    }
}
