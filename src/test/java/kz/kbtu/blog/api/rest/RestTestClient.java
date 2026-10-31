package kz.kbtu.blog.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Sends real HTTP requests, so tests cover JSON encoding and Tomcat as well as the controllers. */
class RestTestClient {

    record Response(int status, JsonNode json, HttpResponse<String> raw) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    RestTestClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    Response get(String path) throws Exception {
        return send("GET", path, null, null, "application/json");
    }

    /** Sends a fresh idempotency key, for tests that are not about idempotency. */
    Response post(String path, String userId, String body) throws Exception {
        return post(path, userId, UUID.randomUUID().toString(), body);
    }

    /** A null key sends no Idempotency-Key header. */
    Response post(String path, String userId, String idempotencyKey, String body) throws Exception {
        return exchange("POST", path, userId, idempotencyKey, body, "application/json");
    }

    Response put(String path, String userId, String body) throws Exception {
        return send("PUT", path, userId, body, "application/json");
    }

    Response delete(String path, String userId) throws Exception {
        return send("DELETE", path, userId, null, "application/json");
    }

    Response send(String method, String path, String userId, String body, String contentType) throws Exception {
        return exchange(method, path, userId, UUID.randomUUID().toString(), body, contentType);
    }

    private Response exchange(
            String method, String path, String userId, String idempotencyKey, String body, String contentType)
            throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, publisher)
                .header("Content-Type", contentType);
        if (userId != null) {
            request.header("X-User-Id", userId);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isEmpty() ? null : MAPPER.readTree(response.body());
        return new Response(response.statusCode(), json, response);
    }

    static String json(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }
}
