package com.netpulse.core;

import java.util.Objects;

import com.netpulse.model.AdapterInfo;
import com.netpulse.model.CounterReading;
import com.netpulse.model.NetworkSpeed;
import com.netpulse.model.SpeedMeasurement;
import com.netpulse.model.SpeedMeasurement.Status;
import com.netpulse.model.TrafficDelta;

/** Stateful baseline calculator; owned by one sampling thread. */
public final class NetworkSpeedCalculator {
    public static final long MAX_SAMPLING_GAP_NANOS = 3_000_000_000L;

    private AdapterInfo.Id previousAdapter;
    private CounterReading previous;

    public SpeedMeasurement calculate(AdapterInfo.Id adapter, CounterReading current) {
        Objects.requireNonNull(adapter);
        Objects.requireNonNull(current);
        if (current.receivedBytes() < 0 || current.transmittedBytes() < 0) {
            reset();
            return SpeedMeasurement.unavailable(Status.INVALID_COUNTERS);
        }
        if (previous == null) {
            baseline(adapter, current);
            return SpeedMeasurement.unavailable(Status.BASELINE);
        }
        if (!adapter.equals(previousAdapter)) {
            baseline(adapter, current);
            return SpeedMeasurement.unavailable(Status.ADAPTER_CHANGED);
        }

        // Subtraction also works across System.nanoTime()'s signed wraparound.
        long elapsedNanos = current.sampledAtNanos() - previous.sampledAtNanos();
        if (elapsedNanos <= 0) {
            reset();
            return SpeedMeasurement.unavailable(Status.INVALID_TIME);
        }
        if (elapsedNanos > MAX_SAMPLING_GAP_NANOS) {
            baseline(adapter, current);
            return SpeedMeasurement.unavailable(Status.SAMPLING_GAP);
        }
        if (current.receivedBytes() < previous.receivedBytes()
                || current.transmittedBytes() < previous.transmittedBytes()) {
            baseline(adapter, current);
            return SpeedMeasurement.unavailable(Status.COUNTER_RESET);
        }

        double seconds = elapsedNanos / 1_000_000_000.0;
        TrafficDelta delta = new TrafficDelta(current.receivedBytes() - previous.receivedBytes(),
                current.transmittedBytes() - previous.transmittedBytes(),
                previous.sampledAtNanos(), current.sampledAtNanos());
        double download = delta.downloadedBytes() / seconds;
        double upload = delta.uploadedBytes() / seconds;
        baseline(adapter, current);
        if (!Double.isFinite(download) || download < 0 || !Double.isFinite(upload) || upload < 0) {
            reset();
            return SpeedMeasurement.unavailable(Status.INVALID_COUNTERS);
        }
        return SpeedMeasurement.available(new NetworkSpeed(download, upload), delta);
    }

    public void reset() {
        previousAdapter = null;
        previous = null;
    }

    private void baseline(AdapterInfo.Id adapter, CounterReading reading) {
        previousAdapter = adapter;
        previous = reading;
    }
}
