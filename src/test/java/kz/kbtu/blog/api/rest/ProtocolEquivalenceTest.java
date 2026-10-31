package kz.kbtu.blog.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Timestamp;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.api.grpc.GrpcServerLifecycle;
import kz.kbtu.blog.api.grpc.GrpcTestClient;
import kz.kbtu.blog.api.rest.RestTestClient.Response;
import kz.kbtu.blog.grpc.v1.CommentResponse;
import kz.kbtu.blog.grpc.v1.CreateCommentRequest;
import kz.kbtu.blog.grpc.v1.CreatePostRequest;
import kz.kbtu.blog.grpc.v1.DeletePostRequest;
import kz.kbtu.blog.grpc.v1.GetAuthorPostsRequest;
import kz.kbtu.blog.grpc.v1.GetCommentsRequest;
import kz.kbtu.blog.grpc.v1.GetPostRequest;
import kz.kbtu.blog.grpc.v1.PostListResponse;
import kz.kbtu.blog.grpc.v1.PostResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Both protocols share one database, so anything written through one must read the same through the other. */
class ProtocolEquivalenceTest extends AbstractIntegrationTest {

    private static final String VALID_POST = "{\"title\":\"Title\",\"content\":\"Content\"}";

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
    void postCreatedOverRestReadsTheSameOverGrpc() throws Exception {
        JsonNode viaRest = rest.post("/api/v1/posts", String.valueOf(ALICE), VALID_POST).json();

        PostResponse viaGrpc = grpc.anonymous()
                .getPost(GetPostRequest.newBuilder().setPostId(viaRest.get("postId").asLong()).build());

        assertSamePost(viaRest, viaGrpc);
    }

    @Test
    void postCreatedOverGrpcReadsTheSameOverRest() throws Exception {
        PostResponse viaGrpc = grpc.as(ALICE).createPost(
                CreatePostRequest.newBuilder().setIdempotencyKey(newKey()).setTitle("Title").setContent("Content").build());

        JsonNode viaRest = rest.get("/api/v1/posts/" + viaGrpc.getPostId()).json();

        assertSamePost(viaRest, viaGrpc);
    }

