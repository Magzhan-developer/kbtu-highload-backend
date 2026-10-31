package kz.kbtu.blog.api.rest;

import java.time.Instant;
import kz.kbtu.blog.application.PostView;

public record PostResponse(
        long postId,
        long authorId,
        String title,
        String content,
        Instant createdAt,
        Instant updatedAt) {

    static PostResponse from(PostView post) {
        return new PostResponse(
                post.id(),
                post.authorId(),
                post.title(),
                post.content(),
                post.createdAt(),
                post.updatedAt());
    }
}
