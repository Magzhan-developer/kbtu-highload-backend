package kz.kbtu.blog.api.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import kz.kbtu.blog.grpc.v1.BlogServiceGrpc;
import kz.kbtu.blog.grpc.v1.GetPostRequest;
import kz.kbtu.blog.grpc.v1.PostResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcMetricsInterceptorTest {

    private static final GetPostRequest REQUEST = GetPostRequest.newBuilder().setPostId(1).build();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private Status outcome = Status.OK;
    private Server server;
    private ManagedChannel channel;
    private BlogServiceGrpc.BlogServiceBlockingStub stub;

    @BeforeEach
    void start() throws Exception {
        BlogServiceGrpc.BlogServiceImplBase service = new BlogServiceGrpc.BlogServiceImplBase() {
            @Override
            public void getPost(GetPostRequest request, StreamObserver<PostResponse> responses) {
                // Returning without a reply leaves the call open until the client's deadline.
                if (outcome == Status.DEADLINE_EXCEEDED) {
                    return;
                }
                if (outcome.isOk()) {
                    responses.onNext(PostResponse.getDefaultInstance());
                    responses.onCompleted();
                } else {
                    responses.onError(outcome.asRuntimeException());
                }
            }
        };
        server = NettyServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(service, new GrpcMetricsInterceptor(registry)))
                .build().start();
        channel = NettyChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        stub = BlogServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private Timer timer(String status) {
        return registry.find(GrpcMetricsInterceptor.TIMER_NAME).tag("status", status).timer();
    }

    @Test
    void successIsRecordedOnceWithMethodAndStatus() {
        stub.getPost(REQUEST);

        Timer timer = timer("OK");
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTag("method")).isEqualTo("GetPost");
        assertThat(timer.getId().getTag("outcome")).isEqualTo("SUCCESS");
    }

    @Test
    void failureIsRecordedWithItsStatus() {
        outcome = Status.NOT_FOUND;

        try {
            stub.getPost(REQUEST);
        } catch (StatusRuntimeException expected) {
            assertThat(timer("NOT_FOUND").count()).isEqualTo(1);
            assertThat(timer("NOT_FOUND").getId().getTag("outcome")).isEqualTo("ERROR");
            return;
        }
        throw new AssertionError("expected a failure");
    }

    @Test
    void callAbandonedByTheClientIsStillRecordedOnce() {
        outcome = Status.DEADLINE_EXCEEDED;

        try {
            stub.withDeadlineAfter(100, TimeUnit.MILLISECONDS).getPost(REQUEST);
        } catch (StatusRuntimeException expected) {
            // The server never answers, so this call only ends through cancellation.
        }

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> timer("CANCELLED") != null);
        assertThat(timer("CANCELLED").count()).isEqualTo(1);
    }
}
