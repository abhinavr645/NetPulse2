package com.netpulse.monitor;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.netpulse.core.AdapterSelector;
import com.netpulse.core.NetworkSpeedCalculator;
import com.netpulse.model.AdapterInfo;
import com.netpulse.model.AdapterSample;
import com.netpulse.model.AdapterSelection;
import com.netpulse.model.MonitoringSnapshot;
import com.netpulse.model.MonitoringSnapshot.ConnectionStatus;
import com.netpulse.model.SpeedMeasurement;
import com.netpulse.model.SpeedMeasurement.Status;

/**
 * One worker, fixed delays, no JavaFX dependency. Listener calls run on the worker
 * and must be brief. stop()/close() never wait for a native read or a listener.
 * Stopping is terminal; create a new service for a new monitoring lifecycle.
 */
public final class NetworkMonitorService implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(NetworkMonitorService.class.getName());
    private final NetworkStatsProvider provider;
    private final Consumer<MonitoringSnapshot> listener;
    private final ScheduledExecutorService executor;
    private final long intervalNanos;
    private final LongSupplier nanoClock;
    private final Clock wallClock;
    private final Object lifecycle = new Object();
    private final AdapterSelector selector = new AdapterSelector();
    private final NetworkSpeedCalculator calculator = new NetworkSpeedCalculator();
    private volatile MonitoringSnapshot latest;
    private volatile boolean running;
    private boolean closed;
    private ScheduledFuture<?> task;
    private AdapterInfo.Id selected;

    public NetworkMonitorService(NetworkStatsProvider provider) {
        this(provider, snapshot -> { });
    }

    public NetworkMonitorService(NetworkStatsProvider provider, Consumer<MonitoringSnapshot> listener) {
        this(provider, listener, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NetPulse-network-monitor");
            thread.setDaemon(true);
            return thread;
        }), Duration.ofSeconds(1), System::nanoTime, Clock.systemUTC());
    }

    // Package-private injection keeps scheduling tests deterministic without adding a framework.
    NetworkMonitorService(NetworkStatsProvider provider, Consumer<MonitoringSnapshot> listener,
            ScheduledExecutorService executor, Duration interval, LongSupplier nanoClock, Clock wallClock) {
        this.provider = Objects.requireNonNull(provider);
        this.listener = Objects.requireNonNull(listener);
        this.executor = Objects.requireNonNull(executor);
        intervalNanos = interval.toNanos();
        if (intervalNanos <= 0) {
            throw new IllegalArgumentException("Sampling interval must be positive");
        }
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.wallClock = Objects.requireNonNull(wallClock);
        latest = snapshot(ConnectionStatus.STOPPED, Optional.empty(),
                SpeedMeasurement.unavailable(Status.STOPPED), "Monitoring has not started");
    }

    public void start() {
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("A stopped monitor cannot be restarted");
            }
            if (running) {
                return;
            }
            latest = snapshot(ConnectionStatus.UNKNOWN, Optional.empty(),
                    SpeedMeasurement.unavailable(Status.BASELINE), "Waiting for the first reading");
            running = true;
            try {
                task = executor.scheduleWithFixedDelay(this::poll, 0, intervalNanos, TimeUnit.NANOSECONDS);
            } catch (RuntimeException failure) {
                running = false;
                closed = true;
                executor.shutdownNow();
                latest = snapshot(ConnectionStatus.STOPPED, Optional.empty(),
                        SpeedMeasurement.unavailable(Status.STOPPED), "Monitoring could not start");
                throw failure;
            }
        }
    }

    /** Returns an explicitly unavailable view if a blocked read outlives the freshness limit. */
    public MonitoringSnapshot latestSnapshot() {
        MonitoringSnapshot current = latest;
        if (running && nanoClock.getAsLong() - current.observedAtNanos()
                > NetworkSpeedCalculator.MAX_SAMPLING_GAP_NANOS) {
            return new MonitoringSnapshot(current.observedAt(), current.observedAtNanos(),
                    ConnectionStatus.UNKNOWN, current.selection(), SpeedMeasurement.unavailable(Status.STALE),
                    "No fresh monitoring snapshot for more than three seconds");
        }
        return current;
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            running = false;
            closed = true;
            if (task != null) {
                task.cancel(true);
            }
            executor.shutdownNow();
            latest = snapshot(ConnectionStatus.STOPPED, Optional.empty(),
                    SpeedMeasurement.unavailable(Status.STOPPED), "Monitoring stopped");
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void poll() {
        if (!running) {
            return;
        }
        MonitoringSnapshot next;
        try {
            List<AdapterSample> samples = List.copyOf(provider.read());
            Optional<AdapterSelection> selection = selector.select(
                    samples.stream().map(AdapterSample::adapter).toList(), selected);
            if (selection.isEmpty()) {
                selected = null;
                calculator.reset();
                boolean failed = samples.stream().anyMatch(sample -> !sample.error().isEmpty());
                boolean unknown = samples.stream().anyMatch(sample ->
                        sample.adapter().linkStatus() == AdapterInfo.LinkStatus.UNKNOWN);
                next = snapshot(failed || unknown ? ConnectionStatus.UNKNOWN : ConnectionStatus.DISCONNECTED,
                        Optional.empty(), SpeedMeasurement.unavailable(failed ? Status.READ_FAILED : Status.NO_ADAPTER),
                        failed ? "Interface metadata or counters could not be read" : "No eligible active physical adapter");
            } else {
                selected = selection.get().adapter().id();
                AdapterSample sample = samples.stream()
                        .filter(candidate -> candidate.adapter().id().equals(selected)).findFirst().orElseThrow();
                SpeedMeasurement measurement;
                if (sample.counters().isEmpty()) {
                    calculator.reset();
                    measurement = SpeedMeasurement.unavailable(Status.READ_FAILED);
                } else if (nanoClock.getAsLong() - sample.counters().orElseThrow().sampledAtNanos()
                        > NetworkSpeedCalculator.MAX_SAMPLING_GAP_NANOS) {
                    calculator.reset();
                    measurement = SpeedMeasurement.unavailable(Status.STALE);
                } else {
                    measurement = calculator.calculate(selected, sample.counters().orElseThrow());
                }
                next = snapshot(measurement.status() == Status.STALE ? ConnectionStatus.UNKNOWN
                                : ConnectionStatus.CONNECTED, selection, measurement,
                        sample.error().isEmpty() ? measurement.status().name() : sample.error());
            }
        } catch (IOException | RuntimeException | LinkageError failure) {
            calculator.reset();
            next = snapshot(ConnectionStatus.UNKNOWN, Optional.empty(),
                    SpeedMeasurement.unavailable(Status.READ_FAILED), "Network statistics read failed: " + failure);
            LOG.log(System.Logger.Level.DEBUG, "Network sampling failed", failure);
        }

        synchronized (lifecycle) {
            if (!running) {
                return; // In-flight native reads cannot publish into a stopped lifecycle.
            }
            latest = next;
        }
        // No user callback runs while holding the lifecycle lock. An already
        // entered callback may finish during close; callers can query latestSnapshot().
        if (running) {
            try {
                listener.accept(next);
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Monitoring listener failed", failure);
            }
        }
    }

    private MonitoringSnapshot snapshot(ConnectionStatus status, Optional<AdapterSelection> selection,
            SpeedMeasurement measurement, String detail) {
        return new MonitoringSnapshot(wallClock.instant(), nanoClock.getAsLong(), status, selection, measurement, detail);
    }
}
