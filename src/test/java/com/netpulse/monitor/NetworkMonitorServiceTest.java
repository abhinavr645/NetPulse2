package com.netpulse.monitor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.netpulse.model.AdapterInfo;
import com.netpulse.model.AdapterSample;
import com.netpulse.model.MonitoringSnapshot;
import com.netpulse.model.MonitoringDataSnapshot;
import com.netpulse.model.SessionTrafficTotals;
import com.netpulse.core.RollingTrafficWindow;
import com.netpulse.model.MonitoringSnapshot.ConnectionStatus;
import com.netpulse.model.SpeedMeasurement.Status;
import com.netpulse.support.AdapterFixture;
import org.junit.jupiter.api.Test;

class NetworkMonitorServiceTest {
    private static final Clock WALL_CLOCK = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void lifecycleSchedulesOneFixedDelayTaskAndClosesIdempotently() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger reads = new AtomicInteger();
        try (NetworkMonitorService service = service(() -> { reads.incrementAndGet(); return List.of(); },
                new ArrayList<>(), scheduler, new AtomicLong())) {
            assertFalse(service.isRunning());
            service.start();
            service.start();
            assertTrue(service.isRunning());
            assertEquals(1, scheduler.schedules);
            assertEquals(0, scheduler.initialDelayNanos);
            assertEquals(1_000_000_000L, scheduler.delayNanos);
            scheduler.fire();
            scheduler.fire();
            assertEquals(2, reads.get());
            service.stop();
            service.stop();
            scheduler.fire();
            assertEquals(2, reads.get());
            assertTrue(scheduler.isShutdown());
            assertEquals(Status.STOPPED, service.latestSnapshot().measurement().status());
            assertThrows(IllegalStateException.class, service::start);
        }
    }

    @Test
    void stoppingBeforeStartClosesTheExecutor() {
        ManualScheduler scheduler = new ManualScheduler();
        NetworkMonitorService service = service(List::of, new ArrayList<>(), scheduler, new AtomicLong());
        service.stop();
        assertTrue(scheduler.isShutdown());
        assertThrows(IllegalStateException.class, service::start);
    }

    @Test
    void failedReadsDiscardSpeedsAndRequireANewBaseline() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AtomicInteger cycle = new AtomicInteger();
        AdapterFixture adapter = new AdapterFixture(1);
        List<MonitoringSnapshot> events = new ArrayList<>();
        NetworkStatsProvider provider = () -> {
            int current = cycle.getAndIncrement();
            if (current == 2) { throw new IOException("simulated read failure"); }
            return List.of(adapter.reading(current * 1_000_000L, current * 500_000L, nanos.get()));
        };
        try (NetworkMonitorService service = service(provider, events, scheduler, nanos)) {
            service.start();
            fire(scheduler, nanos, 0);
            fire(scheduler, nanos, 1);
            assertEquals(8, service.latestSnapshot().measurement().speed().orElseThrow().downloadMbps());
            fire(scheduler, nanos, 2);
            assertEquals(Status.READ_FAILED, service.latestSnapshot().measurement().status());
            assertTrue(service.latestSnapshot().measurement().speed().isEmpty());
            assertEquals(ConnectionStatus.UNKNOWN, service.latestSnapshot().connectionStatus());
            fire(scheduler, nanos, 3);
            assertEquals(Status.BASELINE, service.latestSnapshot().measurement().status());
            fire(scheduler, nanos, 4);
            assertEquals(Status.AVAILABLE, service.latestSnapshot().measurement().status());
            assertEquals(BigInteger.valueOf(2_000_000), service.sessionTotals().downloadedBytes());
            assertEquals(5, service.trafficHistory().size());
            assertEquals(Status.READ_FAILED, service.trafficHistory().get(2).measurement().status());
            assertEquals(5, events.size());
        }
    }

    @Test
    void aFailedSelectedAdapterReadDoesNotReuseOldCountersOrSwitchUnnecessarily() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AtomicReference<AdapterSample> sample = new AtomicReference<>();
        AdapterFixture adapter = new AdapterFixture(1);
        AdapterFixture other = new AdapterFixture(2);
        sample.set(adapter.reading(0, 0, 0));
        try (NetworkMonitorService service = service(() -> List.of(sample.get(), other.reading(0, 0, nanos.get())),
                new ArrayList<>(), scheduler, nanos)) {
            service.start();
            scheduler.fire();
            sample.set(AdapterSample.unavailable(adapter.build(), "counter refresh failed"));
            fire(scheduler, nanos, 1);
            MonitoringSnapshot failed = service.latestSnapshot();
            assertEquals(adapter.build().id(), failed.selection().orElseThrow().adapter().id());
            assertEquals(ConnectionStatus.CONNECTED, failed.connectionStatus());
            assertEquals(Status.READ_FAILED, failed.measurement().status());
            assertTrue(failed.measurement().speed().isEmpty());
        }
    }

    @Test
    void disconnectionAndReconnectionRebaselineEvenForTheSameAdapter() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AdapterFixture adapter = new AdapterFixture(1);
        try (NetworkMonitorService service = service(
                () -> List.of(adapter.reading(nanos.get() / 1000, 0, nanos.get())),
                new ArrayList<>(), scheduler, nanos)) {
            service.start();
            fire(scheduler, nanos, 0);
            fire(scheduler, nanos, 1);
            adapter.link = AdapterInfo.LinkStatus.DOWN;
            fire(scheduler, nanos, 2);
            assertEquals(ConnectionStatus.DISCONNECTED, service.latestSnapshot().connectionStatus());
            assertEquals(Status.NO_ADAPTER, service.latestSnapshot().measurement().status());
            assertTrue(service.latestSnapshot().measurement().speed().isEmpty());
            adapter.link = AdapterInfo.LinkStatus.UP;
            fire(scheduler, nanos, 3);
            assertEquals(Status.BASELINE, service.latestSnapshot().measurement().status());
            fire(scheduler, nanos, 4);
            assertEquals(Status.AVAILABLE, service.latestSnapshot().measurement().status());
            assertEquals(BigInteger.valueOf(2_000_000), service.sessionTotals().downloadedBytes());
            assertEquals(5, service.trafficHistory().size());
            assertEquals(Status.NO_ADAPTER, service.trafficHistory().get(2).measurement().status());
        }
    }

    @Test
    void switchingAdaptersPublishesANewBaselineAndItsIdentity() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AtomicReference<AdapterFixture> active = new AtomicReference<>(new AdapterFixture(1));
        try (NetworkMonitorService service = service(
                () -> List.of(active.get().reading(nanos.get(), nanos.get(), nanos.get())),
                new ArrayList<>(), scheduler, nanos)) {
            service.start();
            fire(scheduler, nanos, 0);
            active.set(new AdapterFixture(2));
            fire(scheduler, nanos, 1);
            assertEquals(Status.ADAPTER_CHANGED, service.latestSnapshot().measurement().status());
            assertEquals(2, service.latestSnapshot().selection().orElseThrow().adapter().id().index());
        }
    }

    @Test
    void staleSnapshotsNeverExposeAnOldSpeedAsCurrent() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AdapterFixture adapter = new AdapterFixture(1);
        try (NetworkMonitorService service = service(
                () -> List.of(adapter.reading(nanos.get(), 0, nanos.get())),
                new ArrayList<>(), scheduler, nanos)) {
            service.start();
            fire(scheduler, nanos, 0);
            fire(scheduler, nanos, 1);
            nanos.set(4_000_000_001L);
            assertEquals(Status.STALE, service.latestSnapshot().measurement().status());
            assertTrue(service.latestSnapshot().measurement().speed().isEmpty());
            assertEquals(ConnectionStatus.UNKNOWN, service.latestSnapshot().connectionStatus());
            scheduler.fire();
            assertEquals(Status.SAMPLING_GAP, service.latestSnapshot().measurement().status());
        }
    }

    @Test
    void countersThatWereAlreadyStaleWhenTheProviderReturnedAreUnavailable() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AtomicLong counterTimestamp = new AtomicLong();
        AdapterFixture adapter = new AdapterFixture(1);
        try (NetworkMonitorService service = service(
                () -> List.of(adapter.reading(nanos.get(), 0, counterTimestamp.get())),
                new ArrayList<>(), scheduler, nanos)) {
            service.start();
            fire(scheduler, nanos, 0);
            counterTimestamp.set(1_000_000_000L);
            fire(scheduler, nanos, 5);
            assertEquals(Status.STALE, service.latestSnapshot().measurement().status());
            assertTrue(service.latestSnapshot().measurement().speed().isEmpty());
            assertEquals(ConnectionStatus.UNKNOWN, service.latestSnapshot().connectionStatus());
            counterTimestamp.set(6_000_000_000L);
            fire(scheduler, nanos, 6);
            assertEquals(Status.BASELINE, service.latestSnapshot().measurement().status());
        }
    }

    @Test
    void aListenerExceptionCannotTerminateSampling() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger callbacks = new AtomicInteger();
        try (NetworkMonitorService service = new NetworkMonitorService(List::of, snapshot -> {
            callbacks.incrementAndGet();
            throw new IllegalStateException("simulated listener failure");
        }, scheduler, Duration.ofSeconds(1), () -> 0L, WALL_CLOCK)) {
            service.start();
            scheduler.fire();
            scheduler.fire();
            assertEquals(2, callbacks.get());
            assertTrue(service.isRunning());
        }
    }

    @Test
    void stoppingDuringAnUninterruptibleReadIsNonblockingAndDiscardsTheResult() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<MonitoringSnapshot> events = new ArrayList<>();
        NetworkMonitorService service = service(() -> {
            entered.countDown();
            await(release);
            return List.of(new AdapterFixture(1).reading(0, 0, 0));
        }, events, scheduler, new AtomicLong());
        service.start();
        Thread worker = Thread.ofPlatform().daemon().start(scheduler::fire);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), service::stop);
            release.countDown();
            worker.join(2000);
            assertFalse(worker.isAlive());
            assertTrue(events.isEmpty());
            assertEquals(Status.STOPPED, service.latestSnapshot().measurement().status());
        } finally {
            release.countDown();
            service.stop();
            worker.join(2000);
        }
    }

    @Test
    void realSchedulerRunsPeriodicallyWithoutOverlapOrCatchUpBursts() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "synthetic-monitor-test");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch twicePublished = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicLong firstReturnedAt = new AtomicLong();
        AtomicLong secondStartedAt = new AtomicLong();
        AtomicReference<String> readThread = new AtomicReference<>();
        NetworkStatsProvider provider = () -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            int number = calls.incrementAndGet();
            try {
                readThread.set(Thread.currentThread().getName());
                if (number == 1) {
                    entered.countDown();
                    await(release);
                    firstReturnedAt.set(System.nanoTime());
                } else if (number == 2) {
                    secondStartedAt.set(System.nanoTime());
                }
                return List.of(new AdapterFixture(1).reading(number * 1000, 0, System.nanoTime()));
            } finally {
                inFlight.decrementAndGet();
            }
        };
        try (NetworkMonitorService service = new NetworkMonitorService(provider, snapshot -> twicePublished.countDown(),
                executor, Duration.ofMillis(30), System::nanoTime, WALL_CLOCK)) {
            service.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            release.countDown();
            assertTrue(twicePublished.await(3, TimeUnit.SECONDS));
            assertEquals(1, maxInFlight.get());
            assertTrue(secondStartedAt.get() - firstReturnedAt.get() >= 30_000_000L);
            assertEquals("synthetic-monitor-test", readThread.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void publishesCoherentTotalsAndHistoryExactlyOnceBeforeNotifyingListeners() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AdapterFixture adapter = new AdapterFixture(1);
        AtomicReference<NetworkMonitorService> reference = new AtomicReference<>();
        List<MonitoringDataSnapshot> published = new ArrayList<>();
        try (NetworkMonitorService service = new NetworkMonitorService(
                () -> List.of(adapter.reading(nanos.get() == 0 ? 100 : 201,
                        nanos.get() == 0 ? 200 : 252, nanos.get())),
                snapshot -> {
                    var data = reference.get().dataSnapshot();
                    assertEquals(snapshot, data.latestAccepted().orElseThrow());
                    assertEquals(snapshot.sequence(), data.history().getLast().sequence());
                    published.add(data);
                }, scheduler, Duration.ofSeconds(1), nanos::get, WALL_CLOCK)) {
            reference.set(service);
            assertTrue(service.dataSnapshot().latestAccepted().isEmpty());
            assertTrue(service.trafficHistory().isEmpty());
            assertEquals(SessionTrafficTotals.ZERO, service.sessionTotals());
            service.start();
            for (int second = 0; second <= 2; second++) {
                fire(scheduler, nanos, second);
            }
            for (int read = 0; read < 100; read++) {
                service.latestSnapshot();
                service.dataSnapshot();
                service.trafficHistory();
                service.sessionTotals();
            }
            assertEquals(3, published.size());
            assertEquals(List.of(1L, 2L, 3L), published.stream()
                    .map(data -> data.latestAccepted().orElseThrow().sequence()).toList());
            assertEquals(BigInteger.valueOf(101), service.sessionTotals().downloadedBytes());
            assertEquals(BigInteger.valueOf(52), service.sessionTotals().uploadedBytes());
            assertEquals(3, service.trafficHistory().size());
            assertEquals(0, service.trafficHistory().getLast().measurement().speed().orElseThrow().downloadMbps());
            assertEquals(1, published.getFirst().history().size());
            assertThrows(UnsupportedOperationException.class, () -> service.trafficHistory().clear());
        }
    }

    @Test
    void resetsSwitchesAndFailuresCreateGapsWithoutCountingMissedTraffic() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AtomicInteger cycle = new AtomicInteger();
        AdapterFixture first = new AdapterFixture(1);
        AdapterFixture second = new AdapterFixture(2);
        NetworkStatsProvider provider = () -> {
            AdapterSample reading = switch (cycle.getAndIncrement()) {
                case 0 -> first.reading(100, 200, nanos.get());
                case 1, 2 -> first.reading(201, 252, nanos.get());
                case 3 -> first.reading(5, 6, nanos.get());
                case 4 -> first.reading(6, 8, nanos.get());
                case 5 -> second.reading(1_000_000, 2_000_000, nanos.get());
                case 6 -> second.reading(1_000_007, 2_000_009, nanos.get());
                case 7 -> throw new IOException("synthetic read failure");
                case 8 -> second.reading(1_000_100, 2_000_100, nanos.get());
                case 9 -> second.reading(1_000_110, 2_000_111, nanos.get());
                default -> throw new AssertionError("Unexpected extra poll");
            };
            return List.of(reading);
        };
        try (NetworkMonitorService service = service(provider, new ArrayList<>(), scheduler, nanos)) {
            service.start();
            for (int secondNumber = 0; secondNumber < 10; secondNumber++) {
                fire(scheduler, nanos, secondNumber);
            }
            MonitoringDataSnapshot data = service.dataSnapshot();
            assertEquals(BigInteger.valueOf(119), data.sessionTotals().downloadedBytes());
            assertEquals(BigInteger.valueOf(74), data.sessionTotals().uploadedBytes());
            assertEquals(10, data.latestAccepted().orElseThrow().sequence());
            assertEquals(10, data.history().size());
            assertEquals(Status.COUNTER_RESET, data.history().get(3).measurement().status());
            assertEquals(Status.ADAPTER_CHANGED, data.history().get(5).measurement().status());
            assertTrue(data.history().get(5).adapterBoundary());
            assertEquals(Status.READ_FAILED, data.history().get(7).measurement().status());
            assertEquals(Status.BASELINE, data.history().get(8).measurement().status());
        }
    }

    @Test
    void replayedProviderCountersBecomeAGapInsteadOfAnotherByteDelta() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AdapterFixture adapter = new AdapterFixture(1);
        try (NetworkMonitorService service = service(() -> List.of(
                adapter.reading(nanos.get() == 0 ? 0 : 100, 0, Math.min(nanos.get(), 1_000_000_000L))),
                new ArrayList<>(), scheduler, nanos)) {
            service.start();
            fire(scheduler, nanos, 0);
            fire(scheduler, nanos, 1);
            fire(scheduler, nanos, 2);
            assertEquals(BigInteger.valueOf(100), service.sessionTotals().downloadedBytes());
            assertEquals(3, service.trafficHistory().size());
            assertEquals(Status.INVALID_TIME, service.trafficHistory().getLast().measurement().status());
            assertTrue(service.trafficHistory().getLast().measurement().trafficDelta().isEmpty());
        }
    }

    @Test
    void cancelledLateReadsCannotChangeAlreadyAcceptedTrafficData() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AtomicInteger cycles = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AdapterFixture adapter = new AdapterFixture(1);
        NetworkMonitorService service = service(() -> {
            int cycle = cycles.getAndIncrement();
            if (cycle == 2) {
                entered.countDown();
                await(release);
            }
            return List.of(adapter.reading(cycle == 2 ? Long.MAX_VALUE : cycle * 100L, 0, nanos.get()));
        }, new ArrayList<>(), scheduler, nanos);
        service.start();
        fire(scheduler, nanos, 0);
        fire(scheduler, nanos, 1);
        nanos.set(2_000_000_000L);
        MonitoringDataSnapshot before = service.dataSnapshot();
        Thread worker = Thread.ofPlatform().daemon().start(scheduler::fire);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            service.stop();
            release.countDown();
            worker.join(2000);
            assertFalse(worker.isAlive());
            assertEquals(before, service.dataSnapshot());
            assertEquals(2, service.dataSnapshot().latestAccepted().orElseThrow().sequence());
            assertEquals(BigInteger.valueOf(100), service.sessionTotals().downloadedBytes());
        } finally {
            release.countDown();
            service.stop();
            worker.join(2000);
        }
    }

    @Test
    void historyExpiresWhileStoppedWithoutResettingSessionTotalsOrReprocessingTheLastSample() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AdapterFixture adapter = new AdapterFixture(1);
        try (NetworkMonitorService service = service(
                () -> List.of(adapter.reading(nanos.get() / 10_000_000L, 0, nanos.get())),
                new ArrayList<>(), scheduler, nanos)) {
            service.start();
            fire(scheduler, nanos, 0);
            fire(scheduler, nanos, 1);
            service.stop();
            nanos.set(61_000_000_000L);
            assertEquals(1, service.trafficHistory().size());
            nanos.incrementAndGet();
            assertTrue(service.trafficHistory().isEmpty());
            assertEquals(2, service.dataSnapshot().latestAccepted().orElseThrow().sequence());
            assertEquals(BigInteger.valueOf(100), service.sessionTotals().downloadedBytes());
        }
    }

    @Test
    void concurrentReadersSeeOnlyWholeAcceptedDataSnapshots() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean();
        AdapterFixture adapter = new AdapterFixture(1);
        try (NetworkMonitorService service = service(() -> {
            int count = reads.incrementAndGet();
            return List.of(adapter.reading(count, count * 2L, nanos.get()));
        }, new ArrayList<>(), scheduler, nanos);
             var readers = Executors.newSingleThreadExecutor()) {
            service.start();
            CountDownLatch ready = new CountDownLatch(1);
            var checking = readers.submit(() -> {
                ready.countDown();
                int observations = 0;
                while (!finished.get() || observations < 1000) {
                    MonitoringDataSnapshot data = service.dataSnapshot();
                    if (data.latestAccepted().isPresent()) {
                        long sequence = data.latestAccepted().orElseThrow().sequence();
                        assertEquals(BigInteger.valueOf(sequence - 1), data.sessionTotals().downloadedBytes());
                        assertEquals(BigInteger.valueOf((sequence - 1) * 2), data.sessionTotals().uploadedBytes());
                        assertEquals(sequence, data.history().getLast().sequence());
                        assertTrue(data.history().size() <= RollingTrafficWindow.MAX_SAMPLES);
                    }
                    observations++;
                }
            });
            try {
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                for (int cycle = 0; cycle < 1000; cycle++) {
                    nanos.set(cycle * 1_000_000L);
                    scheduler.fire();
                }
            } finally {
                finished.set(true);
            }
            checking.get(5, TimeUnit.SECONDS);
            assertEquals(BigInteger.valueOf(999), service.sessionTotals().downloadedBytes());
        }
    }

    private static NetworkMonitorService service(NetworkStatsProvider provider, List<MonitoringSnapshot> events,
            ManualScheduler scheduler, AtomicLong nanos) {
        return new NetworkMonitorService(provider, events::add, scheduler,
                Duration.ofSeconds(1), nanos::get, WALL_CLOCK);
    }

    @Test
    void eightSimulatedHoursOfDisconnectsSwitchesResetsAndFailuresKeepExactBoundedData() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong nanos = new AtomicLong();
        AtomicInteger stage = new AtomicInteger();
        AdapterFixture first = new AdapterFixture(1);
        AdapterFixture second = new AdapterFixture(2);
        var chart = new com.netpulse.ui.TrafficChartData();
        try (NetworkMonitorService monitor = service(() -> switch (stage.get()) {
            case 0 -> List.of(first.reading(100, 200, nanos.get()));
            case 1, 2 -> List.of(first.reading(110, 220, nanos.get()));
            case 3 -> List.of(first.reading(5, 7, nanos.get()));
            case 4 -> List.of(first.reading(8, 11, nanos.get()));
            case 5 -> List.of(second.reading(200, 300, nanos.get()));
            case 6 -> List.of(second.reading(210, 320, nanos.get()));
            case 7 -> throw new IOException("Synthetic read failure");
            case 8 -> List.of(second.reading(500, 600, nanos.get()));
            case 9 -> List.of(second.reading(505, 607, nanos.get()));
            case 11 -> List.of(first.reading(800, 900, nanos.get()));
            case 12 -> List.of(first.reading(802, 903, nanos.get()));
            default -> List.of(); // disconnect and finish each cycle disconnected
        }, new ArrayList<>(), scheduler, nanos)) {
            monitor.start();
            // 2,100 * 14 one-second observations = 29,400 seconds (>8 hours).
            for (int cycle = 0; cycle < 2_100; cycle++) {
                for (int step = 0; step < 14; step++) {
                    stage.set(step);
                    fire(scheduler, nanos, cycle * 14L + step);
                    if (step == 2) {
                        assertEquals(0, monitor.latestSnapshot().measurement().speed().orElseThrow().downloadMbps());
                    }
                }
                var data = monitor.dataSnapshot();
                // Independent golden totals per cycle: 10+3+10+5+2 down,
                // 20+4+20+7+3 up; reset/baseline/failure/disconnect add nothing.
                assertEquals(BigInteger.valueOf((cycle + 1L) * 30), data.sessionTotals().downloadedBytes());
                assertEquals(BigInteger.valueOf((cycle + 1L) * 54), data.sessionTotals().uploadedBytes());
                assertTrue(data.history().size() <= 61);
                var frame = chart.project(data.history(), List.of(), nanos.get(), WALL_CLOCK.instant());
                assertEquals(data.history().stream().filter(sample -> sample.measurement().speed().isPresent()).count(), frame.pointCount());
                assertTrue(frame.segments().size() >= 3);
                assertTrue(frame.segments().stream().flatMap(segment -> segment.points().stream())
                        .allMatch(point -> point.secondsAgo() >= -60 && point.secondsAgo() <= 0));
            }
            assertEquals(29_400, monitor.latestSnapshot().sequence());
        }
        assertTrue(scheduler.isShutdown());
    }

    private static void fire(ManualScheduler scheduler, AtomicLong nanos, long seconds) {
        nanos.set(seconds * 1_000_000_000L);
        scheduler.fire();
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IOException("Test latch timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Test read interrupted", failure);
        }
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private Runnable action;
        private final ManualFuture future = new ManualFuture();
        private int schedules;
        private long initialDelayNanos;
        private long delayNanos;

        ManualScheduler() { super(1); }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                long delay, TimeUnit unit) {
            schedules++;
            action = command;
            initialDelayNanos = unit.toNanos(initialDelay);
            delayNanos = unit.toNanos(delay);
            return future;
        }

        void fire() {
            if (action != null && !future.isCancelled() && !isShutdown()) {
                action.run();
            }
        }
    }

    private static final class ManualFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
        ManualFuture() { super(() -> null); }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
