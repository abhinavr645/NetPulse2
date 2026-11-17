package com.netpulse.ui;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

import com.netpulse.monitor.NetworkMonitorService;
import com.netpulse.monitor.OshiNetworkStatsProvider;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** JavaFX entry point for the NetPulse desktop application. */
public final class NetPulseApplication extends Application {
    private NetworkMonitorService monitor;

    @Override
    public void start(Stage stage) throws IOException {
        URL view = Objects.requireNonNull(
                getClass().getResource("/fxml/FxDashboardView.fxml"),
                "The dashboard FXML resource is missing.");
        Parent root = new FXMLLoader(view).load();

        stage.setTitle("NetPulse");
        stage.setScene(new Scene(root, 1040, 720));
        stage.setMinWidth(640);
        stage.setMinHeight(520);
        stage.show();
        monitor = new NetworkMonitorService(new OshiNetworkStatsProvider());
        monitor.start();
    }

    @Override
    public void stop() {
        if (monitor != null) {
            monitor.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
