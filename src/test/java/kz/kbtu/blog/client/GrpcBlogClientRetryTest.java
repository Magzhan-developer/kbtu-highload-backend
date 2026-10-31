package kz.kbtu.blog.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import kz.kbtu.blog.grpc.v1.BlogServiceGrpc;
import kz.kbtu.blog.grpc.v1.CreatePostRequest;
import kz.kbtu.blog.grpc.v1.GetPostRequest;
import kz.kbtu.blog.grpc.v1.PostResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A scripted fake server: each call takes the next outcome, and every key it saw is recorded. */
class GrpcBlogClientRetryTest {

    private static final String KEY = "6f1c2a44-3a7e-4b1e-9a55-1f2c3d4e5f60";
    private static final RetryPolicy POLICY = new RetryPolicy(3, Duration.ofMillis(300), Duration.ofSeconds(5),
            Duration.ofMillis(1), Duration.ofMillis(5));

    /** Means the fake never answers, so the client's deadline has to fire. */
    private static final Status NO_RESPONSE = Status.ABORTED.withDescription("hang");

    private final Queue<Status> outcomes = new ArrayDeque<>();
    private final List<String> keysSeen = new ArrayList<>();
    private int getCalls;

    private Server server;
    private GrpcBlogClient client;

    @BeforeEach
    void start() throws Exception {
        server = NettyServerBuilder.forPort(0).addService(new BlogServiceGrpc.BlogServiceImplBase() {
            @Override
            public synchronized void createPost(CreatePostRequest request, StreamObserver<PostResponse> responses) {
                keysSeen.add(request.getIdempotencyKey());
                answer(responses);
            }

            @Override
            public synchronized void getPost(GetPostRequest request, StreamObserver<PostResponse> responses) {
                getCalls++;
                answer(responses);
            }
        }).build().start();
        client = new GrpcBlogClient("localhost", server.getPort(), POLICY);
    }

    @AfterEach
    void stop() {
        client.close();
        server.shutdownNow();
    }

    private void answer(StreamObserver<PostResponse> responses) {
        Status outcome = outcomes.remove();
        if (outcome == NO_RESPONSE) {
            return;
        }
        if (outcome.isOk()) {
            responses.onNext(PostResponse.newBuilder().setPostId(7).build());
            responses.onCompleted();
        } else {
            responses.onError(outcome.asRuntimeException());
        }
    }

    @Test
    void writeRetriesUnavailableAndReusesTheSameKey() throws Exception {
        outcomes.addAll(List.of(Status.UNAVAILABLE, Status.UNAVAILABLE, Status.OK));

        CallResult result = client.createPost(1, KEY, "Title", "Content");

        assertThat(result.id()).isEqualTo(7);
        assertThat(keysSeen).containsExactly(KEY, KEY, KEY);
    }

    @Test
    void writeRetriesAfterADeadlineAndReusesTheSameKey() throws Exception {
        outcomes.addAll(List.of(NO_RESPONSE, Status.OK));

        CallResult result = client.createPost(1, KEY, "Title", "Content");

        assertThat(result.id()).isEqualTo(7);
        assertThat(keysSeen).containsExactly(KEY, KEY);
    }

    @Test
    void readsRetryToo() throws Exception {
        outcomes.addAll(List.of(Status.UNAVAILABLE, Status.OK));

        assertThat(client.getPost(7).id()).isEqualTo(7);
        assertThat(getCalls).isEqualTo(2);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        outcomes.addAll(List.of(Status.UNAVAILABLE, Status.UNAVAILABLE, Status.UNAVAILABLE));

        assertThatThrownBy(() -> client.createPost(1, KEY, "Title", "Content"))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
        assertThat(keysSeen).hasSize(3);
    }

    @Test
    void permanentErrorsAreNotRetried() {
        for (Status status : List.of(Status.INVALID_ARGUMENT, Status.PERMISSION_DENIED, Status.UNAUTHENTICATED,
                Status.NOT_FOUND, Status.ALREADY_EXISTS, Status.INTERNAL)) {
            keysSeen.clear();
            outcomes.add(status);

            assertThatThrownBy(() -> client.createPost(1, KEY, "Title", "Content"))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(status.getCode()));
            assertThat(keysSeen).hasSize(1);
        }
    }

    @Test
    void payloadSizesAreTheProtobufBytes() throws Exception {
        outcomes.add(Status.OK);
        CreatePostRequest sent = CreatePostRequest.newBuilder()
                .setIdempotencyKey(KEY).setTitle("Title").setContent("Content").build();

        CallResult result = client.createPost(1, KEY, "Title", "Content");

        assertThat(result.requestBytes()).isEqualTo(sent.getSerializedSize());
        assertThat(result.responseBytes()).isEqualTo(PostResponse.newBuilder().setPostId(7).build().getSerializedSize());
    }
}
