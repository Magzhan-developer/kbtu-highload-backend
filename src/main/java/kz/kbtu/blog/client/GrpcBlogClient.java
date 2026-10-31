package kz.kbtu.blog.client;

import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import kz.kbtu.blog.grpc.v1.BlogServiceGrpc;
import kz.kbtu.blog.grpc.v1.BlogServiceGrpc.BlogServiceBlockingStub;
import kz.kbtu.blog.grpc.v1.CommentListResponse;
import kz.kbtu.blog.grpc.v1.CommentResponse;
import kz.kbtu.blog.grpc.v1.CreateCommentRequest;
import kz.kbtu.blog.grpc.v1.CreatePostRequest;
import kz.kbtu.blog.grpc.v1.GetAuthorPostsRequest;
import kz.kbtu.blog.grpc.v1.GetCommentsRequest;
import kz.kbtu.blog.grpc.v1.GetPostRequest;
import kz.kbtu.blog.grpc.v1.PostListResponse;
import kz.kbtu.blog.grpc.v1.PostResponse;

public final class GrpcBlogClient implements BlogClient {

    // Everything else, such as INVALID_ARGUMENT or PERMISSION_DENIED, fails the same way every time.
    private static final Set<Status.Code> RETRYABLE = Set.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED);

    private static final Metadata.Key<String> USER_ID =
            Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;
    private final BlogServiceBlockingStub stub;
    private final RetryExecutor retries;
    private final LongAdder attempts = new LongAdder();

    public GrpcBlogClient(String host, int port, RetryPolicy policy) {
        // One channel is one HTTP/2 connection: all concurrent calls are multiplexed over it.
        this.channel = NettyChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = BlogServiceGrpc.newBlockingStub(channel);
        this.retries = new RetryExecutor(policy);
    }

    @Override
    public CallResult createPost(long userId, String idempotencyKey, String title, String content) throws Exception {
        CreatePostRequest request = CreatePostRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey).setTitle(title).setContent(content).build();
        BlogServiceBlockingStub caller = withUser(userId);
        PostResponse response = call(timeout -> withDeadline(caller, timeout).createPost(request));
        return result(response.getPostId(), request, response);
    }

    @Override
    public CallResult createComment(long userId, String idempotencyKey, long postId, String content) throws Exception {
        CreateCommentRequest request = CreateCommentRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey).setPostId(postId).setContent(content).build();
        BlogServiceBlockingStub caller = withUser(userId);
        CommentResponse response = call(timeout -> withDeadline(caller, timeout).createComment(request));
        return result(response.getCommentId(), request, response);
    }

    @Override
    public CallResult getPost(long postId) throws Exception {
        GetPostRequest request = GetPostRequest.newBuilder().setPostId(postId).build();
        PostResponse response = call(timeout -> withDeadline(stub, timeout).getPost(request));
        return result(response.getPostId(), request, response);
    }

    @Override
    public CallResult getAuthorPosts(long authorId, int limit) throws Exception {
        GetAuthorPostsRequest request = GetAuthorPostsRequest.newBuilder().setAuthorId(authorId).setLimit(limit).build();
        PostListResponse response = call(timeout -> withDeadline(stub, timeout).getAuthorPosts(request));
        long firstId = response.getItemsCount() == 0 ? 0 : response.getItems(0).getPostId();
        return result(firstId, request, response);
    }

    @Override
    public CallResult getComments(long postId, int limit) throws Exception {
        GetCommentsRequest request = GetCommentsRequest.newBuilder().setPostId(postId).setLimit(limit).build();
        CommentListResponse response = call(timeout -> withDeadline(stub, timeout).getComments(request));
        long firstId = response.getItemsCount() == 0 ? 0 : response.getItems(0).getCommentId();
        return result(firstId, request, response);
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }

    @Override
    public long attemptCount() {
        return attempts.sum();
    }

    private <T> T call(Function<Duration, T> attempt) throws Exception {
        return retries.execute(timeout -> {
            attempts.increment();
            return attempt.apply(timeout);
        }, GrpcBlogClient::isRetryable);
    }

    private static boolean isRetryable(Exception e) {
        return e instanceof StatusRuntimeException failure && RETRYABLE.contains(failure.getStatus().getCode());
    }

    private static BlogServiceBlockingStub withDeadline(BlogServiceBlockingStub caller, Duration timeout) {
        return caller.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private BlogServiceBlockingStub withUser(long userId) {
        Metadata headers = new Metadata();
        headers.put(USER_ID, String.valueOf(userId));
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static CallResult result(long id, Message request, Message response) {
        return new CallResult(id, request.getSerializedSize(), response.getSerializedSize());
    }
}
