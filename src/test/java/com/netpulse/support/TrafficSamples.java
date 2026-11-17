package com.netpulse.support;

import java.time.Instant;
import java.util.Optional;

import com.netpulse.model.AdapterInfo;
import com.netpulse.model.AdapterSelection;
import com.netpulse.model.AdapterSelection.Identification;
import com.netpulse.model.MonitoringSnapshot;
import com.netpulse.model.MonitoringSnapshot.ConnectionStatus;
import com.netpulse.model.NetworkSpeed;
import com.netpulse.model.SpeedMeasurement;
import com.netpulse.model.SpeedMeasurement.Status;
import com.netpulse.model.TrafficDelta;

/** Synthetic, sequenced measurements with exact bytes and a controllable clock. */
public final class TrafficSamples {
    public static final AdapterInfo ADAPTER = new AdapterFixture(1).build();

    private TrafficSamples() { }

    public static MonitoringSnapshot available(long sequence, long second, long download, long upload) {
        return available(sequence, (second - 1) * 1_000_000_000L, second * 1_000_000_000L,
                download, upload, ADAPTER);
    }

    public static MonitoringSnapshot available(long sequence, long startNanos, long endNanos,
            long download, long upload, AdapterInfo adapter) {
        double seconds = (endNanos - startNanos) / 1_000_000_000.0;
        SpeedMeasurement measurement = SpeedMeasurement.available(
                new NetworkSpeed(download / seconds, upload / seconds),
                new TrafficDelta(download, upload, startNanos, endNanos));
        return snapshot(sequence, endNanos, adapter, measurement);
    }

    public static MonitoringSnapshot gap(long sequence, long second, Status status, AdapterInfo adapter) {
        return snapshot(sequence, second * 1_000_000_000L, adapter, SpeedMeasurement.unavailable(status));
    }

    public static MonitoringSnapshot snapshot(long sequence, long observedNanos,
            AdapterInfo adapter, SpeedMeasurement measurement) {
        return new MonitoringSnapshot(sequence, Instant.EPOCH.plusNanos(observedNanos), observedNanos,
                adapter == null ? ConnectionStatus.DISCONNECTED : ConnectionStatus.CONNECTED,
                Optional.ofNullable(adapter).map(value -> new AdapterSelection(value, Identification.LIKELY_PHYSICAL)),
                measurement, "Synthetic sample");
    }
}
