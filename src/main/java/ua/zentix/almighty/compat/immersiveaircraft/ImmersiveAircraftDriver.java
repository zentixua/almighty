package ua.zentix.almighty.compat.immersiveaircraft;

import com.mojang.logging.LogUtils;
import immersive_aircraft.entity.VehicleEntity;
import immersive_aircraft.network.c2s.CollisionMessage;
import immersive_aircraft.network.c2s.CommandMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import ua.zentix.almighty.bot.VehicleDriver;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Immersive Aircraft compatibility: bot-driven aircraft. IA flight runs on the pilot's client; during the
 * aircraft tick, if the pilot is "local", IA reads its keys ({@code VehicleEntity.tickPilot}, client classes).
 * IA does not read server-side player input, and a bot cannot be "local" for IA (key classes are absent on a
 * dedicated server), so the bot runs the pilot step itself after the aircraft tick, using IA methods in the
 * same order: controls ({@code setInputs}), velocity ({@code updateVelocity}), boost ({@code applyBoost}),
 * controller ({@code updateController}), movement ({@code move}), control smoothing. Collisions (computed by the
 * client in {@code move}), dismount and boost keys use IA client messages handled on the server
 * ({@code receiveServer}). Load this class only when the mod is installed ({@code Compat}). Server thread.
 * <p>
 * Reflection only where no public path exists. Checked against Immersive Aircraft 1.5.2, 1.6.0 and 1.7.0 for
 * 1.21.1 (NeoForge): the pilot step uses protected {@code updateVelocity}, {@code applyBoost} and
 * {@code updateController} methods of {@code VehicleEntity} (overridden by subclasses), called only from
 * {@code VehicleEntity.tick} when the pilot is "local"; there is no public step method. Obtain methods from
 * {@code VehicleEntity} ({@code getDeclaredMethod}); invocation dispatches to subclass overrides. The rest is
 * public: {@code setInputs}, {@code move}, control smoothing, messages. If another IA version lacks a method,
 * log a warning and disable the driver ({@link #drives} returns false); the bot does not drive aircraft.
 */
public final class ImmersiveAircraftDriver implements VehicleDriver {
    public static final String MOD_ID = "immersive_aircraft";
    /** IA keys beyond vanilla controls (control-settings names). Steering uses bot keys, like the NeoForge IA client. */
    static final String DISMOUNT = "key.immersive_aircraft.dismount", BOOST = "key.immersive_aircraft.boost";
    private static final List<String> KEYS = List.of(DISMOUNT, BOOST);

    private static final Logger LOG = LogUtils.getLogger();
    private static final Method UPDATE_VELOCITY = method("updateVelocity");
    private static final Method APPLY_BOOST = method("applyBoost");
    private static final Method UPDATE_CONTROLLER = method("updateController");
    private static final boolean BROKEN = UPDATE_VELOCITY == null || APPLY_BOOST == null || UPDATE_CONTROLLER == null;

    /**
     * The aircraft in which the pilot client warned about airborne dismounting, and its tick: IA stores this on
     * the aircraft; another aircraft (or the same after chunk reload, a new entity) has not issued the warning.
     */
    private WeakReference<Entity> warned = new WeakReference<>(null);
    private int warnedAt;
    /**
     * Aircraft from the previous pilot step and its boost at step end: the client reads boost before the aircraft
     * tick decrements it by one.
     */
    private WeakReference<Entity> flown = new WeakReference<>(null);
    private int boost;

    private static Method method(String name) {
        try {
            Method m = VehicleEntity.class.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Almighty: Immersive Aircraft has no {}; bots will not fly its aircraft: {}", name, e.toString());
            return null;
        }
    }

    @Override
    public boolean drives(Entity vehicle) {
        return !BROKEN && vehicle instanceof VehicleEntity;
    }

    /** Pilot step, as run by the aircraft tick on the pilot's client (aircraft y is throttle, z is the flight stick). */
    @Override
    public void tick(ServerPlayer pilot, Entity vehicle, float x, float y, float z) {
        VehicleEntity v = (VehicleEntity) vehicle;
        int before = flown.get() == v ? boost : 0;
        v.setInputs(x, y, z);
        call(UPDATE_VELOCITY, v);
        if (before > 0 || v.getBoost() > 0) call(APPLY_BOOST, v);
        call(UPDATE_CONTROLLER, v);
        Vec3 movement = v.getDeltaMovement();
        Vec3 prediction = v.position().add(movement);
        v.move(MoverType.SELF, movement);
        collision(pilot, v, movement, prediction);
        v.pressingInterpolatedX.update(x);
        v.pressingInterpolatedY.update(y);
        v.pressingInterpolatedZ.update(z);
        if (flown.get() != v) flown = new WeakReference<>(v);
        boost = v.getBoost();
    }

    private static void call(Method m, VehicleEntity v) {
        try {
            m.invoke(v);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error r) throw r;
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Collision as on the IA client ({@code VehicleEntity.move}): send impact strength in a collision message. */
    private static void collision(ServerPlayer pilot, VehicleEntity v, Vec3 movement, Vec3 prediction) {
        if (!v.verticalCollision && !v.horizontalCollision) return;
        double error = prediction.distanceTo(v.position());
        if (error > movement.length()) return;
        float collision = (float) (error - (v.verticalCollision ? Math.abs(v.getGravity()) : 0.0)) - 0.05F;
        if (collision <= 0.0F) return;
        float repeat = 1.0F - (v.getHurtTime() + 1) / 10.0F;
        if (repeat > 1.0E-4F) new CollisionMessage(collision * repeat * repeat).receiveServer(pilot);
    }

    @Override
    public List<String> keys() {
        return KEYS;
    }

    /** IA key as on the pilot's client ({@code VehicleEntity.tickPilot}). */
    @Override
    public String press(ServerPlayer pilot, Entity vehicle, String key) {
        VehicleEntity v = (VehicleEntity) vehicle;
        if (key.equals(DISMOUNT)) {
            // In the air the client warns first; a second press within a second dismounts.
            if (!v.onGround() && (warned.get() != v || v.tickCount - warnedAt >= 20)) {
                warned = new WeakReference<>(v);
                warnedAt = v.tickCount;
                return "in the air: press again within a second to dismount";
            }
            new CommandMessage(CommandMessage.Key.DISMOUNT, v.getDeltaMovement()).receiveServer(pilot);
            pilot.setJumping(false);
            return "dismounted";
        }
        if (key.equals(BOOST)) {
            if (!v.canBoost()) return "boost unavailable: no rocket in the slot, or the boost is already running";
            new CommandMessage(CommandMessage.Key.BOOST, v.getDeltaMovement()).receiveServer(pilot);
            return "boost started";
        }
        return null;
    }
}
