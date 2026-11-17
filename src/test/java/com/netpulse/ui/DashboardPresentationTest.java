package com.netpulse.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;

import com.netpulse.model.SpeedMeasurement.Status;
import com.netpulse.model.SpeedTestResult;
import com.netpulse.model.SpeedTestState;
import com.netpulse.model.SpeedTestState.ErrorCode;
import com.netpulse.model.SpeedTestState.Phase;
import com.netpulse.support.AdapterFixture;
import com.netpulse.support.TrafficSamples;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DashboardPresentationTest {
    @Test void validIdleRemainsPresentAndAdapterChangeClearsOldSpeedAndMetadata() {
        var idle = DashboardPresentation.monitor(TrafficSamples.available(1, 1, 0, 0));
        assertEquals(0, idle.speed().orElseThrow().downloadMbps());
        var changed = new AdapterFixture(2).build();
        var view = DashboardPresentation.monitor(TrafficSamples.gap(2, 2, Status.ADAPTER_CHANGED, changed));
        assertTrue(view.speed().isEmpty());
        assertEquals(changed.displayName(), view.adapter());
        assertEquals(changed.id().name(), view.interfaceName());
        assertEquals(changed.id().macAddress(), view.mac());
        assertTrue(view.status().contains("Adapter changed"));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = "AVAILABLE", mode = EnumSource.Mode.EXCLUDE)
    void everyUnavailableStatusHasNoStaleSpeed(Status status) {
        var view = DashboardPresentation.monitor(TrafficSamples.gap(2, 2, status, TrafficSamples.ADAPTER));
        assertTrue(view.speed().isEmpty());
        assertFalse(view.status().isBlank());
    }

    @Test void disconnectedViewClearsAdapterAndDoesNotClaimInternetConnectivity() {
        var view = DashboardPresentation.monitor(TrafficSamples.gap(3, 3, Status.NO_ADAPTER, null));
        assertEquals("No eligible adapter", view.adapter());
        assertEquals("—", view.mac());
        assertEquals("—", view.interfaceName());
        assertEquals("Link disconnected", view.link());
    }

    @Test void absentMacAndAmbiguousPhysicalIdentityAreNotInvented() {
        var fixture = new AdapterFixture(3);
        fixture.mac = "";
        var original = TrafficSamples.gap(1, 1, Status.BASELINE, fixture.build());
        var snapshot = new com.netpulse.model.MonitoringSnapshot(original.sequence(), original.observedAt(),
                original.observedAtNanos(), original.connectionStatus(), Optional.of(new com.netpulse.model.AdapterSelection(
                        fixture.build(), com.netpulse.model.AdapterSelection.Identification.AMBIGUOUS)), original.measurement(), "");
        var view = DashboardPresentation.monitor(snapshot);
        assertEquals("—", view.mac());
        assertEquals("Physical identification uncertain", view.identification());
    }

    @Test void reservationDisablesStartBeforeTheWorkerPublishesAndCancellationWaitsForCleanup() {
        var ui = new DashboardPresentation();
        assertFalse(ui.busy(false));
        assertFalse(ui.canCancel(false));
        ui.requested();
        ui.observe(SpeedTestState.idle());
        assertTrue(ui.busy(false));
        assertTrue(ui.canCancel(false));
        assertTrue(ui.status().contains("Preparing"));
        ui.observe(state(1, Phase.RUNNING));
        ui.cancellationRequested();
        assertFalse(ui.canCancel(true));
        assertTrue(ui.status().contains("Cancelling"));
        ui.observe(state(1, Phase.CANCELLED));
        assertTrue(ui.busy(true), "cleanup reservation must keep Start disabled");
        assertFalse(ui.busy(false));
    }

    @Test void keepsLastSuccessAfterFailureCancelTimeoutAndExecutableChange() {
        var ui = new DashboardPresentation();
        ui.observe(state(1, Phase.COMPLETED));
        var success = ui.lastSuccess().orElseThrow();
        long id = 2;
        for (var phase : new Phase[] {Phase.FAILED, Phase.CANCELLED, Phase.TIMED_OUT}) {
            ui.requested();
            ui.observe(state(id++, phase));
            assertSame(success, ui.lastSuccess().orElseThrow());
            assertFalse(ui.busy(false));
        }
        ui.executableChanged();
        assertSame(success, ui.lastSuccess().orElseThrow());
        assertEquals(Phase.IDLE, ui.state().phase());
        ui.observe(state(1, Phase.COMPLETED));
        assertEquals(1, ui.state().testId());
    }

    @Test void rejectsOldTestCallbacksPhaseRegressionAndTerminalResurrection() {
        var ui = new DashboardPresentation();
        ui.observe(state(2, Phase.PROCESSING));
        ui.observe(state(1, Phase.COMPLETED));
        assertTrue(ui.lastSuccess().isEmpty());
        ui.observe(state(2, Phase.RUNNING));
        assertEquals(Phase.PROCESSING, ui.state().phase());
        ui.observe(state(2, Phase.CANCELLED));
        ui.observe(state(2, Phase.COMPLETED));
        assertEquals(Phase.CANCELLED, ui.state().phase());
        assertTrue(ui.lastSuccess().isEmpty());
    }

    @Test void showsActionableMissingCliAndTermsErrors() {
        var ui = new DashboardPresentation();
        ui.observe(new SpeedTestState(1, Phase.FAILED, "missing", Optional.of(ErrorCode.NOT_FOUND), Optional.empty()));
        assertTrue(ui.status().contains("Choose speedtest.exe"));
        ui.observe(new SpeedTestState(2, Phase.FAILED, "terms", Optional.of(ErrorCode.TERMS_REQUIRED), Optional.empty()));
        assertTrue(ui.status().contains("review and accept"));
    }

    private static SpeedTestState state(long id, Phase phase) {
        return new SpeedTestState(id, phase, phase.name(),
                switch (phase) {
                    case FAILED -> Optional.of(ErrorCode.NONZERO_EXIT);
                    case CANCELLED -> Optional.of(ErrorCode.CANCELLED);
                    case TIMED_OUT -> Optional.of(ErrorCode.TIMEOUT);
                    default -> Optional.empty();
                }, phase == Phase.COMPLETED ? Optional.of(new SpeedTestResult(12_500_000, 6_250_000,
                        12.5, 1.25, OptionalDouble.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Instant.EPOCH)) : Optional.empty());
    }
}
