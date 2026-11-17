package com.netpulse.util;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class SpeedFormatterTest {
    @Test void displaysDecimalMbpsAndMBpsWithoutChangingTheInput() {
        assertEquals("100.00", SpeedFormatter.mbps(12_500_000));
        assertEquals("12.50 MB/s", SpeedFormatter.megabytesPerSecond(12_500_000));
        assertEquals("0", SpeedFormatter.mbps(0));
        assertEquals("0 MB/s", SpeedFormatter.megabytesPerSecond(0));
        assertEquals("<0.01", SpeedFormatter.mbps(1));
    }
    @Test void distinguishesUnavailableFromIdleAndIsLocaleIndependent() {
        assertEquals("—", SpeedFormatter.number(Double.NaN));
        assertEquals("—", SpeedFormatter.mbps(-1));
        assertEquals("—", SpeedFormatter.megabytesPerSecond(Double.POSITIVE_INFINITY));
        assertEquals("1.25", SpeedFormatter.number(1.25));
    }
    @Test void formatsExactBigIntegerTotalsWithDecimalUnitsBeyondLongRange() {
        assertEquals("0.00 MB", SpeedFormatter.traffic(BigInteger.ZERO));
        assertEquals("1.50 MB", SpeedFormatter.traffic(BigInteger.valueOf(1_500_001)));
        assertEquals("1.00 GB", SpeedFormatter.traffic(BigInteger.valueOf(1_000_000_000)));
        assertEquals("18446744073.71 GB", SpeedFormatter.traffic(new BigInteger("18446744073709551616")));
        assertThrows(IllegalArgumentException.class, () -> SpeedFormatter.traffic(BigInteger.valueOf(-1)));
    }
}
