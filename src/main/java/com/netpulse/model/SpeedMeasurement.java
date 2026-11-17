package com.netpulse.model;

import java.util.Objects;
import java.util.Optional;

/** Unavailable data is absence, never a fabricated zero or the previous speed. */
public record SpeedMeasurement(Status status, Optional<NetworkSpeed> speed, Optional<TrafficDelta> trafficDelta) {
    public SpeedMeasurement {
        Objects.requireNonNull(status);
        Objects.requireNonNull(speed);
        Objects.requireNonNull(trafficDelta);
        if ((status == Status.AVAILABLE) != speed.isPresent()
                || speed.isPresent() != trafficDelta.isPresent()) {
            throw new IllegalArgumentException("Available measurements require both speed and exact traffic deltas");
        }
    }

    public static SpeedMeasurement available(NetworkSpeed speed, TrafficDelta trafficDelta) {
        return new SpeedMeasurement(Status.AVAILABLE, Optional.of(speed), Optional.of(trafficDelta));
    }

    public static SpeedMeasurement unavailable(Status status) {
        return new SpeedMeasurement(status, Optional.empty(), Optional.empty());
    }

    public enum Status {
        AVAILABLE, BASELINE, ADAPTER_CHANGED, COUNTER_RESET, INVALID_COUNTERS,
        INVALID_TIME, SAMPLING_GAP, READ_FAILED, NO_ADAPTER, STALE, STOPPED
    }
}
