package kz.kbtu.blog.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class MeasurementTest {

    private static Measurement withLatencies(long... sortedNanos) {
        return new Measurement(sortedNanos, 0, Map.of(), 0, 0, sortedNanos.length, 1_000_000_000L);
    }

    @Test
    void percentilesUseNearestRank() {
        long[] values = LongStream.rangeClosed(1, 100).toArray();

        assertThat(Measurement.percentile(values, 50)).isEqualTo(50);
        assertThat(Measurement.percentile(values, 95)).isEqualTo(95);
        assertThat(Measurement.percentile(values, 99)).isEqualTo(99);
        assertThat(Measurement.percentile(values, 100)).isEqualTo(100);
    }

    @Test
    void percentileOfSmallSamplesIsAnActualSample() {
        assertThat(Measurement.percentile(new long[] {10, 20}, 50)).isEqualTo(10);
        assertThat(Measurement.percentile(new long[] {10, 20}, 99)).isEqualTo(20);
        assertThat(Measurement.percentile(new long[] {10}, 0)).isEqualTo(10);
        assertThat(Measurement.percentile(new long[0], 99)).isZero();
    }

    @Test
    void derivedMetrics() {
        Measurement measurement = new Measurement(new long[] {1_000_000, 3_000_000}, 2, Map.of("UNAVAILABLE", 2),
                100, 500, 7, 2_000_000_000L);

        assertThat(measurement.requests()).isEqualTo(4);
        assertThat(measurement.errorRate()).isEqualTo(0.5);
        assertThat(measurement.retries()).isEqualTo(3);
        // Throughput counts only successes: a fast stream of errors is not performance.
        assertThat(measurement.throughputPerSecond()).isEqualTo(1.0);
        assertThat(measurement.averageMillis()).isEqualTo(2.0);
        assertThat(measurement.averageRequestBytes()).isEqualTo(50);
        assertThat(measurement.averageResponseBytes()).isEqualTo(250);
    }

    @Test
    void medianAcrossIterations() {
        List<Measurement> odd = List.of(withLatencies(1_000_000), withLatencies(9_000_000), withLatencies(2_000_000));
        List<Measurement> even = List.of(withLatencies(1_000_000), withLatencies(9_000_000),
                withLatencies(2_000_000), withLatencies(4_000_000));

        assertThat(SummaryReport.median(odd, Measurement::averageMillis)).isEqualTo(2.0);
        assertThat(SummaryReport.median(even, Measurement::averageMillis)).isCloseTo(3.0, Offset.offset(1e-9));
        assertThat(SummaryReport.median(List.of(), Measurement::averageMillis)).isZero();
    }
}
