package ua.zentix.almighty.policy;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.bridge.RpcException;

/**
 * Content limits below owner (plan 4.3, step 7): builds take block states only — no NBT, so no command blocks with
 * commands, signs with click events or spawner data — and never blocks in the datapack tag
 * {@code #almighty:never_build} (command, structure and jigsaw blocks, spawners); {@code give} hands out plain
 * items, never one in {@code #almighty:never_give}. Datapacks may add to both tags.
 */
public final class ContentLimits {
    public static final TagKey<Block> NEVER_BUILD = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Almighty.ID, "never_build"));
    public static final TagKey<Item> NEVER_GIVE = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(Almighty.ID, "never_give"));

    private ContentLimits() {}

    /** A build block below owner: refused with the reason if it carries NBT or is in {@code #almighty:never_build}. */
    public static void block(String text, BlockState state, boolean hasNbt) throws RpcException {
        if (hasNbt) {
            throw new RpcException(403, "policy", "block \"" + text + "\": block data ({…}) needs the owner; build block states only");
        }
        if (state.is(NEVER_BUILD)) {
            throw new RpcException(403, "policy", "block \"" + text + "\" is never built for others (#almighty:never_build)");
        }
    }
}
