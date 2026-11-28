package ua.zentix.almighty.compat.cpa;

import eu.pb4.common.protection.api.CommonProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import ua.zentix.almighty.world.Claims;

import java.util.UUID;

/**
 * Claims on Fabric through Patbox's Common Protection API (plan step 19, R9): every protection mod that registers a
 * provider (GOML, Flan, …) answers one read-only query. A chunk with nothing protected in it (the API's area query
 * over the whole chunk, full height) is open. Otherwise an unasked build (nobody) is refused, and a requester must
 * be allowed to place and break at every column of the chunk at the bottom, sea level and top: a claim lying wholly
 * between those heights is not seen (rare: claims are mostly full-height areas). Claims are read from memory by the
 * mods this supports; a provider that loads chunks would do it on the server thread (nothing here can prevent that).
 */
public final class CommonProtectionClaims implements Claims.Source {
    public static final String MOD_ID = "common-protection-api";

    @Override
    public String name() {
        return "Common Protection API";
    }

    @Override
    public boolean mayBuild(UUID player, ServerLevel level, int chunkX, int chunkZ) {
        int x = chunkX << 4, z = chunkZ << 4;
        int bottom = ua.zentix.almighty.platform.Mc.minBuildHeight(level), top = ua.zentix.almighty.platform.Mc.maxBuildHeight(level) - 1;
        if (!CommonProtection.isAreaProtected(level, new net.minecraft.world.phys.AABB(x, bottom, z, x + 16, top + 1, z + 16))) return true;
        if (player == null) return false;
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
        NameAndId who = online != null ? new NameAndId(online.getGameProfile()) : new NameAndId(player, "");
        int[] heights = {bottom, level.getSeaLevel(), top};
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int y : heights) {
                    at.set(x + dx, y, z + dz);
                    if (!CommonProtection.canPlaceBlock(level, at, who, online) || !CommonProtection.canBreakBlock(level, at, who, online)) return false;
                }
            }
        }
        return true;
    }
}
