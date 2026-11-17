package com.netpulse.ui;

import static org.junit.jupiter.api.Assertions.*;
import static com.netpulse.support.TrafficSamples.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.netpulse.core.RollingTrafficWindow;
import com.netpulse.model.SpeedMeasurement.Status;
import com.netpulse.model.SpeedTestPeriod;
import com.netpulse.model.SpeedTestState.Phase;
import com.netpulse.model.TrafficHistorySample;
import com.netpulse.support.AdapterFixture;
import org.junit.jupiter.api.Test;

class TrafficChartDataTest {
    private final TrafficChartData chart = new TrafficChartData();
    private static final long SECOND = 1_000_000_000L;

    @Test void emptyAndIdleHistoryAreDifferentWithoutInventedPoints() {
        var empty = chart.project(List.of(), List.of(), SECOND, Instant.EPOCH);
        assertEquals(0, empty.pointCount());
        assertEquals(1, empty.maximumMbps());
        var idle = frame(List.of(sample(1, 1, 0, 0)), 1);
        assertEquals(1, idle.pointCount());
        assertEquals(0, idle.segments().getFirst().points().getFirst().downloadMbps());
        assertEquals(0, idle.segments().getFirst().points().getFirst().uploadMbps());
        assertEquals(1, idle.maximumMbps());
    }

    @Test void placesPointsUsingRealCounterTimeNotListIndexOrPublicationTime() {
        var first = sample(1, 10, 125_000, 250_000);
        var second = sample(2, 12, 250_000, 125_000);
        var projected = frame(List.of(first, second), 15);
        var points = projected.segments().getFirst().points();
        assertEquals(-5, points.getFirst().secondsAgo());
        assertEquals(-3, points.getLast().secondsAgo());
        assertEquals(1, points.getFirst().downloadMbps());
        assertEquals(2, points.getFirst().uploadMbps());
        assertEquals(2, points.size());
    }

    @Test void usesCounterTimeWhenObservationWasPublishedLater() {
        var reading = available(1, 8, 125_000, 0);
        var delayed = TrafficHistorySample.from(snapshot(1, 10 * SECOND, ADAPTER, reading.measurement()), true);
        assertEquals(-4, frame(List.of(delayed), 12).segments().getFirst().points().getFirst().secondsAgo());
    }

    @Test void unavailableReadingsBreakBothLinesAndDoNotBecomeZero() {
        var unavailable = TrafficHistorySample.from(gap(2, 2, Status.READ_FAILED, ADAPTER), false);
        var frame = frame(List.of(sample(1, 1, 0, 0), unavailable, sample(3, 3, 125_000, 250_000)), 3);
        assertEquals(2, frame.segments().size());
        assertEquals(2, frame.pointCount());
        assertEquals(1, frame.segments().getFirst().points().size());
    }

    @Test void preservesAnExplicitAdapterBoundaryEvenWithAvailableValues() {
        var boundary = TrafficHistorySample.from(available(2, 2, 125_000, 0), true);
        assertEquals(2, frame(List.of(sample(1, 1, 125_000, 0), boundary), 2).segments().size());
    }

    @Test void changedAdapterIdentityAlsoBreaksTheLineWithoutABoundaryFlag() {
        var replacement = TrafficHistorySample.from(available(2, SECOND, 2 * SECOND,
                125_000, 0, new AdapterFixture(2).build()), false);
        assertEquals(2, frame(List.of(sample(1, 1, 125_000, 0), replacement), 2).segments().size());
    }

    @Test void missingSequenceOrExcessiveElapsedTimeDoesNotBridgeMissingReadings() {
        assertEquals(2, frame(List.of(sample(1, 1, 1, 1), sample(3, 2, 1, 1)), 2).segments().size());
        assertEquals(2, frame(List.of(sample(1, 1, 1, 1), sample(2, 10, 1, 1)), 10).segments().size());
    }

    @Test void expiryRetainsExactSixtySecondBoundaryAndRejectsFutureSamples() {
        var point = sample(1, 1, 1, 1);
        assertEquals(1, frame(List.of(point), 61).pointCount());
        assertEquals(0, chart.project(List.of(point), List.of(), 61 * SECOND + 1, Instant.EPOCH).pointCount());
        assertEquals(0, frame(List.of(sample(2, 3, 1, 1)), 2).pointCount());
    }

    @Test void yAxisIncludesUploadExpandsAndRemainsStableOnLowOrAbsentHistory() {
        assertEquals(5, frame(List.of(sample(1, 1, 125_000, 500_000)), 1).maximumMbps());
        assertEquals(20, frame(List.of(sample(2, 2, 1_500_000, 0)), 2).maximumMbps());
        assertEquals(20, frame(List.of(sample(3, 3, 0, 0)), 3).maximumMbps());
        assertEquals(20, frame(List.of(), 100).maximumMbps());
    }

    @Test void frameCollectionsAreImmutableAndHardBounded() {
        var input = new ArrayList<TrafficHistorySample>();
        for (int i = 1; i <= 1000; i++) {
            input.add(TrafficHistorySample.from(available(i, (i - 1) * 1_000_000L,
                    i * 1_000_000L, 1, 1, ADAPTER), false));
        }
        var frame = frame(input, 2);
        assertEquals(RollingTrafficWindow.MAX_SAMPLES, frame.pointCount());
        input.clear();
        assertThrows(UnsupportedOperationException.class, () -> frame.segments().clear());
        assertThrows(UnsupportedOperationException.class, () -> frame.segments().getFirst().points().clear());
    }

    @Test void activeAndCompletedBandsUseMonotonicTimeAndClipToTheWindow() {
        var active = new SpeedTestPeriod(1, 1, 10 * SECOND, Instant.MAX, Optional.empty(), Optional.empty());
        var done = new SpeedTestPeriod(2, 2, 5 * SECOND, Instant.MIN,
                Optional.of(new SpeedTestPeriod.End(20 * SECOND, Instant.MIN, true)), Optional.of(Phase.COMPLETED));
        var frame = chart.project(List.of(), List.of(active, done), 75 * SECOND, Instant.EPOCH);
        assertTrue(frame.activeTest());
        assertEquals(-60, frame.bands().getFirst().fromSecondsAgo());
        assertEquals(0, frame.bands().getFirst().toSecondsAgo());
        assertEquals(-60, frame.bands().getLast().fromSecondsAgo());
        assertEquals(-55, frame.bands().getLast().toSecondsAgo());
        assertFalse(frame.bands().getLast().active());
        assertEquals(Optional.of(Phase.COMPLETED), frame.bands().getLast().outcome());
        assertEquals(Instant.EPOCH, frame.wallTime());
    }

    @Test void completedBandsExpireAndRunningBandsRemainBoundedToVisibleWindow() {
        var end = Optional.of(new SpeedTestPeriod.End(20 * SECOND, Instant.EPOCH, true));
        var period = new SpeedTestPeriod(1, 1, 0, Instant.EPOCH, end, Optional.of(Phase.CANCELLED));
        assertEquals(1, chart.project(List.of(), List.of(period), 80 * SECOND, Instant.EPOCH).bands().size());
        assertTrue(chart.project(List.of(), List.of(period), 80 * SECOND + 1, Instant.EPOCH).bands().isEmpty());
    }

    private TrafficChartData.Frame frame(List<TrafficHistorySample> samples, long seconds) {
        return chart.project(samples, List.of(), seconds * SECOND, Instant.EPOCH.plusSeconds(seconds));
    }
    private static TrafficHistorySample sample(long sequence, long second, long down, long up) {
        return TrafficHistorySample.from(available(sequence, second, down, up), false);
    }
}
