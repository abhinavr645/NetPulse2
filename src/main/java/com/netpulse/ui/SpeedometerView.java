package com.netpulse.ui;

import com.netpulse.util.SpeedFormatter;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

/** Native JavaFX gauge. Only the arc is interpolated; text always shows the actual sample. */
public final class SpeedometerView extends Region implements AutoCloseable {
    private final GaugeScale scale = new GaugeScale(100);
    private final Arc track = arc();
    private final Arc fill = arc();
    private final Label value = new Label(SpeedFormatter.UNAVAILABLE);
    private final Label secondary = new Label(SpeedFormatter.UNAVAILABLE);
    private final Label zero = new Label("0");
    private final Label maximum = new Label("100 Mbps");
    private final VBox reading = new VBox(2, value, new Label("Mbps"), secondary);
    private final DoubleProperty fraction = new SimpleDoubleProperty();
    private final Timeline animation = new Timeline();
    private Double lastValue;

    public SpeedometerView() {
        getStyleClass().add("speedometer");
        setMinSize(200, 182);
        setPrefSize(280, 182);
        track.getStyleClass().add("gauge-track");
        fill.getStyleClass().add("gauge-fill");
        value.getStyleClass().add("gauge-value");
        secondary.getStyleClass().add("gauge-secondary");
        zero.getStyleClass().add("gauge-scale");
        maximum.getStyleClass().add("gauge-scale");
        reading.setAlignment(Pos.CENTER);
        reading.getStyleClass().add("gauge-reading");
        getChildren().addAll(track, fill, reading, zero, maximum);
        fraction.addListener((_, _, current) -> fill.setLength(-240 * current.doubleValue()));
        fill.setLength(0);
        setAccessibleText("Speed unavailable");
    }

    private static Arc arc() {
        Arc arc = new Arc();
        arc.setStartAngle(210);
        arc.setLength(-240);
        arc.setType(ArcType.OPEN);
        arc.setFill(Color.TRANSPARENT);
        arc.setStrokeWidth(9);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        return arc;
    }

    public void showBytesPerSecond(Double bytesPerSecond) {
        if (bytesPerSecond == null || !Double.isFinite(bytesPerSecond) || bytesPerSecond < 0) {
            lastValue = null;
            animation.stop();
            fraction.set(0);
            value.setText(SpeedFormatter.UNAVAILABLE);
            secondary.setText(SpeedFormatter.UNAVAILABLE);
            setAccessibleText("Speed unavailable");
            return;
        }
        if (bytesPerSecond.equals(lastValue)) { return; }
        lastValue = bytesPerSecond;
        double mbps = bytesPerSecond / 125_000.0;
        scale.include(mbps);
        maximum.setText(String.format(java.util.Locale.ROOT, "%.0f Mbps", scale.maximum()));
        value.setText(SpeedFormatter.mbps(bytesPerSecond));
        secondary.setText(SpeedFormatter.megabytesPerSecond(bytesPerSecond));
        setAccessibleText(value.getText() + " Mbps, " + secondary.getText());
        // stop() resets a Timeline to its starting keyframe. Preserve the visible
        // position first so successive readings ease from it instead of from zero.
        double from = fraction.get();
        animation.stop();
        fraction.set(from);
        animation.getKeyFrames().setAll(new KeyFrame(Duration.ZERO, new KeyValue(fraction, from)),
                new KeyFrame(Duration.millis(200), new KeyValue(fraction,
                        mbps / scale.maximum(), Interpolator.EASE_BOTH)));
        animation.playFromStart();
    }

    @Override protected void layoutChildren() {
        double width = getWidth();
        double radius = Math.min((width - 34) / 2, (getHeight() - 32) / 1.866);
        double centerY = radius + 9;
        for (Arc arc : new Arc[] {track, fill}) {
            arc.setCenterX(width / 2);
            arc.setCenterY(centerY);
            arc.setRadiusX(radius);
            arc.setRadiusY(radius);
        }
        reading.resizeRelocate(8, centerY - 49, width - 16, 100);
        zero.autosize();
        maximum.autosize();
        zero.relocate(width / 2 - radius * .866 - 3, centerY + radius * .5 + 11);
        maximum.relocate(width / 2 + radius * .866 - maximum.getWidth() + 3,
                centerY + radius * .5 + 11);
    }

    @Override public void close() { animation.stop(); }
}
