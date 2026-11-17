package com.netpulse.ui;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.stream.Stream;

import com.netpulse.model.SpeedTestResult;
import com.netpulse.monitor.NetworkMonitorService;
import com.netpulse.speedtest.SpeedTestService;
import com.netpulse.util.SpeedFormatter;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

/** The FX timer reads published data only. Services own all I/O and calculations. */
public final class FxDashboardController implements AutoCloseable {
    private static final String DASH = SpeedFormatter.UNAVAILABLE;
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final DateTimeFormatter COMPLETED = DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss z")
            .withZone(ZoneId.systemDefault());
    private final NetworkMonitorService monitor;
    private SpeedTestService speedTests;
    private final DashboardPresentation presentation = new DashboardPresentation();
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.millis(200), _ -> refresh()));
    private SpeedTestResult displayedResult;
    private String actionError;
    private boolean closed;

    @FXML private ScrollPane dashboardScroll;
    @FXML private FlowPane columns;
    @FXML private VBox monitoringColumn, benchmarkColumn, downloadCard, uploadCard;
    @FXML private Label connectionStatus, headerAdapter, adapterName, interfaceName, macAddress,
            identification, monitoringStatus, sessionDownload, sessionUpload, testStatus, cliLocation,
            benchmarkDownload, benchmarkUpload, idlePing, jitter, packetLoss, server, isp, completedAt;
    @FXML private SpeedometerView downloadGauge, uploadGauge;
    @FXML private Button startTest, cancelTest, chooseCli;
    @FXML private ProgressIndicator testProgress;

    public FxDashboardController(NetworkMonitorService monitor, SpeedTestService speedTests) {
        this.monitor = Objects.requireNonNull(monitor);
        this.speedTests = Objects.requireNonNull(speedTests);
    }

    @FXML private void initialize() {
        refresh.setCycleCount(Timeline.INDEFINITE);
        dashboardScroll.viewportBoundsProperty().addListener((_, _, bounds) -> resizeColumns(bounds.getWidth()));
        refresh();
        refresh.play();
    }

    private void resizeColumns(double viewportWidth) {
        double width = Math.max(200, viewportWidth - 48);
        boolean sideBySide = width >= 940;
        double left = sideBySide ? Math.floor((width - 22) * .61) : Math.floor(width);
        double right = sideBySide ? Math.floor(width - 22 - left) : Math.floor(width);
        monitoringColumn.setPrefWidth(left);
        benchmarkColumn.setPrefWidth(right);
        // Leave a pixel of slack for independently snapped child widths at fractional DPI scales.
        double gaugeWidth = Math.floor((left - 18) / 2);
        downloadCard.setPrefWidth(gaugeWidth);
        uploadCard.setPrefWidth(gaugeWidth);
        columns.setPrefWrapLength(width);
    }

    private void refresh() {
        if (closed) { return; }
        var snapshot = monitor.latestSnapshot();
        var view = DashboardPresentation.monitor(snapshot);
        connectionStatus.setText(view.link());
        connectionStatus.pseudoClassStateChanged(PseudoClass.getPseudoClass("connected"),
                snapshot.connectionStatus() == com.netpulse.model.MonitoringSnapshot.ConnectionStatus.CONNECTED);
        headerAdapter.setText(view.adapter());
        adapterName.setText(view.adapter());
        interfaceName.setText(view.interfaceName());
        macAddress.setText(view.mac());
        identification.setText(view.identification());
        monitoringStatus.setText(view.status());
        downloadGauge.showBytesPerSecond(view.speed().map(value -> value.downloadBytesPerSecond()).orElse(null));
        uploadGauge.showBytesPerSecond(view.speed().map(value -> value.uploadBytesPerSecond()).orElse(null));
        var totals = monitor.dataSnapshot().sessionTotals();
        sessionDownload.setText(SpeedFormatter.traffic(totals.downloadedBytes()));
        sessionUpload.setText(SpeedFormatter.traffic(totals.uploadedBytes()));

        presentation.observe(speedTests.latestState());
        boolean busy = presentation.busy(speedTests.isRunning());
        startTest.setDisable(busy);
        chooseCli.setDisable(busy);
        cancelTest.setDisable(!presentation.canCancel(speedTests.isRunning()));
        testProgress.setVisible(busy);
        testProgress.setManaged(busy);
        testStatus.setText(actionError == null ? presentation.status() : actionError);
        testStatus.pseudoClassStateChanged(ERROR, actionError != null || presentation.state().error().isPresent());
        presentation.lastSuccess().filter(result -> result != displayedResult).ifPresent(this::display);
    }

    private void display(SpeedTestResult result) {
        displayedResult = result;
        benchmarkDownload.setText(SpeedFormatter.number(result.downloadMbps()));
        benchmarkUpload.setText(SpeedFormatter.number(result.uploadMbps()));
        idlePing.setText(SpeedFormatter.number(result.idleLatencyMillis()) + " ms");
        jitter.setText(SpeedFormatter.number(result.jitterMillis()) + " ms");
        packetLoss.setText(result.packetLossPercent().isPresent()
                ? SpeedFormatter.number(result.packetLossPercent().getAsDouble()) + "%" : DASH);
        server.setText(result.server().map(details -> {
            String text = Stream.of(details.name(), details.location(), details.country())
                    .flatMap(java.util.Optional::stream).filter(value -> !value.isBlank()).distinct()
                    .collect(java.util.stream.Collectors.joining(" · "));
            return text.isEmpty() ? details.host().orElseGet(() -> details.id().isPresent()
                    ? "Server " + details.id().getAsLong() : DASH) : text;
        }).orElse(DASH));
        isp.setText(result.isp().filter(value -> !value.isBlank()).orElse(DASH));
        completedAt.setText(COMPLETED.format(result.completedAt()));
    }

    @FXML private void startSpeedTest() {
        if (closed || presentation.busy(speedTests.isRunning())) { return; }
        // These methods signal the service; they perform no process operations here.
        try {
            speedTests.start();
            actionError = null;
            presentation.requested();
        } catch (IllegalStateException failure) {
            actionError = "Cannot start test: " + failure.getMessage() + ". Choose CLI to retry.";
        }
        refresh();
    }

    @FXML private void cancelSpeedTest() {
        if (speedTests.cancel()) { presentation.cancellationRequested(); }
        refresh();
    }

    @FXML private void chooseExecutable() {
        if (closed || presentation.busy(speedTests.isRunning())) { return; }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose the official Ookla Speedtest CLI");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Speedtest executable", "*.exe"));
        var selected = chooser.showOpenDialog(chooseCli.getScene().getWindow());
        if (selected != null) { useExecutable(selected.toPath()); }
    }

    /** An in-memory choice, validated on the service worker when testing is requested. */
    public void useExecutable(Path executable) {
        if (closed || presentation.busy(speedTests.isRunning())) {
            throw new IllegalStateException("Cannot change the CLI while a test is active or the dashboard is closed");
        }
        SpeedTestService replacement = new SpeedTestService(executable);
        speedTests.close();
        speedTests = replacement;
        presentation.executableChanged();
        actionError = null;
        cliLocation.setText(executable.getFileName().toString());
        cliLocation.setTooltip(new Tooltip(executable.toString()));
        refresh();
    }

    @Override public void close() {
        if (closed) { return; }
        closed = true;
        refresh.stop();
        downloadGauge.close();
        uploadGauge.close();
        speedTests.close();
        monitor.close();
    }
}
