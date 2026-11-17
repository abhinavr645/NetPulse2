package com.netpulse.ui;

/** Independent, grow-only 1/2/5 scale. No measurement is rounded or modified. */
public final class GaugeScale {
    private double maximum;

    public GaugeScale(double initialMaximum) {
        if (!Double.isFinite(initialMaximum) || initialMaximum <= 0) {
            throw new IllegalArgumentException("A positive finite scale is required");
        }
        maximum = initialMaximum;
    }

    public double include(double mbps) {
        if (Double.isFinite(mbps) && mbps > maximum) {
            double power = Math.pow(10, Math.floor(Math.log10(mbps)));
            double ratio = mbps / power;
            double next = (ratio <= 1 ? 1 : ratio <= 2 ? 2 : ratio <= 5 ? 5 : 10) * power;
            maximum = Double.isFinite(next) ? Math.max(mbps, next) : mbps;
        }
        return maximum;
    }

    public double maximum() { return maximum; }
}
