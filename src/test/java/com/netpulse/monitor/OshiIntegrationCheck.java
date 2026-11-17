package com.netpulse.monitor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.netpulse.model.AdapterSample;
import com.netpulse.model.MonitoringSnapshot;
import com.netpulse.model.SpeedMeasurement.Status;

/** Explicit manual Windows integration check; never included in the unit-test suite. */
public final class OshiIntegrationCheck {
    private OshiIntegrationCheck() { }

    public static void main(String[] args) throws InterruptedException {
        OshiNetworkStatsProvider actual = new OshiNetworkStatsProvider();
        AtomicReference<List<AdapterSample>> raw = new AtomicReference<>(List.of());
        List<MonitoringSnapshot> events = new CopyOnWriteArrayList<>();
        CountDownLatch observed = new CountDownLatch(5);
        NetworkStatsProvider recording = () -> {
            List<AdapterSample> samples = actual.read();
            raw.set(samples);
            if (events.isEmpty()) {
                for (AdapterSample sample : samples) {
                    var adapter = sample.adapter();
                    System.out.printf("OSHI interface=%s name=%s link=%s kind=%s medium=%s connector=%s virtual=%s vmMac=%s route=%s counters=%s%n",
                            adapter.id().name(), adapter.displayName(), adapter.linkStatus(), adapter.kind(),
                            adapter.medium(), adapter.connectorPresent(), adapter.virtual(), adapter.knownVirtualMac(),
                            adapter.defaultRoute(), sample.counters().map(Object::toString).orElse(sample.error()));
                }
            }
            return samples;
        };
        try (NetworkMonitorService monitor = new NetworkMonitorService(recording, snapshot -> {
            events.add(snapshot);
            String selected = snapshot.selection().map(selection ->
                    selection.adapter().displayName() + " / " + selection.identification()).orElse("none");
            String speed = snapshot.measurement().speed().map(value -> String.format(
                    "download=%.6f Mbps (%.6f MB/s) upload=%.6f Mbps (%.6f MB/s)",
                    value.downloadMbps(), value.downloadMBps(), value.uploadMbps(), value.uploadMBps())).orElse("unavailable");
            System.out.printf("%s link=%s adapter=%s measurement=%s %s%n", snapshot.observedAt(),
                    snapshot.connectionStatus(), selected, snapshot.measurement().status(), speed);
            observed.countDown();
        })) {
            monitor.start();
            if (!observed.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Did not receive five monitoring snapshots");
            }
        }
        if (raw.get().stream().noneMatch(sample -> sample.counters().isPresent())) {
            throw new IllegalStateException("No actual OSHI byte counters were observed");
        }
        if (events.stream().anyMatch(event -> event.measurement().status() == Status.READ_FAILED)) {
            throw new IllegalStateException("An OSHI monitoring read failed; inspect the output");
        }
        long measured = events.stream().filter(event -> event.measurement().status() == Status.AVAILABLE).count();
        System.out.printf("Observed %d snapshots, %d valid throughput measurements. Monitor closed.%n", events.size(), measured);
        if (measured == 0) {
            System.out.println("No eligible physical adapter produced throughput; raw OSHI access alone was verified.");
        }
    }
}
