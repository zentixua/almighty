package ua.zentix.almighty.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
//? if >=26.1 {
/*import net.minecraft.world.entity.vehicle.boat.Boat;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
*///?} else {
import net.minecraft.world.entity.vehicle.Boat;
import net.neoforged.neoforge.event.level.BlockEvent;
//?}

/**
 * GameTest helpers for what the game reshaped between versions (the tests' own needs; the mod's are in
 * {@code platform/Mc}). Each keeps the meaning of the 1.21.1 call it replaces.
 */
final class Gt {
    private Gt() {}

    /** Entity types the tests spawn: an oak boat (26.x: one entity type per wood); 26.3 keeps the constants in EntityTypes. */
    //? if >=26.3 {
    /*static final EntityType<Boat> BOAT = net.minecraft.world.entity.EntityTypes.OAK_BOAT;
    static final EntityType<net.minecraft.world.entity.animal.equine.Horse> HORSE = net.minecraft.world.entity.EntityTypes.HORSE;
    static final EntityType<net.minecraft.world.entity.animal.pig.Pig> PIG = net.minecraft.world.entity.EntityTypes.PIG;
    static final EntityType<net.minecraft.world.entity.monster.zombie.Zombie> ZOMBIE = net.minecraft.world.entity.EntityTypes.ZOMBIE;
    static final EntityType<net.minecraft.world.entity.Marker> MARKER = net.minecraft.world.entity.EntityTypes.MARKER;
    *///?} else if >=26.1 {
    /*static final EntityType<Boat> BOAT = EntityType.OAK_BOAT;
    static final EntityType<net.minecraft.world.entity.animal.equine.Horse> HORSE = EntityType.HORSE;
    static final EntityType<net.minecraft.world.entity.animal.pig.Pig> PIG = EntityType.PIG;
    static final EntityType<net.minecraft.world.entity.monster.zombie.Zombie> ZOMBIE = EntityType.ZOMBIE;
    static final EntityType<net.minecraft.world.entity.Marker> MARKER = EntityType.MARKER;
    *///?} else {
    static final EntityType<Boat> BOAT = EntityType.BOAT;
    static final EntityType<net.minecraft.world.entity.animal.horse.Horse> HORSE = EntityType.HORSE;
    static final EntityType<net.minecraft.world.entity.animal.Pig> PIG = EntityType.PIG;
    static final EntityType<net.minecraft.world.entity.monster.Zombie> ZOMBIE = EntityType.ZOMBIE;
    static final EntityType<net.minecraft.world.entity.Marker> MARKER = EntityType.MARKER;
    //?}

    /** An entity's scoreboard tags as a Groovy script reads them ({@code entity.tags}; 26.x: {@code entityTags()}). */
    //? if >=26.1 {
    /*static final String SCRIPT_TAGS = "entityTags()";
    *///?} else {
    static final String SCRIPT_TAGS = "tags";
    //?}

    /** The rule event a block break fires, by the name the bridge gives it ({@code rule.add}). */
    //? if >=26.1 {
    /*static final String BREAK_EVENT = "BreakBlockEvent";
    *///?} else {
    static final String BREAK_EVENT = "BlockEvent.BreakEvent";
    //?}

    /** Post a block-break event as a player breaking {@code state} at {@code pos} would; true if nobody cancelled it. */
    static boolean breaks(ServerLevel level, BlockPos pos, BlockState state, Player by) {
        //? if >=26.1 {
        /*BreakBlockEvent e = new BreakBlockEvent(level, pos, state, by);
        *///?} else {
        BlockEvent.BreakEvent e = new BlockEvent.BreakEvent(level, pos, state, by);
        //?}
        NeoForge.EVENT_BUS.post(e);
        return !e.isCanceled();
    }

    /** Saddle a horse (or another saddleable mount). */
    static void saddle(net.minecraft.world.entity.LivingEntity mount) {
        //? if >=26.1 {
        /*mount.setItemSlot(net.minecraft.world.entity.EquipmentSlot.SADDLE, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SADDLE));
        *///?} else {
        ((net.minecraft.world.entity.animal.horse.AbstractHorse) mount).equipSaddle(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SADDLE), null);
        //?}
    }

    /** Damage an entity on the server. */
    static void hurt(Entity e, net.minecraft.world.damagesource.DamageSource source, float amount) {
        //? if >=26.1 {
        /*e.hurtServer((ServerLevel) e.level(), source, amount);
        *///?} else {
        e.hurt(source, amount);
        //?}
    }

    /** Ride a vehicle, forced (as {@code /ride}). */
    static boolean ride(Entity rider, Entity vehicle) {
        //? if >=26.1 {
        /*return rider.startRiding(vehicle, true, true);
        *///?} else {
        return rider.startRiding(vehicle, true);
        //?}
    }

    /** A block entity of a type at a relative position (a test failure if absent). */
    static <T extends BlockEntity> T blockEntity(GameTestHelper h, BlockPos pos, Class<T> type) {
        //? if >=26.1 {
        /*return h.getBlockEntity(pos, type);
        *///?} else {
        return type.cast(h.getBlockEntity(pos));
        //?}
    }

    /** Food exhaustion accumulated since it last cost food. */
    static float exhaustion(ServerPlayer p) {
        //? if >=26.1 {
        /*try {
            return ua.zentix.almighty.platform.Reflect.field(net.minecraft.world.food.FoodData.class, "exhaustionLevel").getFloat(p.getFoodData());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        *///?} else {
        return p.getFoodData().getExhaustionLevel();
        //?}
    }

    /** The chunk containing a block. */
    static ChunkPos chunk(BlockPos pos) {
        //? if >=26.1 {
        /*return ChunkPos.containing(pos);
        *///?} else {
        return new ChunkPos(pos);
        //?}
    }

    /** Whether a chunk is force-loaded ({@code /forceload} and mod forced chunks). */
    static boolean forced(ServerLevel level, ChunkPos pos) {
        //? if >=26.1 {
        /*return level.getChunkSource().getForceLoadedChunks().contains(pos.pack());
        *///?} else {
        return level.getForcedChunks().contains(pos.toLong());
        //?}
    }

    /** Make a profile an operator at a level. */
    static void op(MinecraftServer server, GameProfile profile, int level) {
        //? if >=26.1 {
        /*server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(new net.minecraft.server.players.NameAndId(profile),
                net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(net.minecraft.server.permissions.PermissionLevel.byId(level)), false));
        *///?} else {
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(profile, level, false));
        //?}
    }

    static void deop(MinecraftServer server, GameProfile profile) {
        //? if >=26.1 {
        /*server.getPlayerList().getOps().remove(new net.minecraft.server.players.NameAndId(profile));
        *///?} else {
        server.getPlayerList().getOps().remove(profile);
        //?}
    }

    static void whitelist(MinecraftServer server, GameProfile profile, boolean add) {
        //? if >=26.1 {
        /*var user = new net.minecraft.server.players.NameAndId(profile);
        if (add) server.getPlayerList().getWhiteList().add(new net.minecraft.server.players.UserWhiteListEntry(user));
        else server.getPlayerList().getWhiteList().remove(user);
        *///?} else {
        if (add) server.getPlayerList().getWhiteList().add(new net.minecraft.server.players.UserWhiteListEntry(profile));
        else server.getPlayerList().getWhiteList().remove(profile);
        //?}
    }
}
