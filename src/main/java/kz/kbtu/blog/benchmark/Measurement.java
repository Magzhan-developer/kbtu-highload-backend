package kz.kbtu.blog.benchmark;

import java.util.Arrays;
import java.util.Map;

/** What one run of one operation produced. Latencies cover successful calls and retries. */
record Measurement(
        long[] sortedLatencyNanos,
        int failures,
        Map<String, Integer> failuresByType,
        long requestBytes,
        long responseBytes,
        long attempts,
        long wallNanos) {

    int requests() {
        return successes() + failures;
    }

    int successes() {
        return sortedLatencyNanos.length;
    }

    long retries() {
        return attempts - requests();
    }

    double errorRate() {
        return requests() == 0 ? 0 : (double) failures / requests();
    }

    double throughputPerSecond() {
        return successes() / (wallNanos / 1e9);
    }

    double averageMillis() {
        return Arrays.stream(sortedLatencyNanos).average().orElse(0) / 1e6;
    }

    double percentileMillis(double percentile) {
        return percentile(sortedLatencyNanos, percentile) / 1e6;
    }

    double maxMillis() {
        return sortedLatencyNanos.length == 0 ? 0 : sortedLatencyNanos[sortedLatencyNanos.length - 1] / 1e6;
    }

    double averageRequestBytes() {
        return successes() == 0 ? 0 : (double) requestBytes / successes();
    }

    double averageResponseBytes() {
        return successes() == 0 ? 0 : (double) responseBytes / successes();
    }

    /** Nearest-rank percentile: the smallest value that at least p% of samples do not exceed. */
    static long percentile(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(percentile / 100 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }
}