    @Test
    void commentListsMatch() throws Exception {
        long postId = rest.post("/api/v1/posts", String.valueOf(ALICE), VALID_POST).json().get("postId").asLong();
        grpc.as(BOB).createComment(CreateCommentRequest.newBuilder().setIdempotencyKey(newKey()).setPostId(postId).setContent("One").build());
        rest.post("/api/v1/posts/" + postId + "/comments", String.valueOf(BOB), "{\"content\":\"Two\"}");

        JsonNode viaRest = rest.get("/api/v1/posts/" + postId + "/comments").json().get("items");
        var viaGrpc = grpc.anonymous()
                .getComments(GetCommentsRequest.newBuilder().setPostId(postId).build()).getItemsList();

        assertThat(viaRest).hasSize(2);
        assertThat(viaGrpc).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertSameComment(viaRest.get(i), viaGrpc.get(i));
        }
    }

    @Test
    void feedPagesMatchAndCursorsWorkAcrossProtocols() throws Exception {
        for (int i = 0; i < 3; i++) {
            rest.post("/api/v1/posts", String.valueOf(ALICE), VALID_POST);
        }

        JsonNode restPage = rest.get("/api/v1/users/" + ALICE + "/posts?limit=2").json();
        PostListResponse grpcPage = grpc.anonymous().getAuthorPosts(
                GetAuthorPostsRequest.newBuilder().setAuthorId(ALICE).setLimit(2).build());

        assertThat(grpcPage.getNextCursor()).isEqualTo(restPage.get("nextCursor").asText());

        // A cursor from REST must continue the same list over gRPC.
        PostListResponse secondPage = grpc.anonymous().getAuthorPosts(GetAuthorPostsRequest.newBuilder()
                .setAuthorId(ALICE).setLimit(2).setCursor(restPage.get("nextCursor").asText()).build());

        assertThat(secondPage.getItemsList()).hasSize(1);
        assertThat(secondPage.getNextCursor()).isEmpty();
    }

    @Test
    void sameFailureProducesTheSameCodeOnBothProtocols() throws Exception {
        long postId = rest.post("/api/v1/posts", String.valueOf(ALICE), VALID_POST).json().get("postId").asLong();
        String alice = String.valueOf(ALICE);
        String bob = String.valueOf(BOB);

        assertSameCode("UNAUTHENTICATED",
                rest.post("/api/v1/posts", null, VALID_POST),
                () -> grpc.anonymous().createPost(validPost()));
        assertSameCode("UNAUTHENTICATED",
                rest.post("/api/v1/posts", String.valueOf(UNKNOWN_USER), VALID_POST),
                () -> grpc.as(UNKNOWN_USER).createPost(validPost()));
        assertSameCode("PERMISSION_DENIED",
                rest.delete("/api/v1/posts/" + postId, bob),
                () -> grpc.as(BOB).deletePost(DeletePostRequest.newBuilder().setPostId(postId).build()));
        assertSameCode("NOT_FOUND",
                rest.get("/api/v1/posts/" + UNKNOWN_ID),
                () -> grpc.anonymous().getPost(GetPostRequest.newBuilder().setPostId(UNKNOWN_ID).build()));
        assertSameCode("NOT_FOUND",
                rest.post("/api/v1/posts/" + UNKNOWN_ID + "/comments", bob, "{\"content\":\"Hi\"}"),
                () -> grpc.as(BOB).createComment(
                        CreateCommentRequest.newBuilder().setIdempotencyKey(newKey()).setPostId(UNKNOWN_ID).setContent("Hi").build()));
        assertSameCode("INVALID_ARGUMENT",
                rest.post("/api/v1/posts", alice, "{\"title\":\"\",\"content\":\"Content\"}"),
                () -> grpc.as(ALICE).createPost(CreatePostRequest.newBuilder().setIdempotencyKey(newKey()).setContent("Content").build()));
        assertSameCode("INVALID_ARGUMENT",
                rest.get("/api/v1/users/" + ALICE + "/posts?limit=101"),
                () -> grpc.anonymous().getAuthorPosts(
                        GetAuthorPostsRequest.newBuilder().setAuthorId(ALICE).setLimit(101).build()));
        assertSameCode("INVALID_ARGUMENT",
                rest.get("/api/v1/users/" + ALICE + "/posts?cursor=garbage"),
                () -> grpc.anonymous().getAuthorPosts(
                        GetAuthorPostsRequest.newBuilder().setAuthorId(ALICE).setCursor("garbage").build()));
    }

    private static CreatePostRequest validPost() {
        return CreatePostRequest.newBuilder().setIdempotencyKey(newKey()).setTitle("Title").setContent("Content").build();
    }

    private static void assertSameCode(String code, Response restResponse, Runnable grpcCall) {
        StatusRuntimeException grpcError = catchThrowableOfType(StatusRuntimeException.class, grpcCall::run);

        assertThat(restResponse.json().get("code").asText()).isEqualTo(code);
        assertThat(grpcError.getStatus().getCode().name()).isEqualTo(code);
        assertThat(grpcError.getStatus().getDescription())
                .isEqualTo(restResponse.json().get("message").asText());
    }

    private static void assertSamePost(JsonNode rest, PostResponse grpc) {
        assertThat(grpc.getPostId()).isEqualTo(rest.get("postId").asLong());
        assertThat(grpc.getAuthorId()).isEqualTo(rest.get("authorId").asLong());
        assertThat(grpc.getTitle()).isEqualTo(rest.get("title").asText());
        assertThat(grpc.getContent()).isEqualTo(rest.get("content").asText());
        assertThat(toInstant(grpc.getCreatedAt())).isEqualTo(Instant.parse(rest.get("createdAt").asText()));
        assertThat(toInstant(grpc.getUpdatedAt())).isEqualTo(Instant.parse(rest.get("updatedAt").asText()));
    }

    private static void assertSameComment(JsonNode rest, CommentResponse grpc) {
        assertThat(grpc.getCommentId()).isEqualTo(rest.get("commentId").asLong());
        assertThat(grpc.getPostId()).isEqualTo(rest.get("postId").asLong());
        assertThat(grpc.getUserId()).isEqualTo(rest.get("userId").asLong());
        assertThat(grpc.getContent()).isEqualTo(rest.get("content").asText());
        assertThat(toInstant(grpc.getCreatedAt())).isEqualTo(Instant.parse(rest.get("createdAt").asText()));
        assertThat(toInstant(grpc.getUpdatedAt())).isEqualTo(Instant.parse(rest.get("updatedAt").asText()));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
