package kz.kbtu.blog.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import kz.kbtu.blog.benchmark.PendingOperationJournal.PendingOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingOperationJournalTest {

    private static final BenchmarkDataset DATASET = new BenchmarkDataset(
            new long[] {1}, new long[] {2}, new long[] {3}, 1, 1, 1);

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("journal.log");
    }

    private static PendingOperation post(String key) {
        return new PendingOperation(key, BenchmarkOperation.CREATE_POST, 7, 0, "bench-1");
    }

    @Test
    void pendingWriteSurvivesAProcessThatNeverShutDown() throws Exception {
        PendingOperationJournal crashed = new PendingOperationJournal(file());
        crashed.recordPending(post("key-1"));
        crashed.recordPending(post("key-2"));
        crashed.recordDone("key-1");
        // No close(): the process died here.

        PendingOperationJournal restarted = new PendingOperationJournal(file());

        assertThat(restarted.pendingOperations()).containsExactly(post("key-2"));
    }

    @Test
    void recoveryResendsWithTheOriginalKey() throws Exception {
        PendingOperationJournal crashed = new PendingOperationJournal(file());
        crashed.recordPending(post("key-1"));
        crashed.recordPending(new PendingOperation("key-2", BenchmarkOperation.CREATE_COMMENT, 8, 55, "-"));

        PendingOperationJournal restarted = new PendingOperationJournal(file());
        Workload workload = new Workload(DATASET, restarted, 20);
        FakeBlogClient client = new FakeBlogClient();

        int recovered = workload.recoverPending(client);

        assertThat(recovered).isEqualTo(2);
        assertThat(client.calls).containsExactly("createPost 7 key-1 bench-1", "createComment 8 key-2 55");
        assertThat(restarted.pendingOperations()).isEmpty();
    }

    @Test
    void lineCutShortByACrashIsIgnored() throws Exception {
        PendingOperationJournal crashed = new PendingOperationJournal(file());
        crashed.recordPending(post("key-1"));
        Files.writeString(file(), "P\tkey-2\tCREATE", StandardOpenOption.APPEND);

        PendingOperationJournal restarted = new PendingOperationJournal(file());

        assertThat(restarted.pendingOperations()).containsExactly(post("key-1"));
    }

    @Test
    void closeRemovesFinishedEntriesButKeepsPendingOnes() throws Exception {
        try (PendingOperationJournal journal = new PendingOperationJournal(file())) {
            journal.recordPending(post("key-1"));
            journal.recordPending(post("key-2"));
            journal.recordDone("key-1");
        }

        assertThat(Files.readAllLines(file())).hasSize(1);
        assertThat(new PendingOperationJournal(file()).pendingOperations()).containsExactly(post("key-2"));
    }
}
