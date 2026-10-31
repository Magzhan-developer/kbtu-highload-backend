package kz.kbtu.blog.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.sql.DataSource;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.PostsTableLock;
import kz.kbtu.blog.api.grpc.GrpcServerLifecycle;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The hard case for any client: the call times out, but the server finishes the work anyway.
 * These run the real clients against the real server, with a database lock stalling the write.
 */
class TimeoutThenRetryTest extends AbstractIntegrationTest {

    private static final RetryPolicy PATIENT = new RetryPolicy(1, Duration.ofSeconds(10), Duration.ofSeconds(10),
            Duration.ofMillis(1), Duration.ofMillis(1));
    private static final RetryPolicy ONE_SHORT_ATTEMPT = new RetryPolicy(1, Duration.ofMillis(800),
            Duration.ofMillis(800), Duration.ofMillis(1), Duration.ofMillis(1));
    private static final RetryPolicy RETRIES_WHILE_STALLED = new RetryPolicy(10, Duration.ofMillis(300),
            Duration.ofSeconds(15), Duration.ofMillis(5), Duration.ofMillis(20));

    @LocalServerPort
    int restPort;

    @Autowired
    GrpcServerLifecycle grpcServer;

    @Autowired
    DataSource dataSource;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    private GrpcBlogClient grpcClient(RetryPolicy policy) {
        return new GrpcBlogClient("localhost", grpcServer.getPort(), policy);
    }

    private RestBlogClient restClient(RetryPolicy policy) {
        return new RestBlogClient("http://localhost:" + restPort, policy);
    }

    @Test
    void grpcDeadlineExpiresButTheServerCommitsAndTheRetryFindsIt() throws Exception {
        timeoutThenRetry(this::grpcClient);
    }

    @Test
    void restTimeoutExpiresButTheServerCommitsAndTheRetryFindsIt() throws Exception {
        timeoutThenRetry(this::restClient);
    }

    @Test
    void grpcRetriesDuringTheStallCreateOnlyOnePost() throws Exception {
        retriesDuringStall(this::grpcClient);
    }

    @Test
    void restRetriesDuringTheStallCreateOnlyOnePost() throws Exception {
        retriesDuringStall(this::restClient);
    }

    @Test
    void identicalRequestsOverBothProtocolsAtOnceCreateOnePost() throws Exception {
        warmUp(this::grpcClient);
        warmUp(this::restClient);
        String key = newKey();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (BlogClient grpc = grpcClient(PATIENT); BlogClient rest = restClient(PATIENT)) {
            List<Future<CallResult>> calls = new java.util.ArrayList<>();
            try (PostsTableLock lock = new PostsTableLock(dataSource)) {
                for (int i = 0; i < 4; i++) {
                    calls.add(pool.submit(() -> grpc.createPost(ALICE, key, "Same", "Same")));
                    calls.add(pool.submit(() -> rest.createPost(ALICE, key, "Same", "Same")));
                }
                awaitBlockedSessions(8);
            }
            for (Future<CallResult> call : calls) {
                assertThat(call.get(15, TimeUnit.SECONDS).id()).isEqualTo(calls.get(0).get().id());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(countPosts("Same")).isEqualTo(1);
    }

    @Test
    void grpcReportsALostDatabaseSessionAsRetryable() throws Exception {
        warmUp(this::grpcClient);
        assertThatThrownBy(() -> createWhileTheDatabaseSessionDies(this::grpcClient))
                .hasRootCauseInstanceOf(StatusRuntimeException.class)
                .rootCause()
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAVAILABLE));
    }

    @Test
    void restReportsALostDatabaseSessionAsRetryable() throws Exception {
        warmUp(this::restClient);
        assertThatThrownBy(() -> createWhileTheDatabaseSessionDies(this::restClient))
                .hasRootCauseInstanceOf(RestBlogClient.RestCallException.class)
                .rootCause()
                .satisfies(e -> assertThat(((RestBlogClient.RestCallException) e).status()).isEqualTo(503));
    }

    private void createWhileTheDatabaseSessionDies(Function<RetryPolicy, BlogClient> clients) throws Exception {
        try (BlogClient client = clients.apply(PATIENT);
                PostsTableLock lock = new PostsTableLock(dataSource)) {
            Future<CallResult> call = executor.submit(
                    () -> client.createPost(ALICE, newKey(), "Doomed", "Content"));
            awaitBlockedSessions(1);
            jdbc.queryForObject("""
                    SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Boolean.class);
            call.get(15, TimeUnit.SECONDS);
        }
    }

    private void timeoutThenRetry(Function<RetryPolicy, BlogClient> clients) throws Exception {
        warmUp(clients);
        String key = newKey();
        try (BlogClient impatient = clients.apply(ONE_SHORT_ATTEMPT);
                PostsTableLock lock = new PostsTableLock(dataSource)) {
            assertThatThrownBy(() -> impatient.createPost(ALICE, key, "Lost response", "Content"))
                    .satisfies(TimeoutThenRetryTest::assertIsTimeout);
            // The client has given up, yet the request is still alive inside the server.
            awaitBlockedSessions(1);
        }

        // The client saw only a failure, but the post exists. This is why retries need the key.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> countPosts("Lost response") == 1);

        try (BlogClient retrying = clients.apply(PATIENT)) {
            CallResult result = retrying.createPost(ALICE, key, "Lost response", "Content");

            assertThat(result.id()).isPositive();
        }
        assertThat(countPosts("Lost response")).isEqualTo(1);
    }

    private void retriesDuringStall(Function<RetryPolicy, BlogClient> clients) throws Exception {
        warmUp(clients);
        String key = newKey();
        try (BlogClient client = clients.apply(RETRIES_WHILE_STALLED)) {
            Future<CallResult> call;
            try (PostsTableLock lock = new PostsTableLock(dataSource)) {
                call = executor.submit(() -> client.createPost(ALICE, key, "Stalled", "Content"));
                // At least two attempts in flight: the first holds the key, a retry waits on it.
                awaitBlockedSessions(2);
            }
            assertThat(call.get(15, TimeUnit.SECONDS).id()).isPositive();
        }
        assertThat(countPosts("Stalled")).isEqualTo(1);
    }

    /** The first call is slow while code warms up, and a short timeout must not hit that instead. */
    private void warmUp(Function<RetryPolicy, BlogClient> clients) throws Exception {
        try (BlogClient client = clients.apply(PATIENT)) {
            client.createPost(ALICE, UUID.randomUUID().toString(), "Warm up", "Content");
        }
    }

    private static void assertIsTimeout(Throwable failure) {
        if (failure instanceof StatusRuntimeException grpcFailure) {
            assertThat(grpcFailure.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        } else {
            assertThat(failure).isInstanceOf(HttpTimeoutException.class);
        }
    }

    private long countPosts(String title) {
        return jdbc.queryForObject("SELECT count(*) FROM posts WHERE title = ?", Long.class, title);
    }
}
