package com.netpulse.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Link status does not assert internet connectivity. Monotonic time is process-local. */
public record MonitoringSnapshot(long sequence, Instant observedAt, long observedAtNanos,
        ConnectionStatus connectionStatus, Optional<AdapterSelection> selection,
        SpeedMeasurement measurement, String detail) {
    public MonitoringSnapshot {
        if (sequence < 0) {
            throw new IllegalArgumentException("Snapshot sequence cannot be negative");
        }
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(connectionStatus);
        Objects.requireNonNull(selection);
        Objects.requireNonNull(measurement);
        detail = Objects.requireNonNullElse(detail, "");
    }

    /** Sequence zero is reserved for lifecycle/unaccepted views. */
    public MonitoringSnapshot withSequence(long acceptedSequence) {
        return new MonitoringSnapshot(acceptedSequence, observedAt, observedAtNanos,
                connectionStatus, selection, measurement, detail);
    }

    public enum ConnectionStatus { CONNECTED, DISCONNECTED, UNKNOWN, STOPPED }
}
