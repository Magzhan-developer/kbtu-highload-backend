package kz.kbtu.blog.api.rest;

import java.time.Instant;
import kz.kbtu.blog.application.CommentView;

public record CommentResponse(
        long commentId,
        long postId,
        long userId,
        String content,
        Instant createdAt,
        Instant updatedAt) {

    static CommentResponse from(CommentView comment) {
        return new CommentResponse(
                comment.id(),
                comment.postId(),
                comment.userId(),
                comment.content(),
                comment.createdAt(),
                comment.updatedAt());
    }
}
