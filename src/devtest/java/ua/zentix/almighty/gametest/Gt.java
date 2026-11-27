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
//? if >=26.1 {
/*import net.minecraft.world.entity.vehicle.boat.Boat;
*///?} else {
import net.minecraft.world.entity.vehicle.Boat;
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

    // Rule events by the names the bridge gives them (rule.add, event.types). NeoForge: any event on its bus; Fabric:
    // Fabric API callbacks (FabricEvents), cancelled by returning their "stop" value.

    /** The cancellable event a player's block break fires, and the mod that declares it. */
    //? if fabric {
    /*static final String BREAK_EVENT = "PlayerBlockBreakEvents.BEFORE";
    static final String BREAK_EVENT_MOD = "fabric-events-interaction-v0";
    *///?} else if >=26.1 {
    /*static final String BREAK_EVENT = "BreakBlockEvent";
    static final String BREAK_EVENT_MOD = "neoforge";
    *///?} else {
    static final String BREAK_EVENT = "BlockEvent.BreakEvent";
    static final String BREAK_EVENT_MOD = "neoforge";
    //?}

    /** An entity joining a level, as a rule names it. */
    //? if fabric {
    /*static final String JOIN_EVENT = "ServerEntityEvents.ENTITY_LOAD";
    *///?} else
    static final String JOIN_EVENT = "EntityJoinLevelEvent";

    /**
     * The event a guard rule cancels to keep a zombie unharmed by its arrival: NeoForge cancels its joining the level;
     * Fabric API has no cancellable entity-join callback, so the Fabric guard cancels damage to it ({@link #guarded}).
     */
    //? if fabric {
    /*static final String GUARD_EVENT = "ServerLivingEntityEvents.ALLOW_DAMAGE";
    *///?} else
    static final String GUARD_EVENT = JOIN_EVENT;

    /** Spawn a zombie at a relative position under a guard rule on {@link #GUARD_EVENT}; fail unless the guard held. */
    static void guarded(GameTestHelper h, int x, int y, int z) {
        //? if fabric {
        /*Entity zombie = h.spawn(ZOMBIE, x, y, z);
        float health = ((net.minecraft.world.entity.LivingEntity) zombie).getHealth();
        hurt(zombie, zombie.damageSources().generic(), 5);
        if (((net.minecraft.world.entity.LivingEntity) zombie).getHealth() != health) throw new ua.zentix.almighty.gametest.v26.Failure("guard did not cancel the zombie's damage");
        zombie.discard();
        *///?} else {
        h.spawn(ZOMBIE, x, y, z);
        h.assertEntityNotPresent(ZOMBIE);
        //?}
    }

    /** A chat message event (short and fully qualified name, declaring mod). */
    //? if fabric {
    /*static final String CHAT_EVENT = "ServerMessageEvents.CHAT_MESSAGE";
    static final String CHAT_EVENT_FULL = "net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE";
    static final String CHAT_EVENT_MOD = "fabric-message-api-v1";
    *///?} else {
    static final String CHAT_EVENT = "ServerChatEvent";
    static final String CHAT_EVENT_FULL = "net.neoforged.neoforge.event.ServerChatEvent";
    static final String CHAT_EVENT_MOD = "neoforge";
    //?}

    /** A cancellable server-wide event with no place in the world: chat (on Fabric the sender is the one who acts, not a place). */
    //? if fabric {
    /*static final String UNPLACED_EVENT = "ServerMessageEvents.ALLOW_CHAT_MESSAGE";
    *///?} else
    static final String UNPLACED_EVENT = "ServerChatEvent";

    /** A player's login. */
    //? if fabric {
    /*static final String LOGIN_EVENT = "ServerPlayerEvents.JOIN";
    *///?} else
    static final String LOGIN_EVENT = "PlayerEvent.PlayerLoggedInEvent";

    /** Fire the block-break event as a player breaking {@code state} at {@code pos} would; true if nobody cancelled it. */
    static boolean breaks(ServerLevel level, BlockPos pos, BlockState state, Player by) {
        //? if fabric {
        /*return net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, by, pos, state, level.getBlockEntity(pos));
        *///?} else if >=26.1 {
        /*var e = new net.neoforged.neoforge.event.level.block.BreakBlockEvent(level, pos, state, by);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(e);
        return !e.isCanceled();
        *///?} else {
        var e = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level, pos, state, by);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(e);
        return !e.isCanceled();
        //?}
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
