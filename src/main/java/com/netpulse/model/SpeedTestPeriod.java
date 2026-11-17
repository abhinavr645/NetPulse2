package com.netpulse.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Entire --format=json process lifetime, not individual transfer phases.
 * Wall time is descriptive; monotonic time controls duration and chart position.
 */
public record SpeedTestPeriod(long id, long testId, long startedAtNanos, Instant startedAt,
        Optional<End> end, Optional<SpeedTestState.Phase> outcome) {
    public SpeedTestPeriod {
        if (id <= 0 || testId <= 0) { throw new IllegalArgumentException("Positive period/test IDs required"); }
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(end);
        Objects.requireNonNull(outcome);
        end.ifPresent(value -> {
            if (value.atNanos() - startedAtNanos < 0) { throw new IllegalArgumentException("Negative process duration"); }
        });
        outcome.ifPresent(phase -> {
            if (phase == SpeedTestState.Phase.IDLE || phase == SpeedTestState.Phase.PREPARING
                    || phase == SpeedTestState.Phase.RUNNING || phase == SpeedTestState.Phase.PROCESSING
                    || end.isEmpty()) { throw new IllegalArgumentException("A terminal outcome requires a closed period"); }
        });
    }

    public boolean active() { return end.isEmpty(); }

    /** False only if process cleanup failed: tracking closed without claiming confirmed exit. */
    public record End(long atNanos, Instant at, boolean terminationConfirmed) {
        public End { Objects.requireNonNull(at); }
    }
}
