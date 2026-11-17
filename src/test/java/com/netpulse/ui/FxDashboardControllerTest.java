package com.netpulse.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import com.netpulse.monitor.NetworkMonitorService;
import com.netpulse.speedtest.SpeedTestService;
import org.junit.jupiter.api.Test;
import javafx.fxml.FXMLLoader;
import javafx.fxml.LoadException;

class FxDashboardControllerTest {
    @Test void closeBeforeFxmlInitializationStillReleasesBothOwnedServices() {
        try (var monitor = new NetworkMonitorService(List::of); var tests = new SpeedTestService()) {
            var controller = new FxDashboardController(monitor, tests);
            assertDoesNotThrow(controller::close, "Failed/aborted FXML loading must still allow cleanup");
            assertDoesNotThrow(controller::close, "Cleanup is idempotent");
            assertThrows(IllegalStateException.class, monitor::start);
            assertThrows(IllegalStateException.class, tests::start);
        }
    }

    @Test void failedFxmlInitializationStillAllowsCleanupOfARunningMonitor() {
        try (var monitor = new NetworkMonitorService(List::of); var tests = new SpeedTestService()) {
            monitor.start();
            var controller = new FxDashboardController(monitor, tests);
            FXMLLoader loader = new FXMLLoader();
            loader.setControllerFactory(_ -> controller);
            String brokenView = "<?import javafx.scene.layout.VBox?>"
                    + "<VBox xmlns:fx=\"http://javafx.com/fxml/1\" fx:controller=\"com.netpulse.ui.FxDashboardController\"/>";
            assertThrows(LoadException.class, () -> loader.load(new ByteArrayInputStream(brokenView.getBytes(StandardCharsets.UTF_8))));
            assertDoesNotThrow(controller::close);
            assertFalse(monitor.isRunning());
            assertThrows(IllegalStateException.class, tests::start);
        }
    }
}
