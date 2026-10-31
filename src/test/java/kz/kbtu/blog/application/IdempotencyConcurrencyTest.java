package kz.kbtu.blog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.PostsTableLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyConcurrencyTest extends AbstractIntegrationTest {

    private static final int REQUESTS = 16;

    @Autowired
    PostService postService;

    @Autowired
    CommentService commentService;

    @Autowired
    DataSource dataSource;

    private final ExecutorService executor = Executors.newFixedThreadPool(REQUESTS);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void identicalConcurrentRequestsCreateExactlyOnePost() throws Exception {
        String key = newKey();
        List<Future<PostView>> results = new ArrayList<>();

        try (PostsTableLock lock = new PostsTableLock(dataSource)) {
            for (int i = 0; i < REQUESTS; i++) {
                results.add(executor.submit(() -> postService.create(ALICE, key, "Title", "Content")));
            }
            // One request waits on the table, the rest wait on its key. All are truly in flight.
            awaitBlockedSessions(REQUESTS);
        }

        assertThat(uniqueResults(results)).hasSize(1);
        assertThat(count("posts")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
    }

    @Test
    void identicalConcurrentRequestsCreateExactlyOneComment() throws Exception {
        long postId = postService.create(ALICE, newKey(), "Title", "Content").id();
        String key = newKey();
        List<Future<CommentView>> results = new ArrayList<>();

        try (PostsTableLock lock = new PostsTableLock(dataSource)) {
            for (int i = 0; i < REQUESTS; i++) {
                results.add(executor.submit(() -> commentService.create(BOB, key, postId, "Nice post")));
            }
            awaitBlockedSessions(REQUESTS);
        }

        assertThat(uniqueResults(results)).hasSize(1);
        assertThat(count("comments")).isEqualTo(1);
    }

    @Test
    void waitingRequestTakesOverWhenTheFirstOneDiesMidway() throws Exception {
        String key = newKey();
        Future<PostView> first;
        Future<PostView> second;

        try (PostsTableLock lock = new PostsTableLock(dataSource)) {
            first = executor.submit(() -> postService.create(ALICE, key, "Title", "Content"));
            awaitBlockedSessions(1);
            second = executor.submit(() -> postService.create(ALICE, key, "Title", "Content"));
            awaitBlockedSessions(2);

            // Ends the session of the request that claimed the key, like a backend crash.
            jdbc.queryForObject("""
                    SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock' AND query ILIKE 'insert into posts%'
                    """, Boolean.class);
            awaitBlockedSessions(1);
        }

        assertThatThrownBy(() -> first.get(20, TimeUnit.SECONDS)).hasRootCauseInstanceOf(Exception.class);
        assertThat(second.get(20, TimeUnit.SECONDS).title()).isEqualTo("Title");
        assertThat(count("posts")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
    }

    @Test
    void crashMidwayLeavesNothingBehindAndTheRetrySucceeds() throws Exception {
        String key = newKey();
        Future<PostView> crashed;

        try (PostsTableLock lock = new PostsTableLock(dataSource)) {
            crashed = executor.submit(() -> postService.create(ALICE, key, "Title", "Content"));
            awaitBlockedSessions(1);

            jdbc.queryForObject("""
                    SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Boolean.class);

            assertThatThrownBy(() -> crashed.get(20, TimeUnit.SECONDS)).hasRootCauseInstanceOf(Exception.class);
        }

        assertThat(count("idempotency_records")).isZero();
        assertThat(count("posts")).isZero();

        PostView retried = postService.create(ALICE, key, "Title", "Content");

        assertThat(retried.title()).isEqualTo("Title");
        assertThat(count("posts")).isEqualTo(1);
    }

    private static <T> HashSet<T> uniqueResults(List<Future<T>> results) throws Exception {
        HashSet<T> unique = new HashSet<>();
        for (Future<T> result : results) {
            unique.add(result.get(20, TimeUnit.SECONDS));
        }
        return unique;
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
