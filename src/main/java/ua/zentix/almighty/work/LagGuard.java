package ua.zentix.almighty.work;

/**
 * Lag guard (plan 4.11): when the server's average tick time stays above {@value #LAG_MSPT} ms for
 * {@value #HOLD_MS} ms, GM work drops to its minimum (one world call and one job unit per tick) until it stays below
 * that for as long. GM work then cannot deepen lag the server is already in, yet still makes progress.
 */
public final class LagGuard {
    public static final double LAG_MSPT = 50;
    public static final long HOLD_MS = 10_000;

    private boolean lagging;
    /** Since when the average has been on the other side of the threshold; -1 if it is on the current side. */
    private long crossedSince = -1;

    /** Feed one tick's average mspt at {@code nowMs}; returns true if the state changed. */
    public boolean update(double mspt, long nowMs) {
        boolean over = mspt > LAG_MSPT;
        if (over == lagging) {
            crossedSince = -1;
            return false;
        }
        if (crossedSince < 0) crossedSince = nowMs;
        if (nowMs - crossedSince < HOLD_MS) return false;
        lagging = over;
        crossedSince = -1;
        return true;
    }

    public boolean lagging() {
        return lagging;
    }
}
