package kz.kbtu.blog.benchmark;

import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import kz.kbtu.blog.benchmark.Workload.PreparedCall;
import kz.kbtu.blog.client.BlogClient;
import kz.kbtu.blog.client.CallResult;
import kz.kbtu.blog.client.RestBlogClient.RestCallException;

/**
 * Closed-loop load: each worker sends a request, waits for the response, then sends the next.
 * Random targets depend on the seed and worker number but not on the client, so both protocols
 * receive the same requests.
 */
final class WorkloadRunner {

    private final Workload workload;

    WorkloadRunner(Workload workload) {
        this.workload = workload;
    }

    Measurement run(BlogClient client, BenchmarkOperation operation, int concurrency, int requests, long seed)
            throws InterruptedException {
        List<Worker> workers = IntStream.range(0, concurrency)
                .mapToObj(index -> new Worker(client, operation, share(requests, concurrency, index),
                        new SplittableRandom(seed * 31 + index)))
                .toList();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = workers.stream().map(worker -> Thread.ofPlatform().start(() -> {
            try {
                start.await();
                worker.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })).toList();

        long attemptsBefore = client.attemptCount();
        long startedAt = System.nanoTime();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        long wallNanos = System.nanoTime() - startedAt;
        return merge(workers, client.attemptCount() - attemptsBefore, wallNanos);
    }

    private static int share(int requests, int concurrency, int index) {
        return requests / concurrency + (index < requests % concurrency ? 1 : 0);
    }

    private static Measurement merge(List<Worker> workers, long attempts, long wallNanos) {
        long[] latencies = workers.stream()
                .flatMapToLong(worker -> Arrays.stream(worker.latencies, 0, worker.successes))
                .sorted()
                .toArray();
        Map<String, Integer> failuresByType = new HashMap<>();
        workers.forEach(worker -> worker.failuresByType.forEach(
                (type, count) -> failuresByType.merge(type, count, Integer::sum)));
        return new Measurement(
                latencies,
                failuresByType.values().stream().mapToInt(Integer::intValue).sum(),
                failuresByType,
                workers.stream().mapToLong(worker -> worker.requestBytes).sum(),
                workers.stream().mapToLong(worker -> worker.responseBytes).sum(),
                attempts,
                wallNanos);
    }

    private final class Worker {

        private final BlogClient client;
        private final BenchmarkOperation operation;
        private final SplittableRandom random;
        private final long[] latencies;
        private final Map<String, Integer> failuresByType = new HashMap<>();
        private int successes;
        private long requestBytes;
        private long responseBytes;

        Worker(BlogClient client, BenchmarkOperation operation, int requests, SplittableRandom random) {
            this.client = client;
            this.operation = operation;
            this.random = random;
            this.latencies = new long[requests];
        }

        void run() {
            for (int i = 0; i < latencies.length; i++) {
                PreparedCall prepared = workload.prepare(client, operation, random);
                long startedAt = System.nanoTime();
                try {
                    CallResult result = prepared.call().call();
                    latencies[successes++] = System.nanoTime() - startedAt;
                    requestBytes += result.requestBytes();
                    responseBytes += result.responseBytes();
                    prepared.onSuccess().run();
                } catch (Exception e) {
                    failuresByType.merge(failureType(e), 1, Integer::sum);
                }
            }
        }

        private static String failureType(Exception e) {
            if (e instanceof StatusRuntimeException failure) {
                return failure.getStatus().getCode().name();
            }
            if (e instanceof RestCallException failure) {
                return "HTTP_" + failure.status();
            }
            return e.getClass().getSimpleName();
        }
    }
}
