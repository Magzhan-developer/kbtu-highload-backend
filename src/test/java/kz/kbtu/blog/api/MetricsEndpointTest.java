package kz.kbtu.blog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.api.grpc.GrpcServerLifecycle;
import kz.kbtu.blog.api.grpc.GrpcTestClient;
import kz.kbtu.blog.grpc.v1.CreatePostRequest;
import kz.kbtu.blog.grpc.v1.GetPostRequest;
import kz.kbtu.blog.grpc.v1.PostResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Reads the real Prometheus endpoint after real calls, as Prometheus itself would. */
@AutoConfigureObservability(tracing = false)
class MetricsEndpointTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    GrpcServerLifecycle grpcServer;

    @Test
    void restAndGrpcAreBothMeasuredWithComparableHistograms() throws Exception {
        try (GrpcTestClient grpc = new GrpcTestClient(grpcServer.getPort())) {
            String key = newKey();
            CreatePostRequest create = CreatePostRequest.newBuilder()
                    .setIdempotencyKey(key).setTitle("Title").setContent("Content").build();
            PostResponse post = grpc.as(ALICE).createPost(create);
            grpc.as(ALICE).createPost(create);
            catchThrowableOfType(StatusRuntimeException.class, () -> grpc.as(ALICE).createPost(
                    create.toBuilder().setContent("Different").build()));
            catchThrowableOfType(StatusRuntimeException.class, () -> grpc.anonymous().getPost(
                    GetPostRequest.newBuilder().setPostId(UNKNOWN_ID).build()));
            get("/api/v1/posts/" + post.getPostId());
        }

        String metrics = get("/actuator/prometheus");

        assertThat(metrics).contains("http_server_requests_seconds_bucket");
        assertThat(metrics).containsPattern(
                "http_server_requests_seconds_count\\{.*status=\"200\".*uri=\"/api/v1/posts/\\{postId\\}\"");
        assertThat(metrics).contains("grpc_server_requests_seconds_bucket");
        assertThat(metrics).containsPattern(
                "grpc_server_requests_seconds_count\\{.*method=\"CreatePost\".*outcome=\"SUCCESS\".*status=\"OK\"");
        assertThat(metrics).containsPattern(
                "grpc_server_requests_seconds_count\\{.*method=\"GetPost\".*outcome=\"ERROR\".*status=\"NOT_FOUND\"");

        assertThat(counter(metrics, "created")).isGreaterThanOrEqualTo(1);
        assertThat(counter(metrics, "replayed")).isGreaterThanOrEqualTo(1);
        assertThat(counter(metrics, "conflict")).isGreaterThanOrEqualTo(1);

        // jvm_gc_pause_seconds is not listed: it only exists after the first garbage collection.
        assertThat(metrics).contains("jvm_memory_used_bytes", "jvm_gc_memory_allocated_bytes", "process_cpu_usage",
                "jvm_threads_live_threads", "process_uptime_seconds", "hikaricp_connections_active");
    }

    private static double counter(String metrics, String outcome) {
        return metrics.lines()
                .filter(line -> line.startsWith("blog_idempotency_outcomes_total{")
                        && line.contains("outcome=\"" + outcome + "\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst()
                .orElse(-1);
    }

    private String get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }
    }
}
