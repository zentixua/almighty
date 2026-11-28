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
 * provider (GOML, Flan, FTB Chunks, Open Parties and Claims builds that support it, …) answers one read-only query.
 * A chunk is open to a player when they may place and break blocks at its centre and its four corners, at sea level
 * (claims are areas on the map; height-limited regions are rare). Unasked builds ask as nobody ({@code UNKNOWN}).
 */
public final class CommonProtectionClaims implements Claims.Source {
    public static final String MOD_ID = "common-protection-api";

    @Override
    public String name() {
        return "Common Protection API";
    }

    @Override
    public boolean mayBuild(UUID player, ServerLevel level, int chunkX, int chunkZ) {
        ServerPlayer online = player == null ? null : level.getServer().getPlayerList().getPlayer(player);
        NameAndId who = player == null ? CommonProtection.UNKNOWN
                : online != null ? new NameAndId(online.getGameProfile()) : new NameAndId(player, "");
        int x = chunkX << 4, z = chunkZ << 4, y = level.getSeaLevel();
        int[][] points = {{8, 8}, {0, 0}, {15, 0}, {0, 15}, {15, 15}};
        for (int[] p : points) {
            BlockPos at = new BlockPos(x + p[0], y, z + p[1]);
            if (!CommonProtection.canPlaceBlock(level, at, who, online) || !CommonProtection.canBreakBlock(level, at, who, online)) return false;
        }
        return true;
    }
}
