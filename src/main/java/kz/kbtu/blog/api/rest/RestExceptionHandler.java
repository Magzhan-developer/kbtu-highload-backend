package kz.kbtu.blog.api.rest;

import kz.kbtu.blog.api.TransientFailures;
import kz.kbtu.blog.domain.exception.IdempotencyConflictException;
import kz.kbtu.blog.domain.exception.InvalidInputException;
import kz.kbtu.blog.domain.exception.PermissionDeniedException;
import kz.kbtu.blog.domain.exception.ResourceNotFoundException;
import kz.kbtu.blog.domain.exception.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into HTTP responses. The base class already maps Spring MVC failures
 * (405, 415, unknown URL) to the right status, so the catch-all below cannot hide them.
 */
@RestControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(InvalidInputException.class)
    ResponseEntity<ErrorResponse> handleInvalidInput(InvalidInputException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage());
    }

    @ExceptionHandler(UnauthenticatedException.class)
    ResponseEntity<ErrorResponse> handleUnauthenticated(UnauthenticatedException e) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", e.getMessage());
    }

    @ExceptionHandler(PermissionDeniedException.class)
    ResponseEntity<ErrorResponse> handlePermissionDenied(PermissionDeniedException e) {
        return error(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", e.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "ALREADY_EXISTS", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // Temporary database trouble: 503 tells clients that retrying may succeed.
        if (TransientFailures.isDatabaseUnavailable(e)) {
            log.warn("Database unavailable: {}", e.getMessage());
            return error(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "service temporarily unavailable");
        }
        log.error("Unexpected error", e);
        // The real cause stays in the log: internal details must not reach clients.
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal server error");
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = "invalid value for " + e.getPropertyName();
        return handleExceptionInternal(e, new ErrorResponse("INVALID_ARGUMENT", message), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorResponse body = new ErrorResponse("INVALID_ARGUMENT", "request body is missing or malformed");
        return handleExceptionInternal(e, body, headers, status, request);
    }

    /** Gives framework errors the same JSON shape as our own. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!(body instanceof ErrorResponse)) {
            HttpStatus httpStatus = HttpStatus.valueOf(status.value());
            String code = httpStatus == HttpStatus.BAD_REQUEST ? "INVALID_ARGUMENT" : httpStatus.name();
            body = new ErrorResponse(code, httpStatus.getReasonPhrase());
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }
}
