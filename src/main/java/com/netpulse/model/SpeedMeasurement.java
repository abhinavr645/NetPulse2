package com.netpulse.model;

import java.util.Objects;
import java.util.Optional;

/** Unavailable data is absence, never a fabricated zero or the previous speed. */
public record SpeedMeasurement(Status status, Optional<NetworkSpeed> speed) {
    public SpeedMeasurement {
        Objects.requireNonNull(status);
        Objects.requireNonNull(speed);
        if ((status == Status.AVAILABLE) != speed.isPresent()) {
            throw new IllegalArgumentException("Only available measurements can contain a speed");
        }
    }

    public static SpeedMeasurement available(NetworkSpeed speed) {
        return new SpeedMeasurement(Status.AVAILABLE, Optional.of(speed));
    }

    public static SpeedMeasurement unavailable(Status status) {
        return new SpeedMeasurement(status, Optional.empty());
    }

    public enum Status {
        AVAILABLE, BASELINE, ADAPTER_CHANGED, COUNTER_RESET, INVALID_COUNTERS,
        INVALID_TIME, SAMPLING_GAP, READ_FAILED, NO_ADAPTER, STALE, STOPPED
    }
}
