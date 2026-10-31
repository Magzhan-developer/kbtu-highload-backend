package kz.kbtu.blog.domain.exception;

/** The same idempotency key was reused for a request with different data. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
