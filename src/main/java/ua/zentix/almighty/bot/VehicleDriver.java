package ua.zentix.almighty.bot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * Привод транспорта мода, который ведёт клиент водителя своим кодом (читает клавиши клиента, считает полёт у пилота),
 * — то, чего общим путём (ввод игрока, метка {@code almighty:driven_by_rider}) не сделать. Только в совместимости с
 * модом ({@code compat/}), приводы бот берёт у {@code Compat}. Экземпляр — на бота, поток сервера.
 */
public interface VehicleDriver {
    /** Ведёт ли привод этот транспорт. */
    boolean drives(Entity vehicle);

    /**
     * Тик бота за рулём, после тика транспорта: x — влево (+1) или вправо (−1), y — прыжок (+1) или присед (−1),
     * z — вперёд (+1) или назад (−1).
     */
    void tick(ServerPlayer pilot, Entity vehicle, float x, float y, float z);

    /** Клавиши мода сверх ванильных — имена в настройках управления. */
    List<String> keys();

    /** Клавиша мода: итог — что сделано; null — такой клавиши у привода нет. */
    String press(ServerPlayer pilot, Entity vehicle, String key);
}
