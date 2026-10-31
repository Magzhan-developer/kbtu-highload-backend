package kz.kbtu.blog.api.grpc;

import com.google.protobuf.Empty;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;
import kz.kbtu.blog.api.DevAuth;
import kz.kbtu.blog.application.CommentService;
import kz.kbtu.blog.application.PostService;
import kz.kbtu.blog.grpc.v1.BlogServiceGrpc;
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
import org.springframework.stereotype.Component;

/** The gRPC counterpart of the REST controllers. It calls the same application services. */
@Component
public class BlogGrpcService extends BlogServiceGrpc.BlogServiceImplBase {

    private final PostService posts;
    private final CommentService comments;

    public BlogGrpcService(PostService posts, CommentService comments) {
        this.posts = posts;
        this.comments = comments;
    }

    @Override
    public void createPost(CreatePostRequest request, StreamObserver<PostResponse> responses) {
        respond(responses, () -> ProtoMapper.toProto(posts.create(
                currentUserId(), request.getIdempotencyKey(), request.getTitle(), request.getContent())));
    }

    @Override
    public void getPost(GetPostRequest request, StreamObserver<PostResponse> responses) {
        respond(responses, () -> ProtoMapper.toProto(posts.get(request.getPostId())));
    }

    @Override
    public void getAuthorPosts(GetAuthorPostsRequest request, StreamObserver<PostListResponse> responses) {
        respond(responses, () -> ProtoMapper.toPostList(
                posts.listByAuthor(request.getAuthorId(), request.getLimit(), request.getCursor())));
    }

    @Override
    public void updatePost(UpdatePostRequest request, StreamObserver<PostResponse> responses) {
        respond(responses, () -> ProtoMapper.toProto(posts.update(
                currentUserId(), request.getPostId(), request.getTitle(), request.getContent())));
    }

    @Override
    public void deletePost(DeletePostRequest request, StreamObserver<Empty> responses) {
        respond(responses, () -> {
            posts.delete(currentUserId(), request.getPostId());
            return Empty.getDefaultInstance();
        });
    }

    @Override
    public void createComment(CreateCommentRequest request, StreamObserver<CommentResponse> responses) {
        respond(responses, () -> ProtoMapper.toProto(comments.create(
                currentUserId(), request.getIdempotencyKey(), request.getPostId(), request.getContent())));
    }

    @Override
    public void getComments(GetCommentsRequest request, StreamObserver<CommentListResponse> responses) {
        respond(responses, () -> ProtoMapper.toCommentList(
                comments.listByPost(request.getPostId(), request.getLimit(), request.getCursor())));
    }

    @Override
    public void updateComment(UpdateCommentRequest request, StreamObserver<CommentResponse> responses) {
        respond(responses, () -> ProtoMapper.toProto(
                comments.update(currentUserId(), request.getCommentId(), request.getContent())));
    }

    @Override
    public void deleteComment(DeleteCommentRequest request, StreamObserver<Empty> responses) {
        respond(responses, () -> {
            comments.delete(currentUserId(), request.getCommentId());
            return Empty.getDefaultInstance();
        });
    }

    /** Sends the one response of a unary call, or turns a failure into a gRPC status. */
    private static <T> void respond(StreamObserver<T> responses, Supplier<T> call) {
        // gRPC sends the client's deadline to the server. If it passed while this call waited
        // for a thread, nobody is listening, so skip the work. Once the work has started it
        // runs to completion, which is why DEADLINE_EXCEEDED does not prove nothing happened.
        if (Context.current().isCancelled()) {
            responses.onError(Status.CANCELLED.withDescription("call cancelled before it started").asRuntimeException());
            return;
        }
        T response;
        try {
            response = call.get();
        } catch (RuntimeException e) {
            responses.onError(GrpcErrors.toStatusException(e));
            return;
        }
        responses.onNext(response);
        responses.onCompleted();
    }

    private static long currentUserId() {
        return DevAuth.parseUserId(UserIdInterceptor.RAW_USER_ID.get());
    }
}
