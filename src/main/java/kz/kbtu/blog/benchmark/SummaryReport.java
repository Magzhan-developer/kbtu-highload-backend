package kz.kbtu.blog.benchmark;

import static kz.kbtu.blog.benchmark.ResultStore.number;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;
import kz.kbtu.blog.benchmark.ResultStore.Row;
import kz.kbtu.blog.benchmark.SerializationProbe.Sample;

/**
 * Builds summary.csv and summary.md. Each figure is the median across iterations, because one
 * disturbed iteration moves a median far less than a mean. Percentiles from different runs
 * cannot be averaged, so raw-runs.csv keeps every run's own values.
 */
final class SummaryReport {

    static final String REST = "REST";
    static final String GRPC = "gRPC";

    record Environment(String startedAt, String finishedAt, String javaVersion, int cores, BenchmarkDataset dataset) {
    }

    record Stats(
            double throughput, double throughputMin, double throughputMax,
            double average, double p50, double p95, double p99, double p99Min, double p99Max,
            double requestBytes, double responseBytes,
            int requests, int failures, long retries) {

        double errorRate() {
            return requests == 0 ? 0 : (double) failures / requests;
        }
    }

    private final BenchmarkConfig config;
    private final List<Row> rows;
    private final List<Sample> probe;
    private final Environment environment;

    SummaryReport(BenchmarkConfig config, List<Row> rows, List<Sample> probe, Environment environment) {
        this.config = config;
        this.rows = rows;
        this.probe = probe;
        this.environment = environment;
    }

