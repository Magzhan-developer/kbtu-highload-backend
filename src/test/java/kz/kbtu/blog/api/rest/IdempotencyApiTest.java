package kz.kbtu.blog.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.api.grpc.GrpcServerLifecycle;
import kz.kbtu.blog.api.grpc.GrpcTestClient;
import kz.kbtu.blog.api.rest.RestTestClient.Response;
import kz.kbtu.blog.grpc.v1.CreateCommentRequest;
import kz.kbtu.blog.grpc.v1.CreatePostRequest;
import kz.kbtu.blog.grpc.v1.PostResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class IdempotencyApiTest extends AbstractIntegrationTest {

    private static final String POST_BODY = "{\"title\":\"Title\",\"content\":\"Content\"}";
    private static final String ALICE_ID = String.valueOf(ALICE);

    @LocalServerPort
    int restPort;

    @Autowired
    GrpcServerLifecycle grpcServer;

    private RestTestClient rest;
    private GrpcTestClient grpc;

    @BeforeEach
    void connect() {
        rest = new RestTestClient(restPort);
        grpc = new GrpcTestClient(grpcServer.getPort());
    }

    @AfterEach
    void disconnect() {
        grpc.close();
    }

    @Test
    void restRetryReturnsTheSame201AndBody() throws Exception {
        String key = newKey();

        Response first = rest.post("/api/v1/posts", ALICE_ID, key, POST_BODY);
        Response second = rest.post("/api/v1/posts", ALICE_ID, key, POST_BODY);

        assertThat(second.status()).isEqualTo(201);
        assertThat(second.json()).isEqualTo(first.json());
        assertThat(second.raw().headers().firstValue("Location")).isEqualTo(first.raw().headers().firstValue("Location"));
        assertThat(postCount()).isEqualTo(1);
    }

    @Test
    void restRejectsAReusedKeyWith422() throws Exception {
        String key = newKey();
        rest.post("/api/v1/posts", ALICE_ID, key, POST_BODY);

        Response response = rest.post("/api/v1/posts", ALICE_ID, key, "{\"title\":\"Title\",\"content\":\"Other\"}");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("ALREADY_EXISTS");
    }

    @Test
    void restRequiresAValidKey() throws Exception {
        assertThat(rest.post("/api/v1/posts", ALICE_ID, null, POST_BODY).status()).isEqualTo(400);
        assertThat(rest.post("/api/v1/posts", ALICE_ID, "not-a-uuid", POST_BODY).status()).isEqualTo(400);
        assertThat(postCount()).isZero();
    }

    @Test
    void grpcRetryReturnsTheSameResponse() {
        CreatePostRequest request = validPost(newKey()).build();

        PostResponse first = grpc.as(ALICE).createPost(request);
        PostResponse second = grpc.as(ALICE).createPost(request);

        assertThat(second).isEqualTo(first);
        assertThat(postCount()).isEqualTo(1);
    }

    @Test
    void grpcRejectsAReusedKeyWithAlreadyExists() {
        String key = newKey();
        grpc.as(ALICE).createPost(validPost(key).build());

        StatusRuntimeException error = catchThrowableOfType(StatusRuntimeException.class,
                () -> grpc.as(ALICE).createPost(validPost(key).setContent("Other").build()));

        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
    }

    @Test
    void grpcRequiresAValidKey() {
        StatusRuntimeException missing = catchThrowableOfType(StatusRuntimeException.class,
                () -> grpc.as(ALICE).createPost(CreatePostRequest.newBuilder()
                        .setTitle("Title").setContent("Content").build()));
        StatusRuntimeException malformed = catchThrowableOfType(StatusRuntimeException.class,
                () -> grpc.as(ALICE).createPost(validPost("not-a-uuid").build()));

        assertThat(missing.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(malformed.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void keyFirstUsedOverRestReplaysOverGrpc() throws Exception {
        String key = newKey();
        JsonNode viaRest = rest.post("/api/v1/posts", ALICE_ID, key, POST_BODY).json();

        PostResponse viaGrpc = grpc.as(ALICE).createPost(validPost(key).build());

        assertThat(viaGrpc.getPostId()).isEqualTo(viaRest.get("postId").asLong());
        assertThat(postCount()).isEqualTo(1);
    }

    @Test
    void keyFirstUsedOverGrpcReplaysOverRest() throws Exception {
        String key = newKey();
        long postId = grpc.as(ALICE).createPost(validPost(key).build()).getPostId();
        String commentKey = newKey();
        long commentId = grpc.as(BOB).createComment(CreateCommentRequest.newBuilder()
                .setIdempotencyKey(commentKey).setPostId(postId).setContent("Hello").build()).getCommentId();

        Response post = rest.post("/api/v1/posts", ALICE_ID, key, POST_BODY);
        Response comment = rest.post("/api/v1/posts/" + postId + "/comments", String.valueOf(BOB), commentKey,
                "{\"content\":\"Hello\"}");

        assertThat(post.json().get("postId").asLong()).isEqualTo(postId);
        assertThat(comment.json().get("commentId").asLong()).isEqualTo(commentId);
        assertThat(postCount()).isEqualTo(1);
    }

    private static CreatePostRequest.Builder validPost(String key) {
        return CreatePostRequest.newBuilder().setIdempotencyKey(key).setTitle("Title").setContent("Content");
    }

    private long postCount() {
        return jdbc.queryForObject("SELECT count(*) FROM posts", Long.class);
    }
}
