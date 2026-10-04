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
 * Транспорт под ботом — то, что делает с ним клиент водителя. Общий путь — ввод игрока: транспорт с меткой
 * {@link #DRIVEN_BY_RIDER} (ванильный — лошади, лодки, свинья, страйдер, верблюд; моды и датапаки добавляют свой) сервер
 * двигает сам ({@link BotPlayer}) по пакету ввода, а то, что ванильный клиент делает сверх него, бот повторяет по коду
 * клиента — руль лодки ({@code Boat.controlBoat}) и прыжок верхом ({@code LocalPlayer.aiStep}). Транспорт мода, который
 * клиент ведёт своим кодом, — привод из совместимости с модом ({@link VehicleDriver}, {@code Compat}); его клавиши
 * сверх ванильных — шаг {@code press} по имени клавиши в настройках управления. Поток сервера.
 */
final class Vehicles {
    /**
     * Транспорт, который ведёт ввод водителя ({@code xxa}/{@code zza}, прыжок, присед), а не клавиши клиента: его бот
     * ведёт «местным», движение считает сервер. Код мода за проверкой «водитель местный» может читать классы клиента —
     * транспорт мода в метку только после проверки, что он ведёт себя как ванильный.
     */
    static final TagKey<EntityType<?>> DRIVEN_BY_RIDER = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(Almighty.ID, "driven_by_rider"));
    private static final Method BOAT_STATUS = ObfuscationReflectionHelper.findMethod(Boat.class, "getStatus");
    private static final Field STATUS = ObfuscationReflectionHelper.findField(Boat.class, "status");
    private static final Field DELTA_ROTATION = ObfuscationReflectionHelper.findField(Boat.class, "deltaRotation");
    private static final Field LAND_FRICTION = ObfuscationReflectionHelper.findField(Boat.class, "landFriction");

    private final Bot bot;
    /** Приводы транспорта модов, которые стоят (совместимость), — свои у каждого бота. */
    private final List<VehicleDriver> drivers = Compat.vehicleDrivers();
    /** Прыжок верхом, как у клиента: тики удержания, сила и было ли нажато в прошлом тике. */
    private int jumpTicks;
    private float jumpScale;
    private boolean jumpWas;

    Vehicles(Bot bot) {
        this.bot = bot;
    }

    /** Ведёт ли игрок транспорт, который ведёт ввод водителя: его движение тогда считает сервер. */
    static boolean local(ServerPlayer p) {
        Entity v = p.getVehicle();
        return v != null && v.getControllingPassenger() == p && v.getType().is(DRIVEN_BY_RIDER);
    }

    /** Привод транспорта мода под ботом; null — нет. */
    private VehicleDriver driver(Entity v) {
        for (VehicleDriver d : drivers) if (d.drives(v)) return d;
        return null;
    }

    /** Тик бота, после пакета ввода: руль, прыжок верхом, привод транспорта мода. */
    void tick(ServerPlayer p, Set<Controls.Key> held) {
        boolean jump = held.contains(Controls.Key.JUMP);
        Entity v = p.getVehicle();
        if (v != null && v.getControllingPassenger() == p) {
            VehicleDriver driver = driver(v);
            if (driver != null) {
                // клиент не двигает транспорт в чанках, которых у него нет; бот — пока они не готовы на сервере
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
     * Ход транспорта от клиента водителя сервер принимает так ({@code handleMoveVehicle}): водитель садится на своё
     * место в сдвинутом транспорте ({@code positionRider}; взгляд и прошлое место — свои:
     * {@code resyncPlayerWithVehicle} NeoForge), движение и статистика езды — по ходу транспорта; чанки за ним — в конце
     * тика бота ({@link Controls}). Иначе место седоку давал бы только тик транспорта в мире, а мир не тикает транспорт
     * вне дальности симуляции: шаг пилота уводил самолёт туда, бот оставался, с ним — его чанки, и самолёт уже не тикал.
     */
    private static void moved(ServerPlayer p, Entity v, Vec3 from) {
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
     * Прыжок верхом — как {@code LocalPlayer.aiStep}: удержание копит силу, отпускание прыгает. Прыжок считает
     * транспорт ({@code onPlayerJump}, у живого игрока — его клиент), сервер получает тот же пакет, что от клиента.
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
     * Руль лодки — {@code Boat.controlBoat}, который игра зовёт только на клиенте: в тике лодки между торможением
     * ({@code floatBoat}) и движением. Бот правит после тика лодки: следующий тик сперва тормозит, потом двигает,
     * поэтому толчок и доворот делятся на торможение следующего тика ({@link #nextFriction}) — после него скорость,
     * доворот и курс ровно те, что у клиента.
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
            throw new IllegalStateException("руль лодки: " + e, e);
        }
    }

    /**
     * Торможение, которое посчитает {@code floatBoat} в следующем тике лодки: состояние лодки — {@code getStatus} (тик
     * начнёт с него же, на том же месте), множитель — как в {@code floatBoat}; приводнение скорость по горизонтали не
     * гасит.
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

    /** Клавиша транспорта мода по имени в настройках управления (шаг {@code press}). Итог — что сделано. */
    String press(ServerPlayer p, String key) {
        Entity v = p.getVehicle();
        if (v == null || v.getControllingPassenger() != p) throw new Steps.Failed("бот не ведёт транспорт: клавиш транспорта нет");
        VehicleDriver driver = driver(v);
        String done = driver != null ? driver.press(p, v, key) : null;
        if (done != null) return done;
        List<String> keys = driver != null ? driver.keys() : List.of();
        throw new Steps.Failed("у " + BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()) + " нет клавиши «" + key + "»"
                + (keys.isEmpty() ? ": клавиш мода у него нет" : "; есть: " + String.join(", ", keys)));
    }

    /** Транспорт под ботом для {@code bot}: кто его ведёт и клавиши мода. null — бот пешком. */
    JsonObject describe(ServerPlayer p) {
        Entity v = p.getVehicle();
        if (v == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(v.getType()).toString());
        boolean driving = v.getControllingPassenger() == p;
        o.addProperty("driver", driving);
        if (driving) {
            VehicleDriver driver = driver(v);
            o.addProperty("steering", driver != null || local(p) ? "клавиши бота"
                    : "сервер этот транспорт не ведёт: нет метки almighty:driven_by_rider и привода мода");
            JsonArray keys = new JsonArray();
            if (driver != null) driver.keys().forEach(keys::add);
            if (!keys.isEmpty()) o.add("keys", keys);
        }
        return o;
    }
}
