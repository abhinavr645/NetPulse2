package com.netpulse.core;

import static org.junit.jupiter.api.Assertions.*;

import com.netpulse.model.AdapterInfo;
import com.netpulse.model.CounterReading;
import com.netpulse.model.NetworkSpeed;
import com.netpulse.model.SpeedMeasurement;
import com.netpulse.model.SpeedMeasurement.Status;
import com.netpulse.support.AdapterFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NetworkSpeedCalculatorTest {
    private final NetworkSpeedCalculator calculator = new NetworkSpeedCalculator();
    private final AdapterInfo.Id adapter = new AdapterFixture(1).build().id();

    @Test
    void publishesExactCounterDeltasAlongsideTheUnroundedInterval() {
        long largeCounter = 9_007_199_254_740_993L;
        calculate(17, 23, 0);
        var measurement = calculate(largeCounter + 17, 124, 1_500_000_000L);
        var delta = measurement.trafficDelta().orElseThrow();
        assertEquals(largeCounter, delta.downloadedBytes());
        assertEquals(101, delta.uploadedBytes());
        assertEquals(0, delta.startedAtNanos());
        assertEquals(1_500_000_000L, delta.endedAtNanos());
    }

    @Test
    void firstReadingIsABaselineRatherThanASpeed() {
        unavailable(calculate(9_000_000, 8_000_000, 0), Status.BASELINE);
    }

    @ParameterizedTest
    @CsvSource({"1000000,0,8,0", "0,2000000,0,16", "1500000,250000,12,2", "0,0,0,0"})
    void measuresDownloadUploadSimultaneousAndValidIdleTraffic(long received, long transmitted,
            double downloadMbps, double uploadMbps) {
        calculate(100, 200, 0);
        NetworkSpeed speed = calculate(100 + received, 200 + transmitted, 1_000_000_000).speed().orElseThrow();
        assertEquals(downloadMbps, speed.downloadMbps(), 1e-9);
        assertEquals(uploadMbps, speed.uploadMbps(), 1e-9);
        assertEquals(downloadMbps / 8, speed.downloadMBps(), 1e-9);
        assertEquals(uploadMbps / 8, speed.uploadMBps(), 1e-9);
    }

    @Test
    void usesActualElapsedTimeInsteadOfAssumingOneSecond() {
        calculate(0, 0, 0);
        NetworkSpeed speed = calculate(1_500_000, 750_000, 1_500_000_000).speed().orElseThrow();
        assertEquals(8, speed.downloadMbps(), 1e-9);
        assertEquals(4, speed.uploadMbps(), 1e-9);
    }

    @ParameterizedTest
    @CsvSource({"9,30", "20,9", "9,9"})
    void aCounterDecreaseRebaselinesBothDirections(long received, long transmitted) {
        calculate(10, 10, 0);
        unavailable(calculate(received, transmitted, 1_000_000_000), Status.COUNTER_RESET);
        NetworkSpeed speed = calculate(received + 100, transmitted + 200, 2_000_000_000).speed().orElseThrow();
        assertEquals(100, speed.downloadBytesPerSecond());
        assertEquals(200, speed.uploadBytesPerSecond());
    }

    @Test
    void adapterChangesAndReusedIndicesCannotMixCounters() {
        calculate(1_000_000, 1_000_000, 0);
        AdapterInfo.Id replacement = new AdapterInfo.Id(adapter.index(), adapter.name(), "02:aa:bb:cc:dd:ee");
        unavailable(calculator.calculate(replacement, new CounterReading(9_000_000, 9_000_000, 1_000_000_000)),
                Status.ADAPTER_CHANGED);
        NetworkSpeed next = calculator.calculate(replacement,
                new CounterReading(10_000_000, 9_000_000, 2_000_000_000)).speed().orElseThrow();
        assertEquals(8, next.downloadMbps());
    }

    @ParameterizedTest
    @CsvSource({"1000000000", "999999999"})
    void nonpositiveElapsedTimeInvalidatesTheBaseline(long timestamp) {
        calculate(10, 10, 1_000_000_000);
        unavailable(calculate(1000, 1000, timestamp), Status.INVALID_TIME);
        unavailable(calculate(2000, 2000, 2_000_000_000), Status.BASELINE);
    }

    @Test
    void gapsOverThreeSecondsRebaselineButExactlyThreeSecondsIsValid() {
        calculate(0, 0, 0);
        assertEquals(Status.AVAILABLE, calculate(3_000_000, 0, 3_000_000_000L).status());
        unavailable(calculate(20_000_000, 0, 6_000_000_001L), Status.SAMPLING_GAP);
        assertEquals(8, calculate(21_000_000, 0, 7_000_000_001L).speed().orElseThrow().downloadMbps());
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,-1", "-1,-1"})
    void invalidCountersCannotBecomeABaselineOrProduceASpeed(long received, long transmitted) {
        calculate(0, 0, 0);
        unavailable(calculate(received, transmitted, 1_000_000_000), Status.INVALID_COUNTERS);
        unavailable(calculate(50, 50, 2_000_000_000), Status.BASELINE);
    }

    @Test
    void anExplicitResetRequiresAFreshBaseline() {
        calculate(0, 0, 0);
        calculator.reset();
        unavailable(calculate(100_000_000, 100_000_000, 1_000_000_000), Status.BASELINE);
    }

    @Test
    void signedMonotonicClockWraparoundIsHandled() {
        long first = Long.MAX_VALUE - 500_000_000;
        calculate(0, 0, first);
        assertEquals(8, calculate(1_000_000, 0, first + 1_000_000_000).speed().orElseThrow().downloadMbps());
    }

    @Test
    void maximumLongCountersDoNotOverflowDuringSubtraction() {
        calculate(0, 0, 0);
        NetworkSpeed speed = calculate(Long.MAX_VALUE, Long.MAX_VALUE, 1).speed().orElseThrow();
        assertTrue(Double.isFinite(speed.downloadMbps()));
        assertTrue(speed.downloadBytesPerSecond() > 0);
    }

    @Test
    void immutableSpeedModelRejectsInvalidNumbers() {
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new NetworkSpeed(invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> new NetworkSpeed(0, invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SpeedMeasurement.unavailable(Status.AVAILABLE));
    }

    private SpeedMeasurement calculate(long received, long transmitted, long nanos) {
        return calculator.calculate(adapter, new CounterReading(received, transmitted, nanos));
    }

    private static void unavailable(SpeedMeasurement measurement, Status reason) {
        assertEquals(reason, measurement.status());
        assertTrue(measurement.speed().isEmpty());
        assertTrue(measurement.trafficDelta().isEmpty());
    }
}
