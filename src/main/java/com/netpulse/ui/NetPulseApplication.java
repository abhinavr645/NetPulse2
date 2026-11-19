package com.netpulse.ui;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

import com.netpulse.monitor.NetworkMonitorService;
import com.netpulse.monitor.OshiNetworkStatsProvider;
import com.netpulse.speedtest.SpeedTestService;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/** JavaFX entry point for the NetPulse desktop application. */
public final class NetPulseApplication extends Application {
    private NetworkMonitorService monitor;
    private SpeedTestService speedTests;
    private FxDashboardController dashboard;

    @Override
    public void start(Stage stage) throws IOException {
        URL view = Objects.requireNonNull(
                getClass().getResource("/fxml/FxDashboardView.fxml"),
                "The dashboard FXML resource is missing.");
        monitor = new NetworkMonitorService(new OshiNetworkStatsProvider());
        speedTests = new SpeedTestService();
        FXMLLoader loader = new FXMLLoader(view);
        dashboard = new FxDashboardController(monitor, speedTests);
        loader.setControllerFactory(type -> {
            if (type != FxDashboardController.class) { throw new IllegalArgumentException("Unexpected controller: " + type); }
            return dashboard;
        });
        Parent root;
        try {
            root = loader.load();
        } catch (IOException | RuntimeException failure) {
            speedTests.close();
            monitor.close();
            throw failure;
        }

        stage.setTitle("NetPulse");
        stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/icons/netpulse.png"),
                "The application icon is missing.")));
        stage.setScene(new Scene(root, 1120, 784));
        stage.setMinWidth(640);
        stage.setMinHeight(520);
        stage.show();
        monitor.start();
    }

    @Override
    public void stop() {
        if (dashboard != null) {
            dashboard.close();
            return;
        }
        if (speedTests != null) {
            speedTests.close();
        }
        if (monitor != null) {
            monitor.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
