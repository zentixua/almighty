package ua.zentix.almighty.work;

/**
 * Lag guard (plan 4.11): when the server's average tick time stays above the target tick length (50 ms at 20 ticks per
 * second; follows {@code /tick rate}) for {@value #HOLD_MS} ms, the GM budget drops to a quarter until it stays below
 * that for as long. GM work then deepens the lag less, yet builds and views still progress.
 */
public final class LagGuard {
    public static final long HOLD_MS = 10_000;

    private boolean lagging;
    /** Since when the average has been on the other side of the threshold; -1 if it is on the current side. */
    private long crossedSince = -1;

    /** Feed the average mspt and the target tick length at {@code nowMs}; returns true if the state changed. */
    public boolean update(double mspt, double targetMspt, long nowMs) {
        boolean over = mspt > targetMspt;
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
