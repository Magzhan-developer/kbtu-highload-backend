package kz.kbtu.blog.api.grpc;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import kz.kbtu.blog.application.CommentView;
import kz.kbtu.blog.application.CursorPage;
import kz.kbtu.blog.application.PostView;
import kz.kbtu.blog.grpc.v1.CommentListResponse;
import kz.kbtu.blog.grpc.v1.CommentResponse;
import kz.kbtu.blog.grpc.v1.PostListResponse;
import kz.kbtu.blog.grpc.v1.PostResponse;

final class ProtoMapper {

    private ProtoMapper() {
    }

    static PostResponse toProto(PostView post) {
        return PostResponse.newBuilder()
                .setPostId(post.id())
                .setAuthorId(post.authorId())
                .setTitle(post.title())
                .setContent(post.content())
                .setCreatedAt(toProto(post.createdAt()))
                .setUpdatedAt(toProto(post.updatedAt()))
                .build();
    }

    static CommentResponse toProto(CommentView comment) {
        return CommentResponse.newBuilder()
                .setCommentId(comment.id())
                .setPostId(comment.postId())
                .setUserId(comment.userId())
                .setContent(comment.content())
                .setCreatedAt(toProto(comment.createdAt()))
                .setUpdatedAt(toProto(comment.updatedAt()))
                .build();
    }

    static PostListResponse toPostList(CursorPage<PostView> page) {
        PostListResponse.Builder list = PostListResponse.newBuilder();
        page.items().forEach(post -> list.addItems(toProto(post)));
        // Protobuf strings cannot be null. An unset string reads as "", which marks the last page.
        if (page.nextCursor() != null) {
            list.setNextCursor(page.nextCursor());
        }
        return list.build();
    }

    static CommentListResponse toCommentList(CursorPage<CommentView> page) {
        CommentListResponse.Builder list = CommentListResponse.newBuilder();
        page.items().forEach(comment -> list.addItems(toProto(comment)));
        if (page.nextCursor() != null) {
            list.setNextCursor(page.nextCursor());
        }
        return list.build();
    }

    private static Timestamp toProto(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
