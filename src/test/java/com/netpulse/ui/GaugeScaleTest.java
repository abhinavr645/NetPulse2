package com.netpulse.ui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GaugeScaleTest {
    @Test void expandsToReadableStepsAndRemainsStable() {
        var scale = new GaugeScale(100);
        assertEquals(100, scale.include(0));
        assertEquals(100, scale.include(99));
        assertEquals(200, scale.include(101));
        assertEquals(200, scale.include(20));
        assertEquals(500, scale.include(201));
        assertEquals(1_000, scale.include(501));
        assertEquals(2_000, scale.include(1_001));
    }
    @Test void scalesAreIndependentAndInvalidValuesCannotCorruptThem() {
        var down = new GaugeScale(100);
        var up = new GaugeScale(100);
        down.include(750);
        assertEquals(100, up.maximum());
        for (double value : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(100, up.include(value));
        }
        assertTrue(Double.isFinite(up.include(Double.MAX_VALUE)));
    }
    @Test void rejectsInvalidInitialMaximum() {
        assertThrows(IllegalArgumentException.class, () -> new GaugeScale(0));
        assertThrows(IllegalArgumentException.class, () -> new GaugeScale(Double.NaN));
    }
}
