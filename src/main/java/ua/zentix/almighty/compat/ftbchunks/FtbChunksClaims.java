package ua.zentix.almighty.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import net.minecraft.server.level.ServerLevel;
import ua.zentix.almighty.world.Claims;

import java.util.UUID;

/** FTB Chunks: a claimed chunk is open to its team's members and allies only (its own read-only API). */
public final class FtbChunksClaims implements Claims.Source {
    public static final String MOD_ID = "ftbchunks";

    @Override
    public String name() {
        return "FTB Chunks";
    }

    @Override
    public boolean mayBuild(UUID player, ServerLevel level, int chunkX, int chunkZ) {
        if (!FTBChunksAPI.api().isManagerLoaded()) return true;
        ClaimedChunk chunk = FTBChunksAPI.api().getManager().getChunk(new ChunkDimPos(level.dimension(), chunkX, chunkZ));
        if (chunk == null) return true;
        if (player == null) return false;
        ChunkTeamData team = chunk.getTeamData();
        return team.isTeamMember(player) || team.isAlly(player);
    }
}
