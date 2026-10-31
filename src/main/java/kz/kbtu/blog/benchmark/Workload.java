package kz.kbtu.blog.benchmark;

import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import kz.kbtu.blog.benchmark.PendingOperationJournal.PendingOperation;
import kz.kbtu.blog.client.BlogClient;
import kz.kbtu.blog.client.CallResult;

/** Turns an operation into a concrete call. The same random source yields the same targets. */
final class Workload {

    /** One request. Everything outside {@code call} happens outside the latency timer. */
    record PreparedCall(Callable<CallResult> call, Runnable onSuccess) {
    }

    // Same sizes as scripts/seed-dataset.sql, so new rows resemble existing ones.
    static final String POST_CONTENT = "x".repeat(1_000);
    static final String COMMENT_CONTENT = "x".repeat(200);

    private final BenchmarkDataset dataset;
    private final PendingOperationJournal journal;
    private final int pageLimit;
    private final AtomicLong sequence = new AtomicLong();

    Workload(BenchmarkDataset dataset, PendingOperationJournal journal, int pageLimit) {
        this.dataset = dataset;
        this.journal = journal;
        this.pageLimit = pageLimit;
    }

    PreparedCall prepare(BlogClient client, BenchmarkOperation operation, SplittableRandom random) {
        return switch (operation) {
            case GET_POST -> {
                long postId = pick(dataset.postIds(), random);
                yield read(() -> client.getPost(postId));
            }
            case GET_AUTHOR_POSTS -> {
                long authorId = pick(dataset.authorIds(), random);
                yield read(() -> client.getAuthorPosts(authorId, pageLimit));
            }
            case GET_COMMENTS -> {
                long postId = pick(dataset.postIdsWithComments(), random);
                yield read(() -> client.getComments(postId, pageLimit));
            }
            case CREATE_POST -> prepareCreatePost(client, random);
            case CREATE_COMMENT -> prepareCreateComment(client, random);
        };
    }

    private PreparedCall prepareCreatePost(BlogClient client, SplittableRandom random) {
        long userId = pick(dataset.authorIds(), random);
        String title = "bench-" + String.format("%012d", sequence.incrementAndGet());
        PendingOperation pending = new PendingOperation(
                UUID.randomUUID().toString(), BenchmarkOperation.CREATE_POST, userId, 0, title);
        journal.recordPending(pending);
        return write(pending, () -> client.createPost(userId, pending.key(), title, POST_CONTENT));
    }

    private PreparedCall prepareCreateComment(BlogClient client, SplittableRandom random) {
        long userId = pick(dataset.authorIds(), random);
        long postId = pick(dataset.postIds(), random);
        PendingOperation pending = new PendingOperation(
                UUID.randomUUID().toString(), BenchmarkOperation.CREATE_COMMENT, userId, postId, "-");
        journal.recordPending(pending);
        return write(pending, () -> client.createComment(userId, pending.key(), postId, COMMENT_CONTENT));
    }

    /** Finishes writes left by an earlier run. Keys work on any protocol, so one client is enough. */
    int recoverPending(BlogClient client) throws Exception {
        int recovered = 0;
        for (PendingOperation pending : journal.pendingOperations()) {
            if (pending.operation() == BenchmarkOperation.CREATE_POST) {
                client.createPost(pending.userId(), pending.key(), pending.title(), POST_CONTENT);
            } else {
                client.createComment(pending.userId(), pending.key(), pending.postId(), COMMENT_CONTENT);
            }
            journal.recordDone(pending.key());
            recovered++;
        }
        return recovered;
    }

    private static PreparedCall read(Callable<CallResult> call) {
        return new PreparedCall(call, () -> {
        });
    }

    private PreparedCall write(PendingOperation pending, Callable<CallResult> call) {
        return new PreparedCall(call, () -> journal.recordDone(pending.key()));
    }

    private static long pick(long[] ids, SplittableRandom random) {
        return ids[random.nextInt(ids.length)];
    }
}
