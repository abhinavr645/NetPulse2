package com.netpulse.ui;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** Initializes the dashboard shell; monitoring belongs to a later phase. */
public final class FxDashboardController {

    @FXML
    private Label applicationStatus;

    @FXML
    private void initialize() {
        applicationStatus.setText("Application ready");
    }
}
