package kz.kbtu.blog.benchmark;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kz.kbtu.blog.client.RetryPolicy;

/** Settings for one benchmark, read from arguments such as --requests=20000. */
public record BenchmarkConfig(
        int readRequests,
        int writeRequests,
        List<Integer> concurrencyLevels,
        int warmupRequests,
        int prewarmPasses,
        int iterations,
        int sampleSize,
        int pageLimit,
        long seed,
        String restUrl,
        String grpcHost,
        int grpcPort,
        String dbUrl,
        String dbUser,
        String dbPassword,
        Path outputDirectory,
        RetryPolicy retryPolicy) {

    public static BenchmarkConfig parse(String... args) {
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            int separator = arg.indexOf('=');
            if (!arg.startsWith("--") || separator < 0) {
                throw new IllegalArgumentException("Expected --name=value but got: " + arg);
            }
            values.put(arg.substring(2, separator), arg.substring(separator + 1));
        }

        RetryPolicy defaults = RetryPolicy.defaults();
        RetryPolicy policy = new RetryPolicy(
                intValue(values, "max-attempts", defaults.maxAttempts()),
                Duration.ofMillis(intValue(values, "attempt-timeout-ms", (int) defaults.attemptTimeout().toMillis())),
                Duration.ofMillis(intValue(values, "overall-timeout-ms", (int) defaults.overallTimeout().toMillis())),
                defaults.baseBackoff(),
                defaults.maxBackoff());

        BenchmarkConfig config = new BenchmarkConfig(
                intValue(values, "requests", 20_000),
                intValue(values, "write-requests", 5_000),
                Arrays.stream(values.getOrDefault("concurrency", "16,64").split(","))
                        .map(String::trim).map(Integer::valueOf).toList(),
                intValue(values, "warmup", 2_000),
                intValue(values, "prewarm-passes", 3),
                intValue(values, "iterations", 4),
                intValue(values, "sample-size", 5_000),
                intValue(values, "page-limit", 20),
                Long.parseLong(values.getOrDefault("seed", "42")),
                values.getOrDefault("rest-url", "http://localhost:8080"),
                values.getOrDefault("grpc-host", "localhost"),
                intValue(values, "grpc-port", 9090),
                values.getOrDefault("db-url", "jdbc:postgresql://localhost:5432/blog"),
                values.getOrDefault("db-user", "blog"),
                values.getOrDefault("db-password", "blog"),
                Path.of(values.getOrDefault("output", "benchmark-results")),
                policy);
        values.keySet().removeAll(KNOWN_OPTIONS);
        if (!values.isEmpty()) {
            throw new IllegalArgumentException("Unknown options: " + values.keySet());
        }
        return config;
    }

    private static final List<String> KNOWN_OPTIONS = List.of(
            "requests", "write-requests", "concurrency", "warmup", "prewarm-passes", "iterations", "sample-size",
            "page-limit", "seed", "rest-url", "grpc-host", "grpc-port", "db-url", "db-user", "db-password",
            "output", "max-attempts", "attempt-timeout-ms", "overall-timeout-ms");

    private static int intValue(Map<String, String> values, String name, int defaultValue) {
        String value = values.get(name);
        return value == null ? defaultValue : Integer.parseInt(value);
    }
}
