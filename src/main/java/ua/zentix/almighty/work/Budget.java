package ua.zentix.almighty.work;

import java.util.function.LongSupplier;

/** Время работы ведущего в одном тике сервера. Часы подменяются в проверках: GameTest не мерит настенное время. */
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
