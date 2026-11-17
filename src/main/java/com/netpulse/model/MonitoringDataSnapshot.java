package com.netpulse.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A coherent immutable view published after a sample updates both data structures. */
public record MonitoringDataSnapshot(Optional<MonitoringSnapshot> latestAccepted,
        SessionTrafficTotals sessionTotals, List<TrafficHistorySample> history) {
    public MonitoringDataSnapshot {
        Objects.requireNonNull(latestAccepted);
        Objects.requireNonNull(sessionTotals);
        history = List.copyOf(history);
    }

    public static MonitoringDataSnapshot empty() {
        return new MonitoringDataSnapshot(Optional.empty(), SessionTrafficTotals.ZERO, List.of());
    }
}
