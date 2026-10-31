package kz.kbtu.blog.api.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.application.PostService;
import kz.kbtu.blog.grpc.v1.GetPostRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.CannotCreateTransactionException;

/** A broken database is hard to arrange on demand, so the service is mocked to fail. */
class GrpcServerFailureTest extends AbstractIntegrationTest {

    private static final GetPostRequest REQUEST = GetPostRequest.newBuilder().setPostId(1).build();

    @Autowired
    GrpcServerLifecycle server;

    @MockitoBean
    PostService postService;

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
    void noDatabaseConnectionIsUnavailable() {
        when(postService.get(1L)).thenThrow(new CannotCreateTransactionException("pool exhausted"));

        assertThat(callAndCaptureStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void queryTimeoutIsUnavailable() {
        when(postService.get(1L)).thenThrow(new QueryTimeoutException("too slow"));

        assertThat(callAndCaptureStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void unexpectedErrorIsInternalWithoutLeakingDetails() {
        when(postService.get(1L)).thenThrow(new IllegalStateException("secret internal detail"));

        Status status = callAndCaptureStatus();

        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).doesNotContain("secret internal detail");
    }

    private Status callAndCaptureStatus() {
        Status[] captured = new Status[1];
        assertThatThrownBy(() -> client.anonymous().getPost(REQUEST))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> captured[0] = e.getStatus());
        return captured[0];
    }
}
