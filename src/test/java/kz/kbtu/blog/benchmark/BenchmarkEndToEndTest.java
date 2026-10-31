package kz.kbtu.blog.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.api.grpc.GrpcServerLifecycle;
import kz.kbtu.blog.application.CommentService;
import kz.kbtu.blog.application.PostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** A tiny real benchmark: real clients, real server, real database, real report files. */
class BenchmarkEndToEndTest extends AbstractIntegrationTest {

    private static final int REQUESTS = 40;
    private static final int ITERATIONS = 2;

    @LocalServerPort
    int restPort;

    @Autowired
    GrpcServerLifecycle grpcServer;

    @Autowired
    PostService posts;

    @Autowired
    CommentService comments;

    @TempDir
    Path output;

    @Test
    void producesResultsForEveryProtocolAndOperationWithoutErrors() throws Exception {
        for (int i = 0; i < 5; i++) {
            long postId = posts.create(ALICE, UUID.randomUUID().toString(), "Title", "Content").id();
            comments.create(BOB, UUID.randomUUID().toString(), postId, "Comment");
        }

        BenchmarkMain.runForTest(BenchmarkConfig.parse(
                "--requests=" + REQUESTS, "--write-requests=" + REQUESTS, "--concurrency=2", "--warmup=4",
                "--prewarm-passes=1", "--iterations=" + ITERATIONS, "--sample-size=5",
                "--max-attempts=1", "--attempt-timeout-ms=5000", "--overall-timeout-ms=5000",
                "--rest-url=http://localhost:" + restPort,
                "--grpc-port=" + grpcServer.getPort(),
                "--db-url=" + POSTGRES.getJdbcUrl(),
                "--db-user=" + POSTGRES.getUsername(),
                "--db-password=" + POSTGRES.getPassword(),
                "--output=" + output));

        List<String> raw = Files.readAllLines(output.resolve("raw-runs.csv"));
        int protocols = 2;
        assertThat(raw).hasSize(1 + protocols * BenchmarkOperation.values().length * ITERATIONS);
        assertThat(raw.subList(1, raw.size())).allSatisfy(row -> {
            assertThat(row).contains("," + REQUESTS + "," + REQUESTS + ",0,0.000000,0,");
        });

        // Order alternates: REST goes first in iteration 1, gRPC in iteration 2.
        assertThat(raw).anyMatch(row -> row.startsWith("REST,GetPost,read,2,1,1,"));
        assertThat(raw).anyMatch(row -> row.startsWith("gRPC,GetPost,read,2,2,1,"));

        String summary = Files.readString(output.resolve("summary.md"));
        assertThat(summary)
                .contains("transport", "serialization", "**not**")
                .contains("GetPost", "GetAuthorPosts", "GetComments", "CreatePost", "CreateComment");
        assertThat(Files.readAllLines(output.resolve("summary.csv"))).hasSize(1 + 2 * 5);
        assertThat(Files.readAllLines(output.resolve("serialization-probe.csv"))).hasSize(1 + 4);
        assertThat(Files.readString(output.resolve("pending-operations.log"))).isEmpty();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Long.class))
                .isGreaterThan((long) REQUESTS);
    }
}
