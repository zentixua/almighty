package ua.zentix.almighty.bot;

/**
 * Когда клиент шлёт серверу пакет движения ({@code LocalPlayer.sendPosition}): сдвинулся с места прошлого пакета больше
 * чем на {@link #MIN_MOVE} блока или {@link #REMINDER_TICKS} тиков не сообщал места, повернул голову или встал на землю
 * либо оторвался от неё. На каждый такой пакет сервер пересчитывает, кого игрок видит ({@code ServerChunkCache.move} →
 * {@code ChunkMap.move}: обход всех отслеживаемых сущностей мира). Бот, который делал это каждый тик и стоя на месте,
 * стоил серверу как клиент, который всё время идёт: живой Zearth 06.10.2026 (8 ботов, 5264 сущности) — ≈7 мс тика,
 * его копия с 6 ботами в городах (~30 тыс. сущностей) — 37 % потока сервера.
 * <p>
 * Седок шлёт пакет каждый тик, сам по себе: его решает вызывающий. Без мира — проверяется юнит-тестом.
 */
final class MovePackets {
    /** Наименьший сдвиг, о котором клиент сообщает, блоков. */
    static final double MIN_MOVE = 2.0E-4;
    /** Через сколько тиков без сдвига клиент всё равно сообщает место. */
    static final int REMINDER_TICKS = 20;

    private boolean sent;
    private double x, y, z;
    private float yRot, xRot;
    private boolean onGround;
    private int reminder;

    /** Тик клиента кончился здесь: ушёл бы пакет движения. Первый тик — всегда. */
    boolean tick(double x, double y, double z, float yRot, float xRot, boolean onGround) {
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        boolean moved = !sent || dx * dx + dy * dy + dz * dz > MIN_MOVE * MIN_MOVE || ++reminder >= REMINDER_TICKS;
        boolean rotated = !sent || yRot != this.yRot || xRot != this.xRot;
        boolean landed = !sent || onGround != this.onGround;
        if (moved) {
            this.x = x;
            this.y = y;
            this.z = z;
            reminder = 0;
        }
        if (rotated) {
            this.yRot = yRot;
            this.xRot = xRot;
        }
        this.onGround = onGround;
        sent = true;
        return moved || rotated || landed;
    }
}
