package kz.kbtu.blog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.function.Supplier;
import kz.kbtu.blog.domain.IdempotencyRecord;
import kz.kbtu.blog.domain.exception.IdempotencyConflictException;
import kz.kbtu.blog.persistence.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final Counter created;
    private final Counter replayed;
    private final Counter conflicts;

    IdempotencyService(IdempotencyRecordRepository records, ObjectMapper objectMapper, MeterRegistry registry) {
        this.records = records;
        this.objectMapper = objectMapper;
        this.created = outcomeCounter(registry, "created");
        this.replayed = outcomeCounter(registry, "replayed");
        this.conflicts = outcomeCounter(registry, "conflict");
    }

    private static Counter outcomeCounter(MeterRegistry registry, String outcome) {
        return Counter.builder("blog.idempotency.outcomes")
                .description("Result of idempotent create requests")
                .tag("outcome", outcome)
                .register(registry);
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
        countAfterCommit(created);
        return result;
    }

    // Counting earlier would include creates that later roll back.
    private static void countAfterCommit(Counter counter) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
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
            conflicts.increment();
            throw new IdempotencyConflictException("idempotency key was already used with different request data");
        }
        replayed.increment();
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
