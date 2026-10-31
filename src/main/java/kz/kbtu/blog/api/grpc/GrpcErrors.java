package kz.kbtu.blog.api.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import kz.kbtu.blog.api.TransientFailures;
import kz.kbtu.blog.domain.exception.IdempotencyConflictException;
import kz.kbtu.blog.domain.exception.InvalidInputException;
import kz.kbtu.blog.domain.exception.PermissionDeniedException;
import kz.kbtu.blog.domain.exception.ResourceNotFoundException;
import kz.kbtu.blog.domain.exception.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The gRPC counterpart of RestExceptionHandler: same failures, gRPC status codes. */
final class GrpcErrors {

    private static final Logger log = LoggerFactory.getLogger(GrpcErrors.class);

    private GrpcErrors() {
    }

    static StatusRuntimeException toStatusException(RuntimeException e) {
        return toStatus(e).asRuntimeException();
    }

    private static Status toStatus(RuntimeException e) {
        if (e instanceof InvalidInputException) {
            return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
        }
        if (e instanceof UnauthenticatedException) {
            return Status.UNAUTHENTICATED.withDescription(e.getMessage());
        }
        if (e instanceof PermissionDeniedException) {
            return Status.PERMISSION_DENIED.withDescription(e.getMessage());
        }
        if (e instanceof ResourceNotFoundException) {
            return Status.NOT_FOUND.withDescription(e.getMessage());
        }
        if (e instanceof IdempotencyConflictException) {
            return Status.ALREADY_EXISTS.withDescription(e.getMessage());
        }
        // Temporary database trouble: UNAVAILABLE tells clients that retrying may succeed.
        if (TransientFailures.isDatabaseUnavailable(e)) {
            log.warn("Database unavailable: {}", e.getMessage());
            return Status.UNAVAILABLE.withDescription("service temporarily unavailable");
        }
        log.error("Unexpected error", e);
        // The real cause stays in the log: internal details must not reach clients.
        return Status.INTERNAL.withDescription("internal server error");
    }
}
