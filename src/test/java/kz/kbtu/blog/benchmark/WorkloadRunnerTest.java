package kz.kbtu.blog.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkloadRunnerTest {

    private static final BenchmarkDataset DATASET = new BenchmarkDataset(
            new long[] {1, 2, 3, 4, 5}, new long[] {11, 12, 13}, new long[] {21, 22}, 3, 5, 2);

    @TempDir
    Path directory;

    private PendingOperationJournal journal;
    private WorkloadRunner runner;

    @BeforeEach
    void create() throws Exception {
        journal = new PendingOperationJournal(directory.resolve("journal.log"));
        runner = new WorkloadRunner(new Workload(DATASET, journal, 20));
    }

    @Test
    void sendsExactlyTheRequestedNumberEvenWhenItDoesNotDivideEvenly() throws Exception {
        FakeBlogClient client = new FakeBlogClient();

        Measurement measurement = runner.run(client, BenchmarkOperation.GET_POST, 7, 100, 1);

        assertThat(measurement.requests()).isEqualTo(100);
        assertThat(client.calls).hasSize(100);
    }

    @Test
    void bothProtocolsReceiveIdenticalTargets() throws Exception {
        FakeBlogClient first = new FakeBlogClient();
        FakeBlogClient second = new FakeBlogClient();

        runner.run(first, BenchmarkOperation.GET_COMMENTS, 4, 200, 99);
        runner.run(second, BenchmarkOperation.GET_COMMENTS, 4, 200, 99);

        assertThat(second.calls).containsExactlyInAnyOrderElementsOf(first.calls);
    }

    @Test
    void differentSeedsProduceDifferentTargets() throws Exception {
        FakeBlogClient first = new FakeBlogClient();
        FakeBlogClient second = new FakeBlogClient();

        runner.run(first, BenchmarkOperation.GET_POST, 1, 100, 1);
        runner.run(second, BenchmarkOperation.GET_POST, 1, 100, 2);

        assertThat(second.calls).isNotEqualTo(first.calls);
    }

    @Test
    void failuresAreCountedAndExcludedFromLatencyAndBytes() throws Exception {
        FakeBlogClient client = new FakeBlogClient();
        client.failEvery = 4;

        Measurement measurement = runner.run(client, BenchmarkOperation.GET_POST, 1, 100, 1);

        assertThat(measurement.failures()).isEqualTo(25);
        assertThat(measurement.successes()).isEqualTo(75);
        assertThat(measurement.failuresByType()).containsEntry("IllegalStateException", 25);
        assertThat(measurement.requestBytes()).isEqualTo(750);
        assertThat(measurement.responseBytes()).isEqualTo(7_500);
    }

    @Test
    void successfulWritesLeaveNothingPending() throws Exception {
        runner.run(new FakeBlogClient(), BenchmarkOperation.CREATE_POST, 2, 20, 1);

        assertThat(journal.pendingOperations()).isEmpty();
    }

    @Test
    void failedWritesStayPendingWithTheirKeys() throws Exception {
        FakeBlogClient client = new FakeBlogClient();
        client.failEvery = 2;

        runner.run(client, BenchmarkOperation.CREATE_COMMENT, 1, 10, 1);

        assertThat(journal.pendingOperations()).hasSize(5);
    }
}
