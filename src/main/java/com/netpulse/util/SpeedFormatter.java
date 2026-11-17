package com.netpulse.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;

/** Decimal network units; formatting never feeds back into measurements or totals. */
public final class SpeedFormatter {
    public static final String UNAVAILABLE = "\u2014";
    private SpeedFormatter() { }

    public static String number(double value) {
        if (!Double.isFinite(value) || value < 0) { return UNAVAILABLE; }
        if (value == 0) { return "0"; }
        if (value < 0.01) { return "<0.01"; }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public static String mbps(double bytesPerSecond) { return number(bytesPerSecond / 125_000.0); }
    public static String megabytesPerSecond(double bytesPerSecond) {
        String value = number(bytesPerSecond / 1_000_000.0);
        return value.equals(UNAVAILABLE) ? value : value + " MB/s";
    }

    public static String traffic(BigInteger bytes) {
        if (bytes.signum() < 0) { throw new IllegalArgumentException("Negative traffic total"); }
        boolean gigabytes = bytes.compareTo(BigInteger.valueOf(1_000_000_000L)) >= 0;
        BigDecimal divisor = BigDecimal.valueOf(gigabytes ? 1_000_000_000L : 1_000_000L);
        return new BigDecimal(bytes).divide(divisor, 2, RoundingMode.HALF_UP).toPlainString()
                + (gigabytes ? " GB" : " MB");
    }
}
