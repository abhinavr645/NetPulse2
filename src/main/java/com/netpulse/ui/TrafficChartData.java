package com.netpulse.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.netpulse.core.NetworkSpeedCalculator;
import com.netpulse.core.RollingTrafficWindow;
import com.netpulse.model.SpeedTestPeriod;
import com.netpulse.model.TrafficHistorySample;

/** JavaFX-free projection of immutable history. No resampling or synthetic points. */
public final class TrafficChartData {
    private final GaugeScale scale = new GaugeScale(1);

    public record Point(double secondsAgo, double downloadMbps, double uploadMbps) { }
    public record Segment(List<Point> points) {
        public Segment { points = List.copyOf(points); }
    }
    public record Band(long id, double fromSecondsAgo, double toSecondsAgo, boolean active,
            Optional<com.netpulse.model.SpeedTestState.Phase> outcome) { }
    public record Frame(Instant wallTime, List<Segment> segments, List<Band> bands, double maximumMbps) {
        public Frame { segments = List.copyOf(segments); bands = List.copyOf(bands); }
        public int pointCount() { return segments.stream().mapToInt(segment -> segment.points().size()).sum(); }
        public boolean activeTest() { return bands.stream().anyMatch(Band::active); }
    }

    public Frame project(List<TrafficHistorySample> history, List<SpeedTestPeriod> periods,
            long nowNanos, Instant wallTime) {
        var segments = new ArrayList<Segment>();
        var points = new ArrayList<Point>();
        TrafficHistorySample previous = null;
        // Inputs come from the bounded rolling window; cap defensively for other component users.
        for (var sample : history.subList(Math.max(0, history.size() - RollingTrafficWindow.MAX_SAMPLES), history.size())) {
            long age = nowNanos - sample.sampledAtNanos();
            if (age < 0 || age > RollingTrafficWindow.WINDOW_NANOS || sample.measurement().speed().isEmpty()) {
                flush(segments, points);
                previous = null;
                continue;
            }
            if (previous != null && (sample.adapterBoundary() || !sample.adapter().equals(previous.adapter())
                    || sample.sequence() != previous.sequence() + 1
                    || sample.sampledAtNanos() - previous.sampledAtNanos() <= 0
                    || sample.sampledAtNanos() - previous.sampledAtNanos() > NetworkSpeedCalculator.MAX_SAMPLING_GAP_NANOS)) {
                flush(segments, points);
            }
            var speed = sample.measurement().speed().orElseThrow();
            scale.include(Math.max(speed.downloadMbps(), speed.uploadMbps()));
            points.add(new Point(-age / 1_000_000_000.0, speed.downloadMbps(), speed.uploadMbps()));
            previous = sample;
        }
        flush(segments, points);

        var bands = new ArrayList<Band>();
        for (var period : periods.subList(Math.max(0, periods.size() - RollingTrafficWindow.MAX_SAMPLES), periods.size())) {
            long sinceStart = nowNanos - period.startedAtNanos();
            if (sinceStart < 0) { continue; }
            long endAge = period.end().map(end -> nowNanos - end.atNanos()).orElse(0L);
            if (endAge > RollingTrafficWindow.WINDOW_NANOS) { continue; }
            bands.add(new Band(period.id(), Math.max(-60, -sinceStart / 1_000_000_000.0),
                    Math.min(0, -endAge / 1_000_000_000.0), period.active(), period.outcome()));
        }
        return new Frame(wallTime, segments, bands, scale.maximum());
    }

    private static void flush(List<Segment> segments, List<Point> points) {
        if (!points.isEmpty()) { segments.add(new Segment(points)); points.clear(); }
    }
}
