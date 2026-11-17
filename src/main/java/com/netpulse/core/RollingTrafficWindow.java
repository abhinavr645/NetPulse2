package com.netpulse.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import com.netpulse.model.AdapterInfo;
import com.netpulse.model.MonitoringSnapshot;
import com.netpulse.model.SpeedMeasurement.Status;
import com.netpulse.model.TrafficHistorySample;

/** One sampling writer, lock-free immutable reads, no interpolation or timer of its own. */
public final class RollingTrafficWindow {
    public static final long WINDOW_NANOS = 60_000_000_000L;
    public static final int MAX_SAMPLES = 128;

    private final Deque<TrafficHistorySample> samples = new ArrayDeque<>();
    private volatile List<TrafficHistorySample> published = List.of();
    private long lastSequence;
    private long lastObservedAtNanos;
    private long lastSampledAtNanos;
    private Optional<AdapterInfo.Id> previousAdapter = Optional.empty();

    public boolean accept(MonitoringSnapshot snapshot) {
        Optional<AdapterInfo.Id> adapter = snapshot.selection().map(selection -> selection.adapter().id());
        boolean boundary = lastSequence == 0 || !adapter.equals(previousAdapter)
                || snapshot.measurement().status() == Status.ADAPTER_CHANGED;
        TrafficHistorySample sample = snapshot.sequence() > 0
                ? TrafficHistorySample.from(snapshot, boundary) : null;
        if (sample == null || snapshot.sequence() <= lastSequence || (lastSequence > 0
                && (snapshot.observedAtNanos() - lastObservedAtNanos < 0
                    || sample.sampledAtNanos() - lastSampledAtNanos < 0))) {
            return false;
        }
        samples.addLast(sample);
        while (!samples.isEmpty() && snapshot.observedAtNanos() - samples.getFirst().sampledAtNanos() > WINDOW_NANOS) {
            samples.removeFirst();
        }
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
        lastSequence = snapshot.sequence();
        lastObservedAtNanos = snapshot.observedAtNanos();
        lastSampledAtNanos = sample.sampledAtNanos();
        previousAdapter = adapter;
        published = List.copyOf(samples);
        return true;
    }

    /** Read-time expiry also works while sampling is stopped or a provider is blocked. */
    public List<TrafficHistorySample> snapshot(long nowNanos) {
        return retainRecent(published, nowNanos);
    }

    public static List<TrafficHistorySample> retainRecent(List<TrafficHistorySample> samples, long nowNanos) {
        return samples.stream().filter(sample -> nowNanos - sample.sampledAtNanos() <= WINDOW_NANOS).toList();
    }
}
