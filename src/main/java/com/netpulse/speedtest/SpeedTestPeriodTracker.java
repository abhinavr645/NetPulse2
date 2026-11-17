package com.netpulse.speedtest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.netpulse.core.RollingTrafficWindow;
import com.netpulse.model.SpeedTestPeriod;
import com.netpulse.model.SpeedTestState.Phase;

/** Short synchronized publications only; never holds a lock during process work.
 * Tokens are shared across replacement services, so late old callbacks cannot
 * alter a newer test and visible completed periods survive executable changes.
 */
public final class SpeedTestPeriodTracker {
    public static final int MAX_PERIODS = 128;
    private volatile List<SpeedTestPeriod> published = List.of();
    private long latestToken;
    private boolean finalized;

    public synchronized long reserve(long nowNanos) {
        published = recent(published, nowNanos);
        if (published.stream().anyMatch(SpeedTestPeriod::active)) {
            throw new IllegalStateException("A benchmark process is already being tracked");
        }
        finalized = false;
        return ++latestToken;
    }

    public synchronized boolean started(long token, long testId, long nanos, Instant wallTime) {
        if (token != latestToken || token <= 0 || finalized
                || published.stream().anyMatch(period -> period.id() == token)) { return false; }
        var next = new ArrayList<>(recent(published, nanos));
        next.add(new SpeedTestPeriod(token, testId, nanos, wallTime, Optional.empty(), Optional.empty()));
        if (next.size() > MAX_PERIODS) { next.removeFirst(); }
        published = List.copyOf(next);
        return true;
    }

    public synchronized boolean ended(long token, long nanos, Instant wallTime, boolean confirmed) {
        if (token != latestToken) { return false; }
        return replace(token, period -> period.end().isPresent() || nanos - period.startedAtNanos() < 0 ? period
                : new SpeedTestPeriod(period.id(), period.testId(), period.startedAtNanos(), period.startedAt(),
                        Optional.of(new SpeedTestPeriod.End(nanos, wallTime, confirmed)), period.outcome()));
    }

    public synchronized boolean finished(long token, Phase phase, long nanos, Instant wallTime) {
        if (token != latestToken) { return false; }
        if (phase == Phase.IDLE || phase == Phase.PREPARING || phase == Phase.RUNNING || phase == Phase.PROCESSING) {
            throw new IllegalArgumentException("Terminal phase required");
        }
        finalized = true;
        return replace(token, period -> {
            if (period.outcome().isPresent() || nanos - period.startedAtNanos() < 0) { return period; }
            // A last-resort terminal close explicitly marks unconfirmed process termination.
            var end = period.end().orElseGet(() -> new SpeedTestPeriod.End(nanos, wallTime, false));
            return new SpeedTestPeriod(period.id(), period.testId(), period.startedAtNanos(), period.startedAt(),
                    Optional.of(end), Optional.of(phase));
        });
    }

    public List<SpeedTestPeriod> snapshot(long nowNanos) { return recent(published, nowNanos); }

    private boolean replace(long token, java.util.function.UnaryOperator<SpeedTestPeriod> update) {
        var next = new ArrayList<>(published);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id() == token) {
                var old = next.get(i);
                var replacement = update.apply(old);
                if (replacement == old) { return false; }
                next.set(i, replacement);
                published = List.copyOf(next);
                return true;
            }
        }
        return false; // No process successfully started, so no interval is invented.
    }

    private static List<SpeedTestPeriod> recent(List<SpeedTestPeriod> periods, long nowNanos) {
        return periods.stream().filter(period -> period.active()
                || nowNanos - period.end().orElseThrow().atNanos() <= RollingTrafficWindow.WINDOW_NANOS).toList();
    }
}
