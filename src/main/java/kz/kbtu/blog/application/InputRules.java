package kz.kbtu.blog.application;

import java.util.UUID;
import kz.kbtu.blog.domain.exception.InvalidInputException;

/** Input validation lives here, so REST and gRPC enforce identical rules. */
final class InputRules {

    static final int TITLE_MAX = 200;
    static final int POST_CONTENT_MAX = 10_000;
    static final int COMMENT_CONTENT_MAX = 2_000;

    private InputRules() {
    }

    static void requireTitle(String title) {
        requireText("title", title, TITLE_MAX);
    }

    static void requirePostContent(String content) {
        requireText("content", content, POST_CONTENT_MAX);
    }

    static void requireCommentContent(String content) {
        requireText("content", content, COMMENT_CONTENT_MAX);
    }

    static UUID requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidInputException("idempotency key is required");
        }
        try {
            UUID parsed = UUID.fromString(key);
            // fromString also accepts short forms such as "1-1-1-1-1".
            if (parsed.toString().equalsIgnoreCase(key)) {
                return parsed;
            }
        } catch (IllegalArgumentException e) {
            // Falls through to the same error as a non-canonical value.
        }
        throw new InvalidInputException("idempotency key must be a UUID");
    }

    private static void requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new InvalidInputException(field + " must be at most " + maxLength + " characters");
        }
        // PostgreSQL text cannot store a NUL character and would fail with a server error.
        if (value.indexOf('\0') >= 0) {
            throw new InvalidInputException(field + " must not contain NUL characters");
        }
    }
}
