package com.netpulse.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.netpulse.model.SpeedTestPeriod;
import com.netpulse.model.TrafficHistorySample;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/** Bounded Canvas paths give explicit gaps without JavaFX chart interpolation.
 * One dashboard timer supplies frames on the FX thread; this view owns no timer.
 */
public final class TrafficChartView extends Region implements AutoCloseable {
    private static final Color DOWNLOAD = Color.web("#6be1c4");
    private static final Color UPLOAD = Color.web("#89b7ff");
    private static final Color TEXT = Color.web("#a2b0c4");
    private static final Color ACTIVE = Color.web("#ffcc80");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Canvas canvas = new Canvas();
    private final TrafficChartData data = new TrafficChartData();
    private TrafficChartData.Frame frame = data.project(List.of(), List.of(), 0, Instant.EPOCH);
    private boolean closed;

    public TrafficChartView() {
        getStyleClass().addAll("card", "traffic-chart");
        getChildren().add(canvas);
        setMinSize(280, 240);
        setPrefSize(600, 250);
    }

    public void update(List<TrafficHistorySample> history, List<SpeedTestPeriod> periods, long nowNanos, Instant wallTime) {
        if (closed) { return; }
        frame = data.project(history, periods, nowNanos, wallTime);
        setAccessibleText("OSHI traffic history, " + frame.pointCount() + " actual samples, "
                + frame.bands().size() + " CLI periods, " + (frame.activeTest() ? "SPEED TEST RUNNING" : "no active test"));
        draw();
    }

    public TrafficChartData.Frame frame() { return frame; }

    @Override protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    private void draw() {
        double width = canvas.getWidth(), height = canvas.getHeight();
        if (closed || width < 200 || height < 180) { return; }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, width, height);
        g.setTextBaseline(VPos.BASELINE);
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        g.setFill(Color.web("#e8edf5"));
        g.fillText("Traffic history", 16, 25);
        g.setFont(Font.font("Segoe UI", 10));
        g.setFill(TEXT);
        g.fillText("OSHI adapter traffic · Mbps", 16, 43);
        if (frame.activeTest()) {
            g.setTextAlign(TextAlignment.RIGHT);
            g.setFill(ACTIVE);
            g.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
            g.fillText("SPEED TEST RUNNING", width - 16, 25);
        }
        double left = 53, right = width - 18, top = 62, bottom = height - 48;
        double plotWidth = right - left, plotHeight = bottom - top;
        g.save();
        g.beginPath(); g.rect(left, top, plotWidth, plotHeight); g.clip();
        for (var band : frame.bands()) {
            double x = x(band.fromSecondsAgo(), left, plotWidth);
            double end = x(band.toSecondsAgo(), left, plotWidth);
            g.setFill(band.active() ? Color.web("#ffcc80", .13) : Color.web("#b2bbda", .085));
            g.fillRect(x, top, Math.max(1, end - x), plotHeight);
            g.setStroke(band.active() ? Color.web("#ffcc80", .6) : Color.web("#b2bbda", .3));
            g.setLineWidth(1);
            g.strokeLine(x, top, x, bottom);
        }
        g.setStroke(Color.web("#29384b"));
        g.setLineDashes();
        g.setLineWidth(1);
        for (int i = 0; i <= 4; i++) {
            double y = bottom - plotHeight * i / 4;
            g.strokeLine(left, y, right, y);
        }
        for (var segment : frame.segments()) {
            drawSeries(g, segment, false, left, plotWidth, bottom, plotHeight);
            drawSeries(g, segment, true, left, plotWidth, bottom, plotHeight);
        }
        g.restore();
        g.setFont(Font.font("Segoe UI", 10));
        g.setFill(TEXT);
        g.setTextAlign(TextAlignment.RIGHT);
        for (int i = 0; i <= 4; i++) {
            double value = frame.maximumMbps() * (i / 4.0);
            g.fillText(java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(),
                    left - 8, bottom - plotHeight * i / 4 + 3);
        }
        for (int i = 0; i <= 4; i++) {
            g.setTextAlign(i == 0 ? TextAlignment.LEFT : i == 4 ? TextAlignment.RIGHT : TextAlignment.CENTER);
            g.fillText(TIME.format(frame.wallTime().minusSeconds(60 - i * 15)), left + plotWidth * i / 4, bottom + 17);
        }
        g.setTextAlign(TextAlignment.LEFT);
        if (frame.pointCount() == 0) {
            g.setFill(TEXT);
            g.fillText("Waiting for valid traffic samples", left + 10, top + plotHeight / 2);
        }
        g.setFill(DOWNLOAD); g.fillRect(16, height - 15, 13, 2);
        g.setFill(TEXT); g.fillText("Download", 34, height - 11);
        g.setFill(UPLOAD); g.fillRect(105, height - 15, 13, 2);
        g.setFill(TEXT); g.fillText("Upload", 123, height - 11);
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("Shading: entire CLI run", width - 16, height - 11);
    }

    private void drawSeries(GraphicsContext g, TrafficChartData.Segment segment, boolean upload,
            double left, double width, double bottom, double height) {
        Color color = upload ? UPLOAD : DOWNLOAD;
        g.setStroke(color); g.setFill(color); g.setLineWidth(2);
        if (upload) { g.setLineDashes(5, 3); } else { g.setLineDashes(); }
        g.beginPath();
        boolean first = true;
        for (var point : segment.points()) {
            double px = x(point.secondsAgo(), left, width);
            double value = upload ? point.uploadMbps() : point.downloadMbps();
            double py = bottom - value / frame.maximumMbps() * height;
            if (first) { g.moveTo(px, py); first = false; } else { g.lineTo(px, py); }
        }
        g.stroke();
        for (var point : segment.points()) {
            double px = x(point.secondsAgo(), left, width);
            double py = bottom - (upload ? point.uploadMbps() : point.downloadMbps()) / frame.maximumMbps() * height;
            double radius = upload ? 1.4 : 2.2;
            g.fillOval(px - radius, py - radius, radius * 2, radius * 2);
        }
        g.setLineDashes();
    }

    private static double x(double secondsAgo, double left, double width) { return left + (secondsAgo + 60) / 60 * width; }

    @Override public void close() { closed = true; }
}
