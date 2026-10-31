package kz.kbtu.blog.api;

import kz.kbtu.blog.domain.exception.UnauthenticatedException;

/**
 * Development-only authentication: the caller simply states who they are.
 * Anyone can claim any id, so this must never be used in production.
 */
public final class DevAuth {

    public static final String USER_ID_HEADER = "X-User-Id";

    private DevAuth() {
    }

    public static long parseUserId(String rawUserId) {
        if (rawUserId == null || rawUserId.isBlank()) {
            throw new UnauthenticatedException(USER_ID_HEADER + " is required");
        }
        try {
            return Long.parseLong(rawUserId.trim());
        } catch (NumberFormatException e) {
            throw new UnauthenticatedException(USER_ID_HEADER + " must be a number");
        }
    }
}
