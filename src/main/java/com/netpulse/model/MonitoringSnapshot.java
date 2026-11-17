package com.netpulse.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Link status does not assert internet connectivity. Monotonic time is process-local. */
public record MonitoringSnapshot(Instant observedAt, long observedAtNanos,
        ConnectionStatus connectionStatus, Optional<AdapterSelection> selection,
        SpeedMeasurement measurement, String detail) {
    public MonitoringSnapshot {
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(connectionStatus);
        Objects.requireNonNull(selection);
        Objects.requireNonNull(measurement);
        detail = Objects.requireNonNullElse(detail, "");
    }

    public enum ConnectionStatus { CONNECTED, DISCONNECTED, UNKNOWN, STOPPED }
}
