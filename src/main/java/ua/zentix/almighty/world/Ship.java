package ua.zentix.almighty.world;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.List;
import java.util.UUID;

/**
 * Корабль мода физики (сейчас — Sable, на нём аппараты Create Aeronautics): его блоки живут на далёком участке
 * («плоте») того же мира, а в мире — их проекция с местом, поворотом и скоростью. Значения — на момент вызова, поток
 * сервера. Скриптам — {@code gm.ship(имя)}, мосту — метод {@code ships}; источник — {@code Compat.ships()}.
 */
public interface Ship {
    /** Имя, которое дал кораблю игрок; null — без имени. */
    String name();

    UUID uuid();

    ServerLevel level();

    /** Где в мире сейчас точка поворота корабля (на участке это {@link #pivot()}). */
    Vec3 pos();

    /** Точка поворота на участке. */
    Vec3 pivot();

    /** Поворот от сборки: направление на участке → направление в мире. */
    Quaterniond orientation();

    /** Скорость точки поворота в мире, блоков в секунду. */
    Vec3 velocity();

    /** Скорость точки мира, которая движется с кораблём, блоков в секунду. */
    Vec3 velocityAt(Vec3 world);

    /** Угловая скорость, радиан в секунду, по осям мира (по правилу правой руки). */
    Vec3 angularVelocity();

    /** Рамка корабля в мире. */
    AABB box();

    /** Рамка его блоков на участке (с запасом: из рамки в мире). */
    BoundingBox plotBox();

    /** Точка участка → где она в мире. */
    Vec3 toWorld(Vec3 plot);

    /** Точка мира → какое место участка в ней сейчас. */
    Vec3 toPlot(Vec3 world);

    /** Направление на участке (нос корабля, как его строили) → направление в мире. */
    Vec3 toWorldDir(Vec3 plotDir);

    /**
     * Блок-сущности корабля в готовых чанках его участка (моторы, горелки, рули): тип — id
     * ({@code create:propeller_bearing}); null — все.
     */
    List<BlockEntity> blockEntities(String type);

    /** Для ответа моста: имя, место, поворот, скорости, рамки. */
    JsonObject describe();
}
