package com.netpulse.speedtest;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import com.netpulse.model.SpeedTestPeriod;
import com.netpulse.model.SpeedTestState.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SpeedTestPeriodTrackerTest {
    private final SpeedTestPeriodTracker tracker = new SpeedTestPeriodTracker();
    private static final long SECOND = 1_000_000_000L;

    @ParameterizedTest @EnumSource(value = Phase.class, names = {"COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED"})
    void closesOnEveryTerminalOutcomeAndPreservesActualProcessEnd(Phase phase) {
        long token = tracker.reserve(0);
        assertTrue(tracker.started(token, 1, SECOND, Instant.EPOCH));
        assertTrue(tracker.snapshot(2 * SECOND).getFirst().active());
        assertTrue(tracker.ended(token, 3 * SECOND, Instant.EPOCH.minusSeconds(100), true));
        assertTrue(tracker.finished(token, phase, 4 * SECOND, Instant.EPOCH));
        var period = tracker.snapshot(4 * SECOND).getFirst();
        assertFalse(period.active());
        assertEquals(3 * SECOND, period.end().orElseThrow().atNanos());
        assertEquals(Instant.EPOCH.minusSeconds(100), period.end().orElseThrow().at());
        assertTrue(period.end().orElseThrow().terminationConfirmed());
        assertEquals(phase, period.outcome().orElseThrow());
    }

    @Test void startupFailureHasNoIntervalAndRejectsLateStart() {
        long token = tracker.reserve(0);
        assertFalse(tracker.finished(token, Phase.FAILED, SECOND, Instant.EPOCH));
        assertTrue(tracker.snapshot(SECOND).isEmpty());
        assertFalse(tracker.started(token, 1, 2 * SECOND, Instant.EPOCH));
    }

    @Test void tokensRejectObsoleteStartsEndsAndOutcomesAcrossServiceTestIdRestarts() {
        long old = tracker.reserve(0);
        tracker.started(old, 1, 0, Instant.EPOCH);
        tracker.ended(old, SECOND, Instant.EPOCH, true);
        tracker.finished(old, Phase.COMPLETED, SECOND, Instant.EPOCH);
        long current = tracker.reserve(2 * SECOND);
        assertFalse(tracker.started(old, 2, 2 * SECOND, Instant.EPOCH));
        assertTrue(tracker.started(current, 1, 2 * SECOND, Instant.EPOCH));
        assertFalse(tracker.ended(old, 3 * SECOND, Instant.EPOCH, true));
        assertFalse(tracker.finished(old, Phase.CANCELLED, 3 * SECOND, Instant.EPOCH));
        assertTrue(tracker.snapshot(3 * SECOND).getLast().active());
        assertEquals(Phase.COMPLETED, tracker.snapshot(3 * SECOND).getFirst().outcome().orElseThrow());
    }

    @Test void duplicateAndBackwardsEventsCannotRewriteAnInterval() {
        long token = tracker.reserve(0);
        tracker.started(token, 1, SECOND, Instant.EPOCH);
        assertFalse(tracker.started(token, 1, 2 * SECOND, Instant.EPOCH));
        assertFalse(tracker.ended(token, 0, Instant.EPOCH, true));
        tracker.ended(token, 3 * SECOND, Instant.EPOCH, true);
        assertFalse(tracker.ended(token, 4 * SECOND, Instant.EPOCH, true));
        tracker.finished(token, Phase.COMPLETED, 4 * SECOND, Instant.EPOCH);
        assertFalse(tracker.finished(token, Phase.FAILED, 5 * SECOND, Instant.EPOCH));
        assertEquals(3 * SECOND, tracker.snapshot(5 * SECOND).getFirst().end().orElseThrow().atNanos());
    }

    @Test void activePeriodIsRetainedAndCannotBeOverwrittenByANewReservation() {
        long token = tracker.reserve(0);
        tracker.started(token, 1, 0, Instant.EPOCH);
        assertTrue(tracker.snapshot(120 * SECOND).getFirst().active());
        assertThrows(IllegalStateException.class, () -> tracker.reserve(120 * SECOND));
    }

    @Test void endBasedExpirationAndImmutableSnapshotsHaveAHardMemoryBound() {
        for (int i = 0; i < 1000; i++) {
            long token = tracker.reserve(i);
            tracker.started(token, i + 1, i, Instant.EPOCH);
            tracker.ended(token, i + 1, Instant.EPOCH, true);
            tracker.finished(token, Phase.COMPLETED, i + 1, Instant.EPOCH);
        }
        var snapshot = tracker.snapshot(1000);
        assertEquals(SpeedTestPeriodTracker.MAX_PERIODS, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertEquals(1, tracker.snapshot(60 * SECOND + 1000).size());
        assertTrue(tracker.snapshot(60 * SECOND + 1001).isEmpty());
    }

    @Test void terminalFallbackClosesTrackingWithoutInventingConfirmedTermination() {
        long token = tracker.reserve(0);
        tracker.started(token, 1, 0, Instant.EPOCH);
        tracker.finished(token, Phase.FAILED, SECOND, Instant.EPOCH);
        var period = tracker.snapshot(SECOND).getFirst();
        assertFalse(period.active());
        assertFalse(period.end().orElseThrow().terminationConfirmed());
    }
}
