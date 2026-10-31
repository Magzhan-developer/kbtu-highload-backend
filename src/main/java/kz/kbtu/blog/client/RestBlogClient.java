package kz.kbtu.blog.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public final class RestBlogClient implements BlogClient {

    /** A response with an error status. */
    public static final class RestCallException extends RuntimeException {

        private final int status;

        RestCallException(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final int SERVICE_UNAVAILABLE = 503;
    private static final int GATEWAY_TIMEOUT = 504;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final RetryExecutor retries;
    private final LongAdder attempts = new LongAdder();

    public RestBlogClient(String baseUrl, RetryPolicy policy) {
        // HTTP/1.1 keep-alive: concurrent calls each need their own pooled connection.
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.baseUrl = baseUrl;
        this.retries = new RetryExecutor(policy);
    }

    @Override
    public CallResult createPost(long userId, String idempotencyKey, String title, String content) throws Exception {
        byte[] body = mapper.writeValueAsBytes(Map.of("title", title, "content", content));
        return send(post("/api/v1/posts", userId, idempotencyKey, body), body.length, "postId");
    }

    @Override
    public CallResult createComment(long userId, String idempotencyKey, long postId, String content) throws Exception {
        byte[] body = mapper.writeValueAsBytes(Map.of("content", content));
        return send(post("/api/v1/posts/" + postId + "/comments", userId, idempotencyKey, body), body.length, "commentId");
    }

    @Override
    public CallResult getPost(long postId) throws Exception {
        return send(get("/api/v1/posts/" + postId), 0, "postId");
    }

    @Override
    public CallResult getAuthorPosts(long authorId, int limit) throws Exception {
        return send(get("/api/v1/users/" + authorId + "/posts?limit=" + limit), 0, "postId");
    }

    @Override
    public CallResult getComments(long postId, int limit) throws Exception {
        return send(get("/api/v1/posts/" + postId + "/comments?limit=" + limit), 0, "commentId");
    }

    @Override
    public long attemptCount() {
        return attempts.sum();
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
    }

    private HttpRequest.Builder post(String path, long userId, String idempotencyKey, byte[] body) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userId))
                .header("Idempotency-Key", idempotencyKey);
    }

    private CallResult send(HttpRequest.Builder request, int requestBytes, String idField) throws Exception {
        byte[] body = retries.execute(timeout -> attempt(request, timeout), RestBlogClient::isRetryable);
        return new CallResult(readId(mapper.readTree(body), idField), requestBytes, body.length);
    }

    private byte[] attempt(HttpRequest.Builder request, Duration timeout) throws Exception {
        attempts.increment();
        HttpResponse<byte[]> response = http.send(
                request.copy().timeout(timeout).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new RestCallException(response.statusCode(), new String(response.body()));
        }
        return response.body();
    }

    // IOException covers timeouts, refused connections and connections lost mid-response.
    private static boolean isRetryable(Exception e) {
        if (e instanceof RestCallException failure) {
            return failure.status() == SERVICE_UNAVAILABLE || failure.status() == GATEWAY_TIMEOUT;
        }
        return e instanceof IOException;
    }

    private static long readId(JsonNode json, String idField) {
        JsonNode items = json.get("items");
        if (items == null) {
            return json.get(idField).asLong();
        }
        return items.isEmpty() ? 0 : items.get(0).get(idField).asLong();
    }
}
