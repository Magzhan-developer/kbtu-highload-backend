package kz.kbtu.blog.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A scripted fake server: each call takes the next outcome, and every key it saw is recorded. */
class RestBlogClientRetryTest {

    private static final String KEY = "6f1c2a44-3a7e-4b1e-9a55-1f2c3d4e5f60";
    private static final String BODY = "{\"postId\":7}";
    private static final RetryPolicy POLICY = new RetryPolicy(3, Duration.ofMillis(300), Duration.ofSeconds(5),
            Duration.ofMillis(1), Duration.ofMillis(5));

    /** Means the fake stalls past the client's timeout before answering. */
    private static final int STALL = -1;

    private final Queue<Integer> outcomes = new ArrayDeque<>();
    private final List<String> keysSeen = Collections.synchronizedList(new ArrayList<>());

    private ExecutorService serverThreads;
    private HttpServer server;
    private RestBlogClient client;

    @BeforeEach
    void start() throws Exception {
        serverThreads = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/", this::answer);
        server.start();
        client = new RestBlogClient("http://localhost:" + server.getAddress().getPort(), POLICY);
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private void answer(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        keysSeen.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        int status;
        synchronized (outcomes) {
            status = outcomes.remove();
        }
        if (status == STALL) {
            try {
                Thread.sleep(POLICY.attemptTimeout().toMillis() * 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
            return;
        }
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void writeRetries503AndReusesTheSameKey() throws Exception {
        outcomes.addAll(List.of(503, 503, 201));

        CallResult result = client.createPost(1, KEY, "Title", "Content");

        assertThat(result.id()).isEqualTo(7);
        assertThat(keysSeen).containsExactly(KEY, KEY, KEY);
    }

    @Test
    void writeRetriesAfterATimeoutAndReusesTheSameKey() throws Exception {
        outcomes.addAll(List.of(STALL, 201));

        CallResult result = client.createPost(1, KEY, "Title", "Content");

        assertThat(result.id()).isEqualTo(7);
        assertThat(keysSeen).containsExactly(KEY, KEY);
    }

    @Test
    void readsRetryToo() throws Exception {
        outcomes.addAll(List.of(504, 200));

        assertThat(client.getPost(7).id()).isEqualTo(7);
        assertThat(keysSeen).hasSize(2);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        outcomes.addAll(List.of(503, 503, 503));

        assertThatThrownBy(() -> client.createPost(1, KEY, "Title", "Content"))
                .isInstanceOfSatisfying(RestBlogClient.RestCallException.class,
                        e -> assertThat(e.status()).isEqualTo(503));
        assertThat(keysSeen).hasSize(3);
    }

    @Test
    void permanentErrorsAreNotRetried() {
        for (int status : List.of(400, 401, 403, 404, 422, 500)) {
            keysSeen.clear();
            outcomes.add(status);

            assertThatThrownBy(() -> client.createPost(1, KEY, "Title", "Content"))
                    .isInstanceOfSatisfying(RestBlogClient.RestCallException.class,
                            e -> assertThat(e.status()).isEqualTo(status));
            assertThat(keysSeen).hasSize(1);
        }
    }

    @Test
    void timeoutIsReportedAsTimeoutWhenAttemptsRunOut() {
        outcomes.addAll(List.of(STALL, STALL, STALL));

        assertThatThrownBy(() -> client.createPost(1, KEY, "Title", "Content"))
                .isInstanceOf(HttpTimeoutException.class);
    }

    @Test
    void payloadSizesAreTheJsonBytes() throws Exception {
        outcomes.add(201);

        CallResult result = client.createPost(1, KEY, "Title", "Content");

        assertThat(result.requestBytes()).isEqualTo("{\"title\":\"Title\",\"content\":\"Content\"}".length());
        assertThat(result.responseBytes()).isEqualTo(BODY.length());
    }
}
