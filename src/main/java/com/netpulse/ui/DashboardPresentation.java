package com.netpulse.ui;

import java.util.Optional;

import com.netpulse.model.AdapterSelection.Identification;
import com.netpulse.model.MonitoringSnapshot;
import com.netpulse.model.NetworkSpeed;
import com.netpulse.model.SpeedTestResult;
import com.netpulse.model.SpeedTestState;
import com.netpulse.model.SpeedTestState.Phase;
import com.netpulse.util.SpeedFormatter;

/** Small JavaFX-free presentation state, confined to its UI owner. */
public final class DashboardPresentation {
    private SpeedTestState test = SpeedTestState.idle();
    private Optional<SpeedTestResult> lastSuccess = Optional.empty();
    private boolean starting;
    private boolean cancelling;

    public record MonitorView(String adapter, String interfaceName, String mac,
            String link, String identification, String status, Optional<NetworkSpeed> speed) { }

    public static MonitorView monitor(MonitoringSnapshot snapshot) {
        String link = switch (snapshot.connectionStatus()) {
            case CONNECTED -> "Link connected";
            case DISCONNECTED -> "Link disconnected";
            case UNKNOWN -> "Link unknown";
            case STOPPED -> "Monitoring stopped";
        };
        String status = switch (snapshot.measurement().status()) {
            case AVAILABLE -> "Live adapter traffic \u00b7 updated every second";
            case BASELINE -> "Measuring \u00b7 waiting for the next sample";
            case ADAPTER_CHANGED -> "Adapter changed \u00b7 establishing a new baseline";
            case COUNTER_RESET -> "Counters reset \u00b7 establishing a new baseline";
            case NO_ADAPTER -> "No eligible physical Wi-Fi or Ethernet adapter";
            case STALE -> "Readings unavailable \u00b7 monitoring update is overdue";
            case STOPPED -> "Monitoring stopped";
            case READ_FAILED -> "Readings unavailable \u00b7 adapter counters could not be read";
            case SAMPLING_GAP -> "Readings unavailable \u00b7 sampling gap";
            case INVALID_COUNTERS, INVALID_TIME -> "Readings unavailable \u00b7 invalid counter sample";
        };
        return snapshot.selection().map(selection -> {
            var adapter = selection.adapter();
            return new MonitorView(adapter.displayName(), adapter.id().name(),
                    adapter.id().hasUsableMac() ? adapter.id().macAddress() : SpeedFormatter.UNAVAILABLE,
                    link, selection.identification() == Identification.LIKELY_PHYSICAL
                            ? "Likely physical" : "Physical identification uncertain",
                    status, snapshot.measurement().speed());
        }).orElseGet(() -> new MonitorView("No eligible adapter", SpeedFormatter.UNAVAILABLE,
                SpeedFormatter.UNAVAILABLE, link, SpeedFormatter.UNAVAILABLE, status, Optional.empty()));
    }

    public void requested() { starting = true; cancelling = false; }
    public void cancellationRequested() { cancelling = true; }

    /** Reject old test IDs, phase regressions and late callbacks after a terminal state. */
    public void observe(SpeedTestState update) {
        if (update.testId() < test.testId()) { return; }
        if (update.testId() == test.testId()
                && (!test.active() && test.phase() != Phase.IDLE || update.phase().ordinal() < test.phase().ordinal())) {
            return;
        }
        if (update.testId() > test.testId()) { starting = false; }
        test = update;
        if (!test.active() && !starting) { cancelling = false; }
        test.result().ifPresent(result -> lastSuccess = Optional.of(result));
    }

    /** A newly selected executable has a fresh service/test ID namespace. Results are retained. */
    public void executableChanged() {
        test = SpeedTestState.idle();
        starting = false;
        cancelling = false;
    }

    public boolean busy(boolean serviceRunning) { return starting || test.active() || serviceRunning; }
    public boolean canCancel(boolean serviceRunning) { return busy(serviceRunning) && !cancelling; }
    public SpeedTestState state() { return test; }
    public Optional<SpeedTestResult> lastSuccess() { return lastSuccess; }
    public String status() {
        if (cancelling) { return "Cancelling \u00b7 waiting for the CLI to stop\u2026"; }
        if (starting) { return "Preparing Ookla speed test\u2026"; }
        return test.error().map(error -> switch (error) {
            case NOT_FOUND -> "Ookla CLI not found. Choose speedtest.exe or add it to PATH.";
            case TERMS_REQUIRED -> "Ookla terms require your review. Run the official CLI yourself to review and accept them, then retry.";
            default -> test.message();
        }).orElse(test.message());
    }
}
