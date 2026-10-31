package kz.kbtu.blog.application;

import java.time.Instant;
import kz.kbtu.blog.domain.Comment;

/** Protocol-neutral comment result. REST and gRPC each map it to their own format. */
public record CommentView(
        long id,
        long postId,
        long userId,
        String content,
        Instant createdAt,
        Instant updatedAt) {

    static CommentView from(Comment comment) {
        return new CommentView(
                comment.getId(),
                comment.getPostId(),
                comment.getUserId(),
                comment.getContent(),
                comment.getCreatedAt(),
                comment.getUpdatedAt());
    }
}
