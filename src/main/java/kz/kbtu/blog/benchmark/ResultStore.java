package kz.kbtu.blog.benchmark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Writes each measured run to raw-runs.csv at once, so a crash keeps what was finished. */
final class ResultStore implements AutoCloseable {

    record Row(
            String protocol,
            BenchmarkOperation operation,
            int concurrency,
            int iteration,
            int position,
            Measurement measurement) {
    }

    private static final String HEADER = "protocol,operation,category,concurrency,iteration,order_in_iteration,"
            + "requests,successes,failures,error_rate,retries,wall_seconds,throughput_rps,"
            + "avg_ms,p50_ms,p95_ms,p99_ms,max_ms,"
            + "avg_request_payload_bytes,avg_response_payload_bytes,avg_total_payload_bytes,failure_types";

    private final BufferedWriter csv;
    private final List<Row> rows = new ArrayList<>();

    ResultStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        csv = Files.newBufferedWriter(directory.resolve("raw-runs.csv"), StandardCharsets.UTF_8);
        csv.write(HEADER);
        csv.newLine();
    }

    void add(Row row) throws IOException {
        rows.add(row);
        Measurement m = row.measurement();
        csv.write(String.join(",",
                row.protocol(),
                row.operation().label(),
                row.operation().isWrite() ? "write" : "read",
                String.valueOf(row.concurrency()),
                String.valueOf(row.iteration()),
                String.valueOf(row.position()),
                String.valueOf(m.requests()),
                String.valueOf(m.successes()),
                String.valueOf(m.failures()),
                number(m.errorRate(), 6),
                String.valueOf(m.retries()),
                number(m.wallNanos() / 1e9, 3),
                number(m.throughputPerSecond(), 1),
                number(m.averageMillis(), 3),
                number(m.percentileMillis(50), 3),
                number(m.percentileMillis(95), 3),
                number(m.percentileMillis(99), 3),
                number(m.maxMillis(), 3),
                number(m.averageRequestBytes(), 1),
                number(m.averageResponseBytes(), 1),
                number(m.averageRequestBytes() + m.averageResponseBytes(), 1),
                failureTypes(m)));
        csv.newLine();
        csv.flush();
    }

    List<Row> rows() {
        return List.copyOf(rows);
    }

    static String number(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static String failureTypes(Measurement m) {
        return new TreeMap<>(m.failuresByType()).entrySet().stream()
                .map(Map.Entry::toString)
                .collect(Collectors.joining(";"));
    }

    @Override
    public void close() throws IOException {
        csv.close();
    }
}
