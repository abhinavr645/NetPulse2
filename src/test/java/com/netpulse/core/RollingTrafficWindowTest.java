package com.netpulse.core;

import static org.junit.jupiter.api.Assertions.*;
import static com.netpulse.support.TrafficSamples.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.netpulse.model.MonitoringDataSnapshot;
import com.netpulse.model.MonitoringSnapshot;
import com.netpulse.model.SessionTrafficTotals;
import com.netpulse.model.SpeedMeasurement.Status;
import com.netpulse.model.TrafficHistorySample;
import com.netpulse.support.AdapterFixture;
import org.junit.jupiter.api.Test;

class RollingTrafficWindowTest {
    private final RollingTrafficWindow window = new RollingTrafficWindow();

    @Test
    void anEmptyWindowContainsNoInventedReadings() {
        assertTrue(window.snapshot(0).isEmpty());
    }

    @Test
    void retainsTheExactSixtySecondBoundaryAndExpiresAnythingOlder() {
        window.accept(gap(1, 0, Status.BASELINE, ADAPTER));
        window.accept(available(2, 59, 10, 20));
        window.accept(available(3, 60, 20, 30));
        assertEquals(3, window.snapshot(60_000_000_000L).size());
        assertEquals(List.of(2L, 3L), sequences(window.snapshot(60_000_000_001L)));
        assertEquals(List.of(3L), sequences(window.snapshot(119_000_000_001L)));
        assertTrue(window.snapshot(120_000_000_001L).isEmpty());
    }

    @Test
    void newAcceptedSamplesEvictOldDataUsingTimeNotAnAssumedSampleCount() {
        window.accept(available(1, 1, 10, 20));
        window.accept(available(2, 15, 30, 40));
        window.accept(gap(3, 70, Status.SAMPLING_GAP, ADAPTER));
        assertEquals(List.of(2L, 3L), sequences(window.snapshot(70_000_000_000L)));
    }

    @Test
    void zeroTrafficIsARealPointAndUnavailableTrafficIsAGap() {
        window.accept(available(1, 1, 0, 0));
        window.accept(gap(2, 2, Status.READ_FAILED, ADAPTER));
        var samples = window.snapshot(2_000_000_000L);
        assertEquals(0, samples.getFirst().measurement().speed().orElseThrow().downloadMbps());
        assertEquals(0, samples.getFirst().measurement().speed().orElseThrow().uploadMbps());
        assertEquals(Status.READ_FAILED, samples.getLast().measurement().status());
        assertTrue(samples.getLast().measurement().speed().isEmpty());
        assertTrue(samples.getLast().measurement().trafficDelta().isEmpty());
    }

    @Test
    void preservesAdapterChangeAndReconnectionBoundaries() {
        var replacement = new AdapterFixture(2).build();
        window.accept(available(1, 1, 10, 20));
        window.accept(gap(2, 2, Status.ADAPTER_CHANGED, replacement));
        window.accept(available(3, 2_000_000_000L, 3_000_000_000L, 1, 2, replacement));
        window.accept(gap(4, 4, Status.NO_ADAPTER, null));
        window.accept(gap(5, 5, Status.BASELINE, replacement));
        var samples = window.snapshot(5_000_000_000L);
        assertTrue(samples.get(0).adapterBoundary());
        assertTrue(samples.get(1).adapterBoundary());
        assertEquals(replacement.id(), samples.get(1).adapter().orElseThrow());
        assertFalse(samples.get(2).adapterBoundary());
        assertTrue(samples.get(3).adapterBoundary());
        assertTrue(samples.get(4).adapterBoundary());
    }

    @Test
    void aLongSamplingDelayDoesNotFabricateIntermediatePoints() {
        window.accept(available(1, 1, 10, 20));
        window.accept(gap(2, 120, Status.SAMPLING_GAP, ADAPTER));
        assertEquals(List.of(2L), sequences(window.snapshot(120_000_000_000L)));
        assertEquals(Status.SAMPLING_GAP, window.snapshot(120_000_000_000L).getFirst().measurement().status());
    }

