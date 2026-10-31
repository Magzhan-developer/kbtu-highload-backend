package kz.kbtu.blog.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.api.rest.RestTestClient.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class PostApiTest extends AbstractIntegrationTest {

    private static final String ALICE_ID = String.valueOf(ALICE);
    private static final String BOB_ID = String.valueOf(BOB);

    @LocalServerPort
    int port;

    private RestTestClient api;

    @BeforeEach
    void createClient() {
        api = new RestTestClient(port);
    }

    @Test
    void createReturns201WithLocationAndTheFullPost() throws Exception {
        Response response = api.post("/api/v1/posts", ALICE_ID, "{\"title\":\"Title\",\"content\":\"Content\"}");

        assertThat(response.status()).isEqualTo(201);
        JsonNode post = response.json();
        long postId = post.get("postId").asLong();
        assertThat(response.raw().headers().firstValue("Location")).hasValue("/api/v1/posts/" + postId);
        assertThat(post.get("authorId").asLong()).isEqualTo(ALICE);
        assertThat(post.get("title").asText()).isEqualTo("Title");
        assertThat(post.get("content").asText()).isEqualTo("Content");
        // Timestamps must be ISO-8601 strings, not numbers.
        assertThat(post.get("createdAt").isTextual()).isTrue();
        assertThat(Instant.parse(post.get("createdAt").asText())).isNotNull();
        assertThat(post.get("updatedAt").asText()).isEqualTo(post.get("createdAt").asText());
    }

    @Test
    void readsNeedNoUserHeaderAndReturnWhatWasCreated() throws Exception {
        JsonNode created = createPost(ALICE_ID, "Title");

        Response response = api.get("/api/v1/posts/" + created.get("postId").asLong());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json()).isEqualTo(created);
    }

    @Test
    void unicodeSurvivesTheRoundTrip() throws Exception {
        String body = RestTestClient.json(Map.of("title", "Қазақша 日本語", "content", "Сәлем 🌍"));

        Response created = api.post("/api/v1/posts", ALICE_ID, body);
        Response read = api.get("/api/v1/posts/" + created.json().get("postId").asLong());

        assertThat(read.json().get("title").asText()).isEqualTo("Қазақша 日本語");
        assertThat(read.json().get("content").asText()).isEqualTo("Сәлем 🌍");
    }

    @Test
    void updateChangesThePost() throws Exception {
        long postId = createPost(ALICE_ID, "Title").get("postId").asLong();

        Response response = api.put("/api/v1/posts/" + postId, ALICE_ID,
                "{\"title\":\"New title\",\"content\":\"New content\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("title").asText()).isEqualTo("New title");
        assertThat(api.get("/api/v1/posts/" + postId).json()).isEqualTo(response.json());
    }

    @Test
    void deleteReturns204ThenThePostIsGone() throws Exception {
        long postId = createPost(ALICE_ID, "Title").get("postId").asLong();

        Response deleted = api.delete("/api/v1/posts/" + postId, ALICE_ID);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleted.raw().body()).isEmpty();
        assertThat(api.get("/api/v1/posts/" + postId).status()).isEqualTo(404);
    }

    @Test
    void authorFeedPagesThroughAllPostsWithCursors() throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(0, createPost(ALICE_ID, "Post " + i).get("postId").asLong());
        }
        createPost(BOB_ID, "Other author");

        List<Long> actual = new ArrayList<>();
        String path = "/api/v1/users/" + ALICE + "/posts?limit=2";
        JsonNode page = api.get(path).json();
        page.get("items").forEach(post -> actual.add(post.get("postId").asLong()));
        while (page.has("nextCursor")) {
            page = api.get(path + "&cursor=" + page.get("nextCursor").asText()).json();
            page.get("items").forEach(post -> actual.add(post.get("postId").asLong()));
        }

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void lastPageOmitsNextCursor() throws Exception {
        createPost(ALICE_ID, "Only post");

        Response response = api.get("/api/v1/users/" + ALICE + "/posts");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().has("nextCursor")).isFalse();
    }

    private JsonNode createPost(String userId, String title) throws Exception {
        String body = RestTestClient.json(Map.of("title", title, "content", "Content"));
        Response response = api.post("/api/v1/posts", userId, body);
        assertThat(response.status()).isEqualTo(201);
        return response.json();
    }
}
