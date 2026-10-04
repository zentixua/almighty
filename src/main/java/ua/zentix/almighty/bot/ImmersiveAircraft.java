package ua.zentix.almighty.bot;

import com.mojang.logging.LogUtils;
import immersive_aircraft.entity.VehicleEntity;
import immersive_aircraft.network.c2s.CollisionMessage;
import immersive_aircraft.network.c2s.CommandMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Самолёты Immersive Aircraft под ботом. Полёт IA двигает клиент пилота: в тике самолёта, когда пилот «местный», IA
 * читает его клавиши ({@code VehicleEntity.tickPilot}, классы клиента) и считает полёт. На сервере клиента нет, поэтому
 * бот для IA не «местный» ({@link Vehicles#local}), а шаг пилота делает сам, после тика самолёта, — методами IA в том
 * же порядке: рули ({@code setInputs}), скорость ({@code updateVelocity}), ускоритель ({@code applyBoost}),
 * управление ({@code updateController}), движение ({@code move}), сглаживание рулей. Удар о препятствие (его считает
 * клиент в {@code move}) и клавиши высадки и ускорителя — сообщения клиента IA, их обработкой на сервере
 * ({@code receiveServer}). Класс грузится, только когда мод стоит. Поток сервера.
 */
final class ImmersiveAircraft {
    static final String MOD_ID = "immersive_aircraft";
    /** Клавиши IA сверх ванильных (имена в настройках управления). Рули — клавиши бота, как у клиента IA в NeoForge. */
    static final String DISMOUNT = "key.immersive_aircraft.dismount", BOOST = "key.immersive_aircraft.boost";
    static final List<String> KEYS = List.of(DISMOUNT, BOOST);

    private static final Logger LOG = LogUtils.getLogger();
    private static final Method UPDATE_VELOCITY = method("updateVelocity");
    private static final Method APPLY_BOOST = method("applyBoost");
    private static final Method UPDATE_CONTROLLER = method("updateController");
    private static final boolean broken = UPDATE_VELOCITY == null || APPLY_BOOST == null || UPDATE_CONTROLLER == null;

    private ImmersiveAircraft() {}

    static boolean is(Entity e) {
        return e instanceof VehicleEntity;
    }

    private static Method method(String name) {
        try {
            Method m = VehicleEntity.class.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Almighty: у Immersive Aircraft нет {} — боты его самолёты не ведут: {}", name, e.toString());
            return null;
        }
    }

    /**
     * Шаг пилота — то, что делает тик самолёта у клиента пилота: x — влево (+1) или вправо, y — вверх (прыжок) или
     * вниз (присед), z — вперёд или назад (у самолёта — штурвал от себя или на себя). {@code boost} — ускоритель в конце
     * прошлого шага: клиент смотрит его до того, как тик самолёта убавит его на единицу. Итог — ускоритель после шага.
     */
    static int fly(ServerPlayer pilot, Entity e, float x, float y, float z, int boost) {
        VehicleEntity v = (VehicleEntity) e;
        if (broken) return v.getBoost();
        v.setInputs(x, y, z);
        call(UPDATE_VELOCITY, v);
        if (boost > 0 || v.getBoost() > 0) call(APPLY_BOOST, v);
        call(UPDATE_CONTROLLER, v);
        Vec3 movement = v.getDeltaMovement();
        Vec3 prediction = v.position().add(movement);
        v.move(MoverType.SELF, movement);
        collision(pilot, v, movement, prediction);
        v.pressingInterpolatedX.update(x);
        v.pressingInterpolatedY.update(y);
        v.pressingInterpolatedZ.update(z);
        return v.getBoost();
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

    /** Удар о препятствие — как клиент IA ({@code VehicleEntity.move}): сила удара сообщением о столкновении. */
    private static void collision(ServerPlayer pilot, VehicleEntity v, Vec3 movement, Vec3 prediction) {
        if (!v.verticalCollision && !v.horizontalCollision) return;
        double error = prediction.distanceTo(v.position());
        if (error > movement.length()) return;
        float collision = (float) (error - (v.verticalCollision ? Math.abs(v.getGravity()) : 0.0)) - 0.05F;
        if (collision <= 0.0F) return;
        float repeat = 1.0F - (v.getHurtTime() + 1) / 10.0F;
        if (repeat > 1.0E-4F) new CollisionMessage(collision * repeat * repeat).receiveServer(pilot);
    }

    /** Клавиша IA — как клиент пилота ({@code VehicleEntity.tickPilot}). null — такой клавиши у IA нет. */
    static String press(ServerPlayer pilot, Entity e, String key, Vehicles state) {
        VehicleEntity v = (VehicleEntity) e;
        if (key.equals(DISMOUNT)) {
            // в воздухе клиент сперва предупреждает, высадка — вторым нажатием за секунду
            if (!v.onGround() && v.tickCount - state.lastTriedToExit() >= 20) {
                state.triedToExit(v.tickCount);
                return "в воздухе: высадка — ещё одним нажатием в течение секунды";
            }
            new CommandMessage(CommandMessage.Key.DISMOUNT, v.getDeltaMovement()).receiveServer(pilot);
            pilot.setJumping(false);
            return "высадка";
        }
        if (key.equals(BOOST)) {
            if (!v.canBoost()) return "ускоритель недоступен: нет ракеты в слоте или он уже работает";
            new CommandMessage(CommandMessage.Key.BOOST, v.getDeltaMovement()).receiveServer(pilot);
            return "ускоритель";
        }
        return null;
    }
}
