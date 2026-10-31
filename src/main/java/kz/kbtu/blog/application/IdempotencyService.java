package kz.kbtu.blog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.function.Supplier;
import kz.kbtu.blog.domain.IdempotencyRecord;
import kz.kbtu.blog.domain.exception.IdempotencyConflictException;
import kz.kbtu.blog.persistence.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a create operation at most once per key.
 *
 * <p>The key claim, the new resource and the stored response all commit in the caller's
 * transaction, so they exist together or not at all. If the server crashes midway,
 * PostgreSQL rolls everything back and a retry runs as a fresh request. That is why no
 * "in progress" status or cleanup job is needed.
 */
@Service
class IdempotencyService {

    private static final String RESPONSE_STATUS_CREATED = "CREATED";

    private final IdempotencyRecordRepository records;
    private final ObjectMapper objectMapper;

    IdempotencyService(IdempotencyRecordRepository records, ObjectMapper objectMapper) {
        this.records = records;
        this.objectMapper = objectMapper;
    }

    /** MANDATORY: without the caller's transaction, the atomicity above would not hold. */
    @Transactional(propagation = Propagation.MANDATORY)
    <T> T execute(
            long userId,
            OperationType operation,
            UUID key,
            String requestHash,
            Class<T> resultType,
            Supplier<T> action) {
        boolean claimed = records.claim(userId, operation.name(), key, requestHash, Timestamps.now()) == 1;
        if (!claimed) {
            return replay(userId, operation, key, requestHash, resultType);
        }
        T result = action.get();
        records.complete(userId, operation.name(), key, toJson(result), RESPONSE_STATUS_CREATED);
        return result;
    }

    /**
     * This SELECT is a separate statement on purpose. Under READ COMMITTED each statement
     * gets a new snapshot, so it sees the row committed while claim() was waiting. Under
     * REPEATABLE READ the row would be invisible here.
     */
    private <T> T replay(long userId, OperationType operation, UUID key, String requestHash, Class<T> resultType) {
        IdempotencyRecord record = records
                .findByUserIdAndOperationTypeAndIdempotencyKey(userId, operation.name(), key)
                .orElseThrow(() -> new IllegalStateException("claimed idempotency record not found"));
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("idempotency key was already used with different request data");
        }
        return fromJson(record.getResponseBody(), resultType);
    }

    // Stores the protocol-neutral view, so a key first used over one protocol replays over the other.
    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize idempotent response", e);
        }
    }

    private <T> T fromJson(String body, Class<T> resultType) {
        try {
            return objectMapper.readValue(body, resultType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not read stored idempotent response", e);
        }
    }
}
