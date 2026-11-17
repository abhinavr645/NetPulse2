package com.netpulse.core;

import static org.junit.jupiter.api.Assertions.*;
import static com.netpulse.support.TrafficSamples.*;

import java.math.BigInteger;
import java.util.Locale;

import com.netpulse.model.MonitoringSnapshot;
import com.netpulse.model.SessionTrafficTotals;
import com.netpulse.model.SpeedMeasurement.Status;
import com.netpulse.support.AdapterFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class SessionTrafficTrackerTest {
    private final SessionTrafficTracker tracker = new SessionTrafficTracker();

    @Test
    void aNewApplicationSessionStartsAtZero() {
        assertEquals(SessionTrafficTotals.ZERO, tracker.snapshot());
        tracker.accept(available(1, 1, 10, 20));
        assertEquals(SessionTrafficTotals.ZERO, new SessionTrafficTracker().snapshot());
    }

    @ParameterizedTest
    @CsvSource({"100,0", "0,200", "123,456", "0,0"})
    void countsDownloadUploadBothAndIdleMeasurements(long download, long upload) {
        assertTrue(tracker.accept(available(1, 1, download, upload)));
        assertEquals(BigInteger.valueOf(download), tracker.snapshot().downloadedBytes());
        assertEquals(BigInteger.valueOf(upload), tracker.snapshot().uploadedBytes());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, mode = EnumSource.Mode.EXCLUDE, names = "AVAILABLE")
    void unavailableOrRebaseliningMeasurementsNeverAddBytes(Status status) {
        tracker.accept(available(1, 1, 123, 456));
        assertTrue(tracker.accept(gap(2, 2, status, ADAPTER)));
        assertEquals(new SessionTrafficTotals(BigInteger.valueOf(123), BigInteger.valueOf(456)), tracker.snapshot());
    }

    @Test
    void retainsTotalsAcrossResetsAndSwitchesWithoutReconstructingMissingTraffic() {
        tracker.accept(available(1, 1, 100, 200));
        tracker.accept(gap(2, 2, Status.COUNTER_RESET, ADAPTER));
        tracker.accept(available(3, 3, 50, 75));
        var replacement = new AdapterFixture(2).build();
        tracker.accept(gap(4, 4, Status.ADAPTER_CHANGED, replacement));
        tracker.accept(available(5, 4_000_000_000L, 5_000_000_000L, 20, 30, replacement));
        assertEquals(BigInteger.valueOf(170), tracker.snapshot().downloadedBytes());
        assertEquals(BigInteger.valueOf(305), tracker.snapshot().uploadedBytes());
    }

    @Test
    void exactByteDeltasSurviveDisplayRoundingAndDoublePrecisionLimits() {
        MonitoringSnapshot singleByte = available(1, 1, 1, 0);
        assertEquals("0.00", String.format(Locale.ROOT, "%.2f",
                singleByte.measurement().speed().orElseThrow().downloadMbps()));
        tracker.accept(singleByte);
        long beyondExactDoubleInteger = 9_007_199_254_740_993L;
        tracker.accept(available(2, 2, beyondExactDoubleInteger, 0));
        assertEquals(BigInteger.valueOf(beyondExactDoubleInteger).add(BigInteger.ONE),
                tracker.snapshot().downloadedBytes());
    }

    @Test
    void longRunningTotalsCanExceedLongMaxWithoutWrappingOrSaturating() {
        for (int second = 1; second <= 100; second++) {
            tracker.accept(available(second, second, Long.MAX_VALUE, Long.MAX_VALUE));
        }
        BigInteger expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(100));
        assertEquals(expected, tracker.snapshot().downloadedBytes());
        assertEquals(expected, tracker.snapshot().uploadedBytes());
    }

    @Test
    void duplicateAndLowerSequencesAreRejected() {
        MonitoringSnapshot sample = available(5, 5, 100, 200);
        assertTrue(tracker.accept(sample));
        assertFalse(tracker.accept(sample));
        assertFalse(tracker.accept(available(4, 6, 999, 999)));
        assertFalse(tracker.accept(available(0, 7, 999, 999)));
        assertEquals(BigInteger.valueOf(100), tracker.snapshot().downloadedBytes());
    }

    @Test
    void newerSequenceWithOlderObservationTimeIsStillStale() {
        tracker.accept(available(1, 10, 100, 200));
        assertFalse(tracker.accept(available(2, 9, 999, 999)));
        assertTrue(tracker.accept(available(3, 11, 1, 2)));
        assertEquals(BigInteger.valueOf(101), tracker.snapshot().downloadedBytes());
    }

    @Test
    void replayingAnAlreadyCountedIntervalUnderANewSequenceCannotDoubleCountIt() {
        MonitoringSnapshot first = available(1, 1, 100, 200);
        tracker.accept(first);
        assertFalse(tracker.accept(snapshot(2, 2_000_000_000L, ADAPTER, first.measurement())));
        assertTrue(tracker.accept(available(3, 3, 1, 2)));
        assertEquals(BigInteger.valueOf(101), tracker.snapshot().downloadedBytes());
        assertEquals(BigInteger.valueOf(202), tracker.snapshot().uploadedBytes());
    }

    @Test
    void previousImmutableTotalsDoNotChangeAfterFurtherMeasurements() {
        tracker.accept(available(1, 1, 1, 2));
        SessionTrafficTotals previous = tracker.snapshot();
        tracker.accept(available(2, 2, 10, 20));
        assertEquals(BigInteger.ONE, previous.downloadedBytes());
        assertEquals(BigInteger.valueOf(11), tracker.snapshot().downloadedBytes());
    }
}
