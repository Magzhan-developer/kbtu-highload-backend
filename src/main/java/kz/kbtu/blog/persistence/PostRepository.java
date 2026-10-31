package kz.kbtu.blog.persistence;

import java.time.Instant;
import java.util.List;
import kz.kbtu.blog.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostRepository extends JpaRepository<Post, Long> {

    @Query(value = """
            SELECT * FROM posts
            WHERE author_id = :authorId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Post> findFirstFeedPage(long authorId, int limit);

    // The row comparison (a, b) < (x, y) matches idx_posts_author_feed exactly, so
    // PostgreSQL starts at the cursor instead of reading and skipping earlier rows.
    @Query(value = """
            SELECT * FROM posts
            WHERE author_id = :authorId
              AND (created_at, id) < (:createdAt, :id)
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Post> findFeedPageAfter(long authorId, Instant createdAt, long id, int limit);
}
