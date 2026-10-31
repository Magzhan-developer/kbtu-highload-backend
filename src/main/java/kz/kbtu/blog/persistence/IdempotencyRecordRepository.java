package kz.kbtu.blog.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kz.kbtu.blog.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    /**
     * Returns 1 if this request now owns the key and 0 if the key is taken. When another
     * uncommitted transaction holds the key, PostgreSQL makes this statement wait until
     * that transaction ends, which is what serializes concurrent duplicates.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_records (user_id, operation_type, idempotency_key, request_hash, created_at)
            VALUES (:userId, :operationType, :idempotencyKey, :requestHash, :createdAt)
            ON CONFLICT (user_id, operation_type, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claim(long userId, String operationType, UUID idempotencyKey, String requestHash, Instant createdAt);

    Optional<IdempotencyRecord> findByUserIdAndOperationTypeAndIdempotencyKey(
            long userId, String operationType, UUID idempotencyKey);

    @Modifying
    @Query(value = """
            UPDATE idempotency_records
            SET response_body = :responseBody, response_status = :responseStatus
            WHERE user_id = :userId AND operation_type = :operationType AND idempotency_key = :idempotencyKey
            """, nativeQuery = true)
    int complete(long userId, String operationType, UUID idempotencyKey, String responseBody, String responseStatus);
}
