package kz.kbtu.blog.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.api.rest.RestTestClient.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class CommentApiTest extends AbstractIntegrationTest {

    private static final String ALICE_ID = String.valueOf(ALICE);
    private static final String BOB_ID = String.valueOf(BOB);

    @LocalServerPort
    int port;

    private RestTestClient api;
    private long postId;

    @BeforeEach
    void createPost() throws Exception {
        api = new RestTestClient(port);
        postId = api.post("/api/v1/posts", ALICE_ID, "{\"title\":\"Title\",\"content\":\"Content\"}")
                .json().get("postId").asLong();
    }

    @Test
    void createReturns201AndTheCommentIsListed() throws Exception {
        Response created = api.post("/api/v1/posts/" + postId + "/comments", BOB_ID, "{\"content\":\"Nice post\"}");

        assertThat(created.status()).isEqualTo(201);
        JsonNode comment = created.json();
        assertThat(created.raw().headers().firstValue("Location"))
                .hasValue("/api/v1/comments/" + comment.get("commentId").asLong());
        assertThat(comment.get("postId").asLong()).isEqualTo(postId);
        assertThat(comment.get("userId").asLong()).isEqualTo(BOB);

        Response list = api.get("/api/v1/posts/" + postId + "/comments");

        assertThat(list.status()).isEqualTo(200);
        assertThat(list.json().get("items")).hasSize(1);
        assertThat(list.json().get("items").get(0)).isEqualTo(comment);
    }

    @Test
    void commentsPageThroughWithCursors() throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Response created = api.post("/api/v1/posts/" + postId + "/comments", BOB_ID, "{\"content\":\"Comment\"}");
            expected.add(0, created.json().get("commentId").asLong());
        }

        List<Long> actual = new ArrayList<>();
        String path = "/api/v1/posts/" + postId + "/comments?limit=2";
        JsonNode page = api.get(path).json();
        page.get("items").forEach(comment -> actual.add(comment.get("commentId").asLong()));
        while (page.has("nextCursor")) {
            page = api.get(path + "&cursor=" + page.get("nextCursor").asText()).json();
            page.get("items").forEach(comment -> actual.add(comment.get("commentId").asLong()));
        }

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void updateAndDeleteAComment() throws Exception {
        long commentId = api.post("/api/v1/posts/" + postId + "/comments", BOB_ID, "{\"content\":\"Nice post\"}")
                .json().get("commentId").asLong();

        Response updated = api.put("/api/v1/comments/" + commentId, BOB_ID, "{\"content\":\"Edited\"}");

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("content").asText()).isEqualTo("Edited");

        assertThat(api.delete("/api/v1/comments/" + commentId, BOB_ID).status()).isEqualTo(204);
        assertThat(api.get("/api/v1/posts/" + postId + "/comments").json().get("items")).isEmpty();
    }

    @Test
    void commentingOnAMissingPostReturns404() throws Exception {
        Response response = api.post("/api/v1/posts/" + UNKNOWN_ID + "/comments", BOB_ID, "{\"content\":\"Hello\"}");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void onlyTheCommentAuthorCanChangeIt() throws Exception {
        long commentId = api.post("/api/v1/posts/" + postId + "/comments", BOB_ID, "{\"content\":\"Nice post\"}")
                .json().get("commentId").asLong();

        assertThat(api.put("/api/v1/comments/" + commentId, ALICE_ID, "{\"content\":\"Hacked\"}").status())
                .isEqualTo(403);
        assertThat(api.delete("/api/v1/comments/" + commentId, ALICE_ID).status()).isEqualTo(403);
    }
}
