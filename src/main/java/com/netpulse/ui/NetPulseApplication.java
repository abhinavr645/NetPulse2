package com.netpulse.ui;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** JavaFX entry point for the NetPulse desktop application. */
public final class NetPulseApplication extends Application {

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
    }

    // JavaFX exits naturally when the last window closes. Phase 1 owns no
    // background tasks, processes, or other resources requiring stop() cleanup.
    public static void main(String[] args) {
        launch(args);
    }
}
