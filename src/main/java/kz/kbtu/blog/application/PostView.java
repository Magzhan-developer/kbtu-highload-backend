package kz.kbtu.blog.application;

import java.time.Instant;
import kz.kbtu.blog.domain.Post;

/** Protocol-neutral post result. REST and gRPC each map it to their own format. */
public record PostView(
        long id,
        long authorId,
        String title,
        String content,
        Instant createdAt,
        Instant updatedAt) {

    static PostView from(Post post) {
        return new PostView(
                post.getId(),
                post.getAuthorId(),
                post.getTitle(),
                post.getContent(),
                post.getCreatedAt(),
                post.getUpdatedAt());
    }
}
