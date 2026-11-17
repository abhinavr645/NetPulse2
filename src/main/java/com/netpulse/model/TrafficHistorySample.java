package com.netpulse.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One actual sample or observed gap; a boundary tells a renderer to start a new path. */
public record TrafficHistorySample(long sequence, Instant observedAt, long sampledAtNanos,
        Optional<AdapterInfo.Id> adapter, SpeedMeasurement measurement, boolean adapterBoundary) {
    public TrafficHistorySample {
        if (sequence <= 0) {
            throw new IllegalArgumentException("History requires an accepted sample sequence");
        }
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(adapter);
        Objects.requireNonNull(measurement);
    }

    public static TrafficHistorySample from(MonitoringSnapshot snapshot, boolean adapterBoundary) {
        long sampleTime = snapshot.measurement().trafficDelta()
                .map(TrafficDelta::endedAtNanos).orElse(snapshot.observedAtNanos());
        return new TrafficHistorySample(snapshot.sequence(), snapshot.observedAt(), sampleTime,
                snapshot.selection().map(selection -> selection.adapter().id()),
                snapshot.measurement(), adapterBoundary);
    }
}
