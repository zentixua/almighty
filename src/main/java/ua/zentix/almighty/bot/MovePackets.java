package ua.zentix.almighty.bot;

/**
 * When the client sends a movement packet ({@code LocalPlayer.sendPosition}): moved more than
 * {@link #MIN_MOVE} blocks from the last packet's position, no position report for {@link #REMINDER_TICKS} ticks,
 * head rotation, landing or leaving the ground. For each packet the server recalculates visible entities
 * ({@code ServerChunkCache.move} to {@code ChunkMap.move}: scans every tracked entity in the world).
 * A bot doing this every tick while stationary would cost as much as a constantly moving client:
 * Zearth with 8 bots and 5264 entities takes approximately 7 ms per tick; a copy with 6 bots in cities
 * (~30 thousand entities) takes 37% of the server thread.
 * <p>
 * A rider sends a packet every tick independently: the caller handles this. World-independent, covered by a unit test.
 */
final class MovePackets {
    /** Minimum displacement the client reports, in blocks. */
    static final double MIN_MOVE = 2.0E-4;
    /** Ticks without displacement after which the client reports its position anyway. */
    static final int REMINDER_TICKS = 20;

    private boolean sent;
    private double x, y, z;
    private float yRot, xRot;
    private boolean onGround;
    private int reminder;

    /** The client tick ended here: a movement packet would be sent. Always on the first tick. */
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
