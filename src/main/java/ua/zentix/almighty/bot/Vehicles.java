package ua.zentix.almighty.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import ua.zentix.almighty.Almighty;
import ua.zentix.almighty.compat.Compat;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * A bot's vehicle: what the driver's client does with it. The common path is player input: the server moves
 * vehicles tagged {@link #DRIVEN_BY_RIDER} (vanilla horses, boats, pigs, striders, camels; mods and datapacks can
 * add theirs) itself ({@link BotPlayer}) from the input packet. The bot reproduces extra vanilla client actions
 * from client code: boat controls ({@code Boat.controlBoat}) and riding jumps ({@code LocalPlayer.aiStep}).
 * A mod vehicle driven by its own client code uses a mod compatibility driver ({@link VehicleDriver},
 * {@code Compat}); extra mod keys use {@code press} with the key's control-settings name. Server thread.
 */
final class Vehicles {
    /**
     * Vehicles driven by rider input ({@code xxa}/{@code zza}, jump, sneak), rather than client keys: the bot drives
     * as "local", and the server computes movement. Mod code behind the local-driver check may read client classes:
     * add a mod vehicle to the tag only after checking that it behaves like vanilla.
     */
    static final TagKey<EntityType<?>> DRIVEN_BY_RIDER = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(Almighty.ID, "driven_by_rider"));
    private static final Method BOAT_STATUS = ObfuscationReflectionHelper.findMethod(Boat.class, "getStatus");
    private static final Field STATUS = ObfuscationReflectionHelper.findField(Boat.class, "status");
    private static final Field DELTA_ROTATION = ObfuscationReflectionHelper.findField(Boat.class, "deltaRotation");
    private static final Field LAND_FRICTION = ObfuscationReflectionHelper.findField(Boat.class, "landFriction");

    private final Bot bot;
    /** Drivers for installed vehicle mods (compatibility), separate for each bot. */
    private final List<VehicleDriver> drivers = Compat.vehicleDrivers();
    /** Riding jump, like the client: hold ticks, power and whether pressed last tick. */
    private int jumpTicks;
    private float jumpScale;
    private boolean jumpWas;

    Vehicles(Bot bot) {
        this.bot = bot;
    }

    /** Whether the player drives a vehicle controlled by rider input; the server then computes its movement. */
    static boolean local(ServerPlayer p) {
        Entity v = p.getVehicle();
        return v != null && v.getControllingPassenger() == p && v.getType().is(DRIVEN_BY_RIDER);
    }

    /** Driver for the bot's mod vehicle; null if none. */
    private VehicleDriver driver(Entity v) {
        for (VehicleDriver d : drivers) if (d.drives(v)) return d;
        return null;
    }

    /** Bot tick after the input packet: steering, riding jump, mod vehicle driver. */
    void tick(ServerPlayer p, Set<Controls.Key> held) {
        boolean jump = held.contains(Controls.Key.JUMP);
        Entity v = p.getVehicle();
        if (v != null && v.getControllingPassenger() == p) {
            VehicleDriver driver = driver(v);
            if (driver != null) {
                // A client does not move a vehicle in chunks it lacks; a bot waits until they are ready on the server.
                if (Controls.ready(v)) {
                    Vec3 from = v.position();
                    driver.tick(p, v, axis(held, Controls.Key.LEFT, Controls.Key.RIGHT), axis(held, Controls.Key.JUMP, Controls.Key.SNEAK),
                            axis(held, Controls.Key.FORWARD, Controls.Key.BACK));
                    moved(p, v, from);
                }
            } else if (local(p)) {
                if (v instanceof Boat boat) {
                    steer(boat, held.contains(Controls.Key.LEFT), held.contains(Controls.Key.RIGHT), held.contains(Controls.Key.FORWARD), held.contains(Controls.Key.BACK));
                }
                ridingJump(p, v, jump);
            }
        } else {
            jumpScale = 0.0F;
        }
        jumpWas = jump;
    }

    private static float axis(Set<Controls.Key> held, Controls.Key plus, Controls.Key minus) {
        boolean a = held.contains(plus), b = held.contains(minus);
        return a == b ? 0.0F : a ? 1.0F : -1.0F;
    }

    /**
     * The server accepts driver-client vehicle movement as follows ({@code handleMoveVehicle}): seat the driver in
     * the moved vehicle ({@code positionRider}; retain its own view and previous position:
     * NeoForge's {@code resyncPlayerWithVehicle}), with movement and riding statistics from vehicle displacement;
     * update chunks at bot tick end ({@link Controls}). Otherwise only the world's vehicle tick would seat the rider,
     * and the world does not tick vehicles outside simulation distance: the pilot step would take the aircraft
     * there, leaving the bot and its chunks behind, and the aircraft would stop ticking.
     */
    private static void moved(ServerPlayer p, Entity v, Vec3 from) {
        // A collision may have destroyed the vehicle and dismounted the bot.
        if (p.getVehicle() != v) return;
        Vec3 old = p.position();
        float yRot = p.getYRot(), xRot = p.getXRot(), head = p.getYHeadRot();
        v.positionRider(p);
        p.setYRot(yRot);
        p.setXRot(xRot);
        p.setYHeadRot(head);
        p.xo = old.x;
        p.yo = old.y;
        p.zo = old.z;
        Vec3 d = v.position().subtract(from);
        p.setKnownMovement(d);
        p.checkRidingStatistics(d.x, d.y, d.z);
    }

    /**
     * Riding jump as in {@code LocalPlayer.aiStep}: holding charges power; releasing jumps. The vehicle computes
     * the jump ({@code onPlayerJump}, on a real player's client); the server receives the same packet as from a client.
     */
    private void ridingJump(ServerPlayer p, Entity v, boolean jump) {
        if (!(v instanceof PlayerRideableJumping jumping) || !jumping.canJump() || jumping.getJumpCooldown() != 0) {
            jumpScale = 0.0F;
            return;
        }
        if (jumpTicks < 0 && ++jumpTicks == 0) jumpScale = 0.0F;
        if (jumpWas && !jump) {
            jumpTicks = -10;
            int power = Mth.floor(jumpScale * 100.0F);
            jumping.onPlayerJump(power);
            bot.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_RIDING_JUMP, power));
        } else if (!jumpWas && jump) {
            jumpTicks = 0;
            jumpScale = 0.0F;
        } else if (jumpWas) {
            jumpTicks++;
            jumpScale = jumpTicks < 10 ? jumpTicks * 0.1F : 0.8F + 2.0F / (jumpTicks - 9) * 0.1F;
        }
    }

    /**
     * Boat controls from {@code Boat.controlBoat}, called by the game only on the client: during the boat tick,
     * between drag ({@code floatBoat}) and movement. The bot controls after the boat tick; the next tick applies
     * drag before movement, so divide thrust and turn by next tick's drag ({@link #nextFriction}). After that,
     * speed, turn and heading exactly match the client.
     */
    static void steer(Boat boat, boolean left, boolean right, boolean up, boolean down) {
        boat.setInput(left, right, up, down);
        if (!boat.isVehicle()) return;
        try {
            float inv = nextFriction(boat);
            float turn = (right ? 1.0F : 0.0F) - (left ? 1.0F : 0.0F);
            float rotation = DELTA_ROTATION.getFloat(boat) * inv + turn;
            float push = 0.0F;
            if (right != left && !up && !down) push += 0.005F;
            if (up) push += 0.04F;
            if (down) push -= 0.005F;
            float yaw = boat.getYRot() + rotation;
            boat.setYRot(yaw);
            boat.setDeltaMovement(boat.getDeltaMovement().add(Mth.sin(-yaw * Mth.DEG_TO_RAD) * push / inv, 0.0, Mth.cos(yaw * Mth.DEG_TO_RAD) * push / inv));
            DELTA_ROTATION.setFloat(boat, rotation / inv);
            boat.setPaddleState(right && !left || up, left && !right || up);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("boat steering: " + e, e);
        }
    }

    /**
     * Drag {@code floatBoat} will compute on the next boat tick: boat status from {@code getStatus} (the tick
     * starts with the same status at the same position), multiplier as in {@code floatBoat}; entering water
     * does not reduce horizontal velocity.
     */
    private static float nextFriction(Boat boat) throws IllegalAccessException, InvocationTargetException {
        Boat.Status old = (Boat.Status) STATUS.get(boat);
        Boat.Status next = (Boat.Status) BOAT_STATUS.invoke(boat);
        if (old == Boat.Status.IN_AIR && next != Boat.Status.IN_AIR && next != Boat.Status.ON_LAND) return 1.0F;
        return switch (next) {
            case IN_WATER, UNDER_FLOWING_WATER, IN_AIR -> 0.9F;
            case UNDER_WATER -> 0.45F;
            case ON_LAND -> LAND_FRICTION.getFloat(boat);
        };
    }

    /** Mod vehicle key by its control-settings name ({@code press} step). Returns what was done. */
    String press(ServerPlayer p, String key) {
        Entity v = p.getVehicle();
        if (v == null || v.getControllingPassenger() != p) throw new Steps.Failed("the bot is not driving a vehicle: there are no vehicle keys");
        VehicleDriver driver = driver(v);
        String done = driver != null ? driver.press(p, v, key) : null;
        if (done != null) return done;
        List<String> keys = driver != null ? driver.keys() : List.of();
        throw new Steps.Failed(BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()) + " has no key \"" + key + "\""
                + (keys.isEmpty() ? ": it has no mod keys" : "; keys: " + String.join(", ", keys)));
    }

    /** The bot's vehicle for {@code bot}: its driver and mod keys. Null means the bot is on foot. */
    JsonObject describe(ServerPlayer p) {
        Entity v = p.getVehicle();
        if (v == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).toString());
        boolean driving = v.getControllingPassenger() == p;
        o.addProperty("driver", driving);
        if (driving) {
            VehicleDriver driver = driver(v);
            o.addProperty("steering", driver != null || local(p) ? "bot keys"
                    : "the server does not drive this vehicle: no almighty:driven_by_rider tag and no mod driver");
            JsonArray keys = new JsonArray();
            if (driver != null) driver.keys().forEach(keys::add);
            if (!keys.isEmpty()) o.add("keys", keys);
        }
        return o;
    }
}
