package kz.kbtu.blog.api.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicReference;
import kz.kbtu.blog.application.CommentService;
import kz.kbtu.blog.application.PostService;
import kz.kbtu.blog.grpc.v1.GetPostRequest;
import kz.kbtu.blog.grpc.v1.PostResponse;
import org.junit.jupiter.api.Test;

class ServerSideCancellationTest {

    @Test
    void callThatExpiredWhileQueuedDoesNoWork() {
        PostService posts = mock(PostService.class);
        BlogGrpcService service = new BlogGrpcService(posts, mock(CommentService.class));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Context.CancellableContext expired = Context.current().withCancellation();
        expired.cancel(null);

        expired.run(() -> service.getPost(GetPostRequest.newBuilder().setPostId(1).build(),
                new StreamObserver<PostResponse>() {
                    @Override
                    public void onNext(PostResponse value) {
                    }

                    @Override
                    public void onError(Throwable t) {
                        failure.set(t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                }));

        verifyNoInteractions(posts);
        assertThat(Status.fromThrowable(failure.get()).getCode()).isEqualTo(Status.Code.CANCELLED);
    }
}
