package com.netpulse.core;

import java.math.BigInteger;

import com.netpulse.model.MonitoringSnapshot;
import com.netpulse.model.SessionTrafficTotals;
import com.netpulse.model.SpeedMeasurement.Status;
import com.netpulse.model.TrafficDelta;

/** Single-writer accumulator with immutable, thread-safe reads. One instance per session. */
public final class SessionTrafficTracker {
    private long lastSequence;
    private long lastObservedAtNanos;
    private boolean countedInterval;
    private long lastIntervalEnd;
    private volatile SessionTrafficTotals totals = SessionTrafficTotals.ZERO;

    /** Returns false for duplicate, out-of-order, or overlapping replayed measurements. */
    public boolean accept(MonitoringSnapshot snapshot) {
        if (snapshot.sequence() <= lastSequence || (lastSequence > 0
                && snapshot.observedAtNanos() - lastObservedAtNanos < 0)) {
            return false;
        }
        if (snapshot.measurement().status() == Status.AVAILABLE) {
            TrafficDelta delta = snapshot.measurement().trafficDelta().orElseThrow();
            if (countedInterval && (delta.startedAtNanos() - lastIntervalEnd < 0
                    || delta.endedAtNanos() - lastIntervalEnd <= 0)) {
                return false;
            }
            totals = new SessionTrafficTotals(
                    totals.downloadedBytes().add(BigInteger.valueOf(delta.downloadedBytes())),
                    totals.uploadedBytes().add(BigInteger.valueOf(delta.uploadedBytes())));
            countedInterval = true;
            lastIntervalEnd = delta.endedAtNanos();
        }
        lastSequence = snapshot.sequence();
        lastObservedAtNanos = snapshot.observedAtNanos();
        return true;
    }

    public SessionTrafficTotals snapshot() {
        return totals;
    }
}