    void write(Path directory) throws IOException {
        Files.writeString(directory.resolve("summary.csv"), csv(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("summary.md"), markdown(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("serialization-probe.csv"), probeCsv(), StandardCharsets.UTF_8);
    }

    Stats stats(String protocol, BenchmarkOperation operation, int concurrency) {
        List<Measurement> runs = rows.stream()
                .filter(row -> row.protocol().equals(protocol) && row.operation() == operation
                        && row.concurrency() == concurrency)
                .map(Row::measurement)
                .toList();
        return new Stats(
                median(runs, Measurement::throughputPerSecond),
                runs.stream().mapToDouble(Measurement::throughputPerSecond).min().orElse(0),
                runs.stream().mapToDouble(Measurement::throughputPerSecond).max().orElse(0),
                median(runs, Measurement::averageMillis),
                median(runs, m -> m.percentileMillis(50)),
                median(runs, m -> m.percentileMillis(95)),
                median(runs, m -> m.percentileMillis(99)),
                runs.stream().mapToDouble(m -> m.percentileMillis(99)).min().orElse(0),
                runs.stream().mapToDouble(m -> m.percentileMillis(99)).max().orElse(0),
                median(runs, Measurement::averageRequestBytes),
                median(runs, Measurement::averageResponseBytes),
                runs.stream().mapToInt(Measurement::requests).sum(),
                runs.stream().mapToInt(Measurement::failures).sum(),
                runs.stream().mapToLong(Measurement::retries).sum());
    }

    static double median(List<Measurement> runs, ToDoubleFunction<Measurement> metric) {
        double[] values = runs.stream().mapToDouble(metric).sorted().toArray();
        if (values.length == 0) {
            return 0;
        }
        int middle = values.length / 2;
        return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
    }

    private List<Integer> concurrencies() {
        return rows.stream().map(Row::concurrency).distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private String csv() {
        StringBuilder out = new StringBuilder("protocol,operation,category,concurrency,iterations,total_requests,"
                + "total_failures,error_rate,total_retries,median_throughput_rps,min_throughput_rps,max_throughput_rps,"
                + "median_avg_ms,median_p50_ms,median_p95_ms,median_p99_ms,min_p99_ms,max_p99_ms,"
                + "avg_request_payload_bytes,avg_response_payload_bytes,avg_total_payload_bytes\n");
        for (int concurrency : concurrencies()) {
            for (BenchmarkOperation operation : BenchmarkOperation.values()) {
                for (String protocol : List.of(REST, GRPC)) {
                    Stats s = stats(protocol, operation, concurrency);
                    out.append(String.join(",", protocol, operation.label(), operation.isWrite() ? "write" : "read",
                            String.valueOf(concurrency), String.valueOf(config.iterations()),
                            String.valueOf(s.requests()), String.valueOf(s.failures()), number(s.errorRate(), 6),
                            String.valueOf(s.retries()), number(s.throughput(), 1), number(s.throughputMin(), 1),
                            number(s.throughputMax(), 1), number(s.average(), 3), number(s.p50(), 3), number(s.p95(), 3),
                            number(s.p99(), 3), number(s.p99Min(), 3), number(s.p99Max(), 3),
                            number(s.requestBytes(), 1), number(s.responseBytes(), 1),
                            number(s.requestBytes() + s.responseBytes(), 1))).append('\n');
                }
            }
        }
        return out.toString();
    }

    private String probeCsv() {
        StringBuilder out = new StringBuilder("format,payload,bytes,serialize_micros,parse_micros\n");
        for (Sample sample : probe) {
            out.append(String.join(",", sample.format(), sample.payload(), String.valueOf(sample.bytes()),
                    number(sample.serializeMicros(), 2), number(sample.parseMicros(), 2))).append('\n');
        }
        return out.toString();
    }

    private String markdown() {
        List<String> out = new ArrayList<>();
        out.add("# Benchmark summary: gRPC vs REST");
        out.add("");
        out.add("All numbers in this file were measured by the benchmark runner. It is generated: edit nothing here.");
        out.add("");
        out.add("> **What this comparison means.** The two sides differ in transport (HTTP/2 over one multiplexed");
        out.add("> connection vs HTTP/1.1 with one connection per concurrent request) **and** in serialization");
        out.add("> (Protobuf vs JSON), and in framework (grpc-java on Netty vs Spring MVC on Tomcat). These results");
        out.add("> measure the combination. They do not show how much of any gap comes from Protobuf alone.");
        out.add("");
        environment(out);
        out.add("## Latency and throughput");
        out.add("");
        out.add("Medians across iterations. The difference is gRPC relative to REST: a negative latency change and a");
        out.add("positive throughput change favour gRPC. Compare a difference with the min-max range before trusting it.");
        out.add("");
        for (int concurrency : concurrencies()) {
            comparisonTable(out, concurrency);
        }
        payloadTable(out);
        reliabilityTable(out);
        probeTable(out);
        out.add("");
        return String.join("\n", out);
    }

    private void environment(List<String> out) {
        BenchmarkDataset dataset = environment.dataset();
        out.add("## Environment");
        out.add("");
        out.add("| Item | Value |");
        out.add("|---|---|");
        out.add("| Started / finished | " + environment.startedAt() + " / " + environment.finishedAt() + " |");
        out.add("| Client Java / cores | " + environment.javaVersion() + " / " + environment.cores() + " |");
        out.add("| Dataset at start | " + dataset.userCount() + " users, " + dataset.postCount() + " posts, "
                + dataset.commentCount() + " comments |");
        out.add("| Requests per run | " + config.readRequests() + " reads, " + config.writeRequests() + " writes |");
        out.add("| Warm-up per run | " + config.warmupRequests() + " requests, not recorded |");
        out.add("| Iterations | " + config.iterations() + ", with protocol order alternating |");
        out.add("| Concurrent clients | " + config.concurrencyLevels() + " |");
        out.add("| Sampled ids | " + config.sampleSize() + " per kind, seed " + config.seed() + " |");
        out.add("| Page size | " + config.pageLimit() + " |");
        out.add("| Retry policy | " + config.retryPolicy().maxAttempts() + " attempts, "
                + config.retryPolicy().attemptTimeout().toMillis() + " ms per attempt, "
                + config.retryPolicy().overallTimeout().toMillis() + " ms overall |");
        out.add("");
    }

    private void comparisonTable(List<String> out, int concurrency) {
        out.add("### " + concurrency + " concurrent clients");
        out.add("");
        out.add("| Operation | Protocol | Throughput (req/s) | Range | Avg (ms) | p50 (ms) | p95 (ms) | p99 (ms) | p99 range |");
        out.add("|---|---|---:|---:|---:|---:|---:|---:|---:|");
        for (BenchmarkOperation operation : BenchmarkOperation.values()) {
            Stats rest = stats(REST, operation, concurrency);
            Stats grpc = stats(GRPC, operation, concurrency);
            out.add(statsRow(operation.label(), REST, rest));
            out.add(statsRow(operation.label(), GRPC, grpc));
            out.add("| | **Difference** | **" + change(grpc.throughput(), rest.throughput()) + "** | | "
                    + change(grpc.average(), rest.average()) + " | " + change(grpc.p50(), rest.p50()) + " | "
                    + change(grpc.p95(), rest.p95()) + " | " + change(grpc.p99(), rest.p99()) + " | |");
        }
        out.add("");
    }

    private static String statsRow(String label, String protocol, Stats s) {
        return "| " + label + " | " + protocol + " | " + number(s.throughput(), 0) + " | "
                + number(s.throughputMin(), 0) + "-" + number(s.throughputMax(), 0) + " | "
                + number(s.average(), 2) + " | " + number(s.p50(), 2) + " | " + number(s.p95(), 2) + " | "
                + number(s.p99(), 2) + " | " + number(s.p99Min(), 2) + "-" + number(s.p99Max(), 2) + " |";
    }

    private static String change(double value, double baseline) {
        if (baseline == 0) {
            return "n/a";
        }
        double percent = (value - baseline) / baseline * 100;
        return (percent >= 0 ? "+" : "") + number(percent, 1) + "%";
    }

    private void payloadTable(List<String> out) {
        out.add("## Application payload size");
        out.add("");
        out.add("Average serialized message bytes only: JSON bodies for REST, Protobuf messages for gRPC. This is **not**");
        out.add("total network traffic. It excludes HTTP headers, the 5-byte gRPC message prefix, HTTP/2 frames and TCP.");
        out.add("Network bytes were not measured.");
        out.add("");
        out.add("The request columns are not like for like: REST sends ids in the URL and the idempotency key in a header,");
        out.add("neither of which counts as body bytes, while gRPC carries them inside the Protobuf message.");
        out.add("");
        out.add("| Operation | Request REST | Request gRPC | Response REST | Response gRPC | Response change | Total REST | Total gRPC |");
        out.add("|---|---:|---:|---:|---:|---:|---:|---:|");
        int concurrency = concurrencies().isEmpty() ? 0 : concurrencies().get(0);
        for (BenchmarkOperation operation : BenchmarkOperation.values()) {
            Stats rest = stats(REST, operation, concurrency);
            Stats grpc = stats(GRPC, operation, concurrency);
            out.add("| " + operation.label() + " | " + number(rest.requestBytes(), 0) + " | "
                    + number(grpc.requestBytes(), 0) + " | " + number(rest.responseBytes(), 0) + " | "
                    + number(grpc.responseBytes(), 0) + " | " + change(grpc.responseBytes(), rest.responseBytes()) + " | "
                    + number(rest.requestBytes() + rest.responseBytes(), 0) + " | "
                    + number(grpc.requestBytes() + grpc.responseBytes(), 0) + " |");
        }
        out.add("");
    }

    private void reliabilityTable(List<String> out) {
        out.add("## Errors and retries");
        out.add("");
        out.add("| Concurrency | Operation | Protocol | Requests | Failures | Error rate | Retries |");
        out.add("|---:|---|---|---:|---:|---:|---:|");
        for (int concurrency : concurrencies()) {
            for (BenchmarkOperation operation : BenchmarkOperation.values()) {
                for (String protocol : List.of(REST, GRPC)) {
                    Stats s = stats(protocol, operation, concurrency);
                    out.add("| " + concurrency + " | " + operation.label() + " | " + protocol + " | " + s.requests()
                            + " | " + s.failures() + " | " + number(s.errorRate() * 100, 3) + "% | " + s.retries() + " |");
                }
            }
        }
        out.add("");
    }

    private void probeTable(List<String> out) {
        out.add("## Serialization alone");
        out.add("");
        out.add("JSON and Protobuf timed in one process with no network, server or database, on identical data. This is a");
        out.add("simple timing loop rather than JMH, so treat it as indicative. It shows how much of an end-to-end gap");
        out.add("serialization could account for, on one side of one call.");
        out.add("");
        out.add("| Payload | Format | Bytes | Serialize (µs) | Parse (µs) |");
        out.add("|---|---|---:|---:|---:|");
        for (Sample sample : probe) {
            out.add("| " + sample.payload() + " | " + sample.format() + " | " + sample.bytes() + " | "
                    + number(sample.serializeMicros(), 2) + " | " + number(sample.parseMicros(), 2) + " |");
        }
        out.add("");
        out.add("Raw data: `raw-runs.csv` has one row per run, `summary.csv` has these tables.");
    }
}
