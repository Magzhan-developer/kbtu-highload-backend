package kz.kbtu.blog.api.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.grpc.v1.CommentListResponse;
import kz.kbtu.blog.grpc.v1.CommentResponse;
import kz.kbtu.blog.grpc.v1.CreateCommentRequest;
import kz.kbtu.blog.grpc.v1.CreatePostRequest;
import kz.kbtu.blog.grpc.v1.DeleteCommentRequest;
import kz.kbtu.blog.grpc.v1.DeletePostRequest;
import kz.kbtu.blog.grpc.v1.GetAuthorPostsRequest;
import kz.kbtu.blog.grpc.v1.GetCommentsRequest;
import kz.kbtu.blog.grpc.v1.GetPostRequest;
import kz.kbtu.blog.grpc.v1.PostListResponse;
import kz.kbtu.blog.grpc.v1.PostResponse;
import kz.kbtu.blog.grpc.v1.UpdateCommentRequest;
import kz.kbtu.blog.grpc.v1.UpdatePostRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GrpcApiTest extends AbstractIntegrationTest {

    @Autowired
    GrpcServerLifecycle server;

    private GrpcTestClient client;

    @BeforeEach
    void connect() {
        client = new GrpcTestClient(server.getPort());
    }

    @AfterEach
    void disconnect() {
        client.close();
    }

    @Test
    void createdPostCanBeReadBackAnonymously() {
        PostResponse created = createPost(ALICE, "Title");

        assertThat(created.getPostId()).isPositive();
        assertThat(created.getAuthorId()).isEqualTo(ALICE);
        assertThat(created.getContent()).isEqualTo("Content");
        assertThat(created.hasCreatedAt()).isTrue();
        assertThat(created.getUpdatedAt()).isEqualTo(created.getCreatedAt());

        PostResponse read = client.anonymous()
                .getPost(GetPostRequest.newBuilder().setPostId(created.getPostId()).build());

        assertThat(read).isEqualTo(created);
    }

    @Test
    void unicodeSurvivesTheRoundTrip() {
        PostResponse created = client.as(ALICE).createPost(CreatePostRequest.newBuilder().setIdempotencyKey(newKey())
                .setTitle("Қазақша 日本語").setContent("Сәлем 🌍").build());

        PostResponse read = client.anonymous()
                .getPost(GetPostRequest.newBuilder().setPostId(created.getPostId()).build());

        assertThat(read.getTitle()).isEqualTo("Қазақша 日本語");
        assertThat(read.getContent()).isEqualTo("Сәлем 🌍");
    }

    @Test
    void updateChangesThePost() {
        PostResponse created = createPost(ALICE, "Title");

        PostResponse updated = client.as(ALICE).updatePost(UpdatePostRequest.newBuilder()
                .setPostId(created.getPostId()).setTitle("New").setContent("New content").build());

        assertThat(updated.getTitle()).isEqualTo("New");
        assertThat(updated.getCreatedAt()).isEqualTo(created.getCreatedAt());
        assertThat(toMicros(updated.getUpdatedAt())).isGreaterThanOrEqualTo(toMicros(created.getUpdatedAt()));
    }

    @Test
    void deleteRemovesThePost() {
        PostResponse created = createPost(ALICE, "Title");
        DeletePostRequest delete = DeletePostRequest.newBuilder().setPostId(created.getPostId()).build();

        client.as(ALICE).deletePost(delete);

        assertStatus(Status.Code.NOT_FOUND, () -> client.anonymous()
                .getPost(GetPostRequest.newBuilder().setPostId(created.getPostId()).build()));
    }

    @Test
    void authorFeedPagesThroughAllPostsWithCursors() {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(0, createPost(ALICE, "Post " + i).getPostId());
        }
        createPost(BOB, "Other author");

        List<Long> actual = new ArrayList<>();
        String cursor = "";
        do {
            PostListResponse page = client.anonymous().getAuthorPosts(GetAuthorPostsRequest.newBuilder()
                    .setAuthorId(ALICE).setLimit(2).setCursor(cursor).build());
            page.getItemsList().forEach(post -> actual.add(post.getPostId()));
            cursor = page.getNextCursor();
        } while (!cursor.isEmpty());

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void commentLifecycle() {
        long postId = createPost(ALICE, "Title").getPostId();
        CommentResponse created = client.as(BOB).createComment(CreateCommentRequest.newBuilder().setIdempotencyKey(newKey())
                .setPostId(postId).setContent("Nice post").build());

        assertThat(created.getUserId()).isEqualTo(BOB);
        assertThat(listComments(postId).getItemsList()).containsExactly(created);

        CommentResponse updated = client.as(BOB).updateComment(UpdateCommentRequest.newBuilder()
                .setCommentId(created.getCommentId()).setContent("Edited").build());

        assertThat(updated.getContent()).isEqualTo("Edited");
        assertThat(listComments(postId).getItemsList()).containsExactly(updated);

        client.as(BOB).deleteComment(DeleteCommentRequest.newBuilder().setCommentId(created.getCommentId()).build());

        assertThat(listComments(postId).getItemsList()).isEmpty();
    }

    @Test
    void commentsPageThroughWithCursors() {
        long postId = createPost(ALICE, "Title").getPostId();
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(0, client.as(BOB).createComment(CreateCommentRequest.newBuilder().setIdempotencyKey(newKey())
                    .setPostId(postId).setContent("Comment").build()).getCommentId());
        }

        List<Long> actual = new ArrayList<>();
        String cursor = "";
        do {
            CommentListResponse page = client.anonymous().getComments(GetCommentsRequest.newBuilder()
                    .setPostId(postId).setLimit(2).setCursor(cursor).build());
            page.getItemsList().forEach(comment -> actual.add(comment.getCommentId()));
            cursor = page.getNextCursor();
        } while (!cursor.isEmpty());

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void writesRequireAValidUser() {
        CreatePostRequest request = CreatePostRequest.newBuilder().setIdempotencyKey(newKey()).setTitle("Title").setContent("Content").build();

        assertStatus(Status.Code.UNAUTHENTICATED, () -> client.anonymous().createPost(request));
        assertStatus(Status.Code.UNAUTHENTICATED, () -> client.as("not-a-number").createPost(request));
        assertStatus(Status.Code.UNAUTHENTICATED, () -> client.as(UNKNOWN_USER).createPost(request));
    }

    @Test
    void changingSomeoneElsesContentIsPermissionDenied() {
        long postId = createPost(ALICE, "Title").getPostId();

        assertStatus(Status.Code.PERMISSION_DENIED, () -> client.as(BOB).deletePost(
                DeletePostRequest.newBuilder().setPostId(postId).build()));
    }

    @Test
    void invalidInputIsInvalidArgument() {
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.as(ALICE).createPost(
                CreatePostRequest.newBuilder().setIdempotencyKey(newKey()).setContent("No title").build()));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.anonymous().getAuthorPosts(
                GetAuthorPostsRequest.newBuilder().setAuthorId(ALICE).setLimit(101).build()));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.anonymous().getAuthorPosts(
                GetAuthorPostsRequest.newBuilder().setAuthorId(ALICE).setCursor("garbage").build()));
    }

    @Test
    void missingResourcesAreNotFound() {
        assertStatus(Status.Code.NOT_FOUND, () -> client.anonymous().getPost(
                GetPostRequest.newBuilder().setPostId(UNKNOWN_ID).build()));
        assertStatus(Status.Code.NOT_FOUND, () -> client.as(BOB).createComment(
                CreateCommentRequest.newBuilder().setIdempotencyKey(newKey()).setPostId(UNKNOWN_ID).setContent("Hello").build()));
        assertStatus(Status.Code.NOT_FOUND, () -> client.anonymous().getComments(
                GetCommentsRequest.newBuilder().setPostId(UNKNOWN_ID).build()));
    }

    @Test
    void clientDeadlineThatHasAlreadyPassedFailsWithDeadlineExceeded() {
        assertStatus(Status.Code.DEADLINE_EXCEEDED, () -> client.anonymous()
                .withDeadlineAfter(0, TimeUnit.MILLISECONDS)
                .getPost(GetPostRequest.newBuilder().setPostId(1).build()));
    }

    private PostResponse createPost(long userId, String title) {
        return client.as(userId).createPost(
                CreatePostRequest.newBuilder().setIdempotencyKey(newKey()).setTitle(title).setContent("Content").build());
    }

    private CommentListResponse listComments(long postId) {
        return client.anonymous().getComments(GetCommentsRequest.newBuilder().setPostId(postId).build());
    }

    private static long toMicros(Timestamp timestamp) {
        return timestamp.getSeconds() * 1_000_000 + timestamp.getNanos() / 1_000;
    }

    private static void assertStatus(Status.Code expected, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(expected));
    }
}