    @Test
    void usesTheCounterMeasurementTimeRatherThanItsLaterPublicationTime() {
        var measured = available(1, 8, 10, 20);
        window.accept(snapshot(1, 10_000_000_000L, ADAPTER, measured.measurement()));
        assertEquals(8_000_000_000L, window.snapshot(10_000_000_000L).getFirst().sampledAtNanos());
        assertTrue(window.snapshot(68_000_000_001L).isEmpty());
    }

    @Test
    void snapshotsAreImmutableAndRemainDetachedFromLaterUpdates() {
        window.accept(available(1, 1, 10, 20));
        List<TrafficHistorySample> before = window.snapshot(1_000_000_000L);
        assertThrows(UnsupportedOperationException.class, before::clear);
        window.accept(available(2, 2, 30, 40));
        assertEquals(1, before.size());
        assertEquals(2, window.snapshot(2_000_000_000L).size());

        List<TrafficHistorySample> mutable = new ArrayList<>(before);
        MonitoringDataSnapshot copy = new MonitoringDataSnapshot(Optional.empty(), SessionTrafficTotals.ZERO, mutable);
        mutable.clear();
        assertEquals(1, copy.history().size());
        assertThrows(UnsupportedOperationException.class, () -> copy.history().clear());
    }

    @Test
    void aBurstOfSamplesHasAHardMemoryBoundWithoutDownsamplingOrInterpolation() {
        for (int index = 1; index <= 1000; index++) {
            window.accept(available(index, (index - 1) * 1_000_000L, index * 1_000_000L, 1, 2, ADAPTER));
        }
        var retained = window.snapshot(1_000_000_000L);
        assertEquals(RollingTrafficWindow.MAX_SAMPLES, retained.size());
        assertEquals(1000, retained.getLast().sequence());
        assertEquals(1000 - RollingTrafficWindow.MAX_SAMPLES + 1, retained.getFirst().sequence());
    }

    @Test
    void duplicateOutOfOrderAndUnacceptedSnapshotsAreRejected() {
        MonitoringSnapshot first = available(5, 5, 10, 20);
        assertTrue(window.accept(first));
        assertFalse(window.accept(first));
        assertFalse(window.accept(available(4, 6, 10, 20)));
        assertFalse(window.accept(available(6, 4, 10, 20)));
        assertFalse(window.accept(available(0, 7, 10, 20)));
        assertEquals(List.of(5L), sequences(window.snapshot(7_000_000_000L)));
    }

    @Test
    void wallClockChangesDoNotChangeMonotonicOrderingOrEviction() {
        MonitoringSnapshot first = available(1, 1, 10, 20);
        MonitoringSnapshot second = available(2, 2, 10, 20);
        window.accept(first);
        window.accept(new MonitoringSnapshot(second.sequence(), Instant.EPOCH.minusSeconds(3600),
                second.observedAtNanos(), second.connectionStatus(), second.selection(), second.measurement(), "clock adjusted"));
        assertEquals(List.of(1L, 2L), sequences(window.snapshot(2_000_000_000L)));
    }

    @Test
    void monotonicSignedWraparoundDoesNotEvictRecentSamples() {
        long first = Long.MAX_VALUE - 500_000_000L;
        long second = first + 1_000_000_000L;
        window.accept(snapshot(1, first, ADAPTER, com.netpulse.model.SpeedMeasurement.unavailable(Status.BASELINE)));
        window.accept(available(2, first, second, 100, 200, ADAPTER));
        assertEquals(List.of(1L, 2L), sequences(window.snapshot(second)));
    }

    private static List<Long> sequences(List<TrafficHistorySample> samples) {
        return samples.stream().map(TrafficHistorySample::sequence).toList();
    }
}
