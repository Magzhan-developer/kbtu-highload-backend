package kz.kbtu.blog.persistence;

import java.time.Instant;
import java.util.List;
import kz.kbtu.blog.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @Query(value = """
            SELECT * FROM comments
            WHERE post_id = :postId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Comment> findFirstPage(long postId, int limit);

    @Query(value = """
            SELECT * FROM comments
            WHERE post_id = :postId
              AND (created_at, id) < (:createdAt, :id)
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Comment> findPageAfter(long postId, Instant createdAt, long id, int limit);
}
