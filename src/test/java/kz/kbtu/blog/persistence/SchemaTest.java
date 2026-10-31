package kz.kbtu.blog.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kz.kbtu.blog.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class SchemaTest extends AbstractIntegrationTest {

    @Test
    void devUsersAreSeeded() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username IN ('alice', 'bob', 'carol')", Long.class);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void feedIndexesMatchThePaginationOrder() {
        assertThat(indexDefinition("idx_posts_author_feed"))
                .contains("(author_id, created_at DESC, id DESC)");
        assertThat(indexDefinition("idx_comments_post_feed"))
                .contains("(post_id, created_at DESC, id DESC)");
    }

    @Test
    void databaseRejectsADuplicateIdempotencyKeyInTheSameScope() {
        UUID key = UUID.randomUUID();
        insertIdempotencyRecord(ALICE, "CREATE_POST", key);

        assertThatThrownBy(() -> insertIdempotencyRecord(ALICE, "CREATE_POST", key))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void sameIdempotencyKeyIsAllowedInADifferentScope() {
        UUID key = UUID.randomUUID();
        insertIdempotencyRecord(ALICE, "CREATE_POST", key);

        insertIdempotencyRecord(BOB, "CREATE_POST", key);
        insertIdempotencyRecord(ALICE, "CREATE_COMMENT", key);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Long.class))
                .isEqualTo(3);
    }

    private String indexDefinition(String name) {
        return jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, name);
    }

    private void insertIdempotencyRecord(long userId, String operation, UUID key) {
        jdbc.update("""
                INSERT INTO idempotency_records (user_id, operation_type, idempotency_key, request_hash, created_at)
                VALUES (?, ?, ?, 'hash', now())
                """, userId, operation, key);
    }
}
