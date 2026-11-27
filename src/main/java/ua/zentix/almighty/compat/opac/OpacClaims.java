package ua.zentix.almighty.compat.opac;

import net.minecraft.server.level.ServerLevel;
import ua.zentix.almighty.world.Claims;
import xaero.pac.common.server.api.OpenPACServerAPI;

import java.util.UUID;

/** Open Parties and Claims: its own read-only chunk access query ({@code IChunkProtectionAPI.hasChunkAccess}). */
public final class OpacClaims implements Claims.Source {
    public static final String MOD_ID = "openpartiesandclaims";
    /** A fixed identity that belongs to nobody's party: "a stranger" for unasked builds. */
    private static final UUID STRANGER = UUID.fromString("a1a1a1a1-0000-4000-8000-a1a1a1a1a1a1");

    @Override
    public String name() {
        return "Open Parties and Claims";
    }

    @Override
    public boolean mayBuild(UUID player, ServerLevel level, int chunkX, int chunkZ) {
        return OpenPACServerAPI.get(level.getServer()).getChunkProtection()
                .hasChunkAccess(player == null ? STRANGER : player, level.dimension().location(), chunkX, chunkZ);
    }
}
