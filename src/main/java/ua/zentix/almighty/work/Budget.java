package ua.zentix.almighty.work;

import java.util.function.LongSupplier;

/** GM work time in one server tick. Tests substitute the clock: GameTest does not measure wall-clock time. */
public final class Budget {
    private final LongSupplier clock;
    private final long deadline;

    public Budget(LongSupplier clock, long nanos) {
        this.clock = clock;
        this.deadline = clock.getAsLong() + nanos;
    }

    public boolean left() {
        return clock.getAsLong() < deadline;
    }
}
