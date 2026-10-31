package kz.kbtu.blog.benchmark;

import static kz.kbtu.blog.benchmark.SummaryReport.GRPC;
import static kz.kbtu.blog.benchmark.SummaryReport.REST;

import java.time.Instant;
import java.util.List;
import kz.kbtu.blog.benchmark.ResultStore.Row;
import kz.kbtu.blog.benchmark.SerializationProbe.Sample;
import kz.kbtu.blog.benchmark.SummaryReport.Environment;
import kz.kbtu.blog.client.BlogClient;
import kz.kbtu.blog.client.GrpcBlogClient;
import kz.kbtu.blog.client.RestBlogClient;

/**
 * Compares REST and gRPC on the same operations. Run it as a separate process from the server:
 *
 * <pre>
 * java -Dloader.main=kz.kbtu.blog.benchmark.BenchmarkMain -jar target/blog-platform-0.1.0-SNAPSHOT.jar --requests=20000
 * </pre>
 */
public final class BenchmarkMain {

    private final BenchmarkConfig config;

    private BenchmarkMain(BenchmarkConfig config) {
        this.config = config;
    }

    public static void main(String[] args) throws Exception {
        new BenchmarkMain(BenchmarkConfig.parse(args)).run();
    }

    static void runForTest(BenchmarkConfig config) throws Exception {
        new BenchmarkMain(config).run();
    }

    private void run() throws Exception {
        String startedAt = Instant.now().toString();
        BenchmarkDataset dataset = BenchmarkDataset.load(config);
        log("Run %s. Dataset: %d posts, %d comments", config.runId(), dataset.postCount(), dataset.commentCount());

        try (PendingOperationJournal journal = new PendingOperationJournal(config.journalFile());
                ResultStore results = new ResultStore(config.outputDirectory());
                BlogClient rest = new RestBlogClient(config.restUrl(), config.retryPolicy());
                BlogClient grpc = new GrpcBlogClient(config.grpcHost(), config.grpcPort(), config.retryPolicy())) {
            Workload workload = new Workload(dataset, journal, config.pageLimit());
            WorkloadRunner runner = new WorkloadRunner(workload);

            int recovered = workload.recoverPending(rest);
            if (recovered > 0) {
                log("Finished %d writes left pending by an earlier run", recovered);
            }

            log("Measuring serialization alone");
            List<Sample> probe = new SerializationProbe().run();

            prewarm(runner, rest, grpc);
            for (int concurrency : config.concurrencyLevels()) {
                measure(runner, results, rest, grpc, BenchmarkOperation.reads(), concurrency, config.readRequests());
            }
            // Writes run last, because the rows they add would change what later reads return.
            for (int concurrency : config.concurrencyLevels()) {
                measure(runner, results, rest, grpc, BenchmarkOperation.writes(), concurrency, config.writeRequests());
            }

            Environment environment = new Environment(startedAt, Instant.now().toString(),
                    System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(), dataset);
            new SummaryReport(config, results.rows(), probe, environment).write(config.outputDirectory());
            RunMetadata.of(config, environment).write(config.outputDirectory());
            log("Done. Results are in %s", config.outputDirectory().toAbsolutePath());
        }
    }

    /**
     * Loads the sampled rows into PostgreSQL's cache and warms both JVMs, so the protocol
     * measured first does not pay for cold caches that the second one then benefits from.
     */
    private void prewarm(WorkloadRunner runner, BlogClient rest, BlogClient grpc) throws InterruptedException {
        int requests = config.sampleSize() * config.prewarmPasses();
        for (BenchmarkOperation operation : BenchmarkOperation.reads()) {
            log("Prewarming %s", operation.label());
            runner.run(rest, operation, 8, requests, config.seed() + 1);
            runner.run(grpc, operation, 8, requests, config.seed() + 2);
        }
    }

    private void measure(
            WorkloadRunner runner, ResultStore results, BlogClient rest, BlogClient grpc,
            List<BenchmarkOperation> operations, int concurrency, int requests) throws Exception {
        for (BenchmarkOperation operation : operations) {
            for (int iteration = 1; iteration <= config.iterations(); iteration++) {
                // Alternating which protocol goes first cancels out the advantage of going second.
                boolean restFirst = iteration % 2 == 1;
                long seed = config.seed() + 1_000L * operation.ordinal() + 100L * iteration + concurrency;
                String first = restFirst ? REST : GRPC;
                String second = restFirst ? GRPC : REST;
                measureOne(runner, results, restFirst ? rest : grpc, first, operation, concurrency, requests, iteration, 1, seed);
                measureOne(runner, results, restFirst ? grpc : rest, second, operation, concurrency, requests, iteration, 2, seed);
            }
        }
    }

    private void measureOne(
            WorkloadRunner runner, ResultStore results, BlogClient client, String protocol,
            BenchmarkOperation operation, int concurrency, int requests, int iteration, int position, long seed)
            throws Exception {
        if (config.warmupRequests() > 0) {
            runner.run(client, operation, concurrency, config.warmupRequests(), seed + 7);
        }
        Measurement measurement = runner.run(client, operation, concurrency, requests, seed);
        results.add(new Row(protocol, operation, concurrency, iteration, position, measurement));
        log("%-4s %-14s c=%-3d it=%d  %7.0f req/s  p50=%6.2f  p99=%7.2f ms  failures=%d retries=%d",
                protocol, operation.label(), concurrency, iteration, measurement.throughputPerSecond(),
                measurement.percentileMillis(50), measurement.percentileMillis(99),
                measurement.failures(), measurement.retries());
    }

    private static void log(String format, Object... arguments) {
        System.out.println(Instant.now() + " " + String.format(format, arguments));
    }
}
