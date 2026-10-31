package kz.kbtu.blog.client;

import java.time.Duration;

/**
 * Shared by the REST and gRPC clients, so both protocols retry identically.
 *
 * @param maxAttempts total tries, including the first
 * @param attemptTimeout limit for a single try
 * @param overallTimeout limit for all tries and the waits between them
 * @param baseBackoff upper bound of the wait after the first failure; it doubles each time
 * @param maxBackoff the bound never grows beyond this
 */
public record RetryPolicy(
        int maxAttempts,
        Duration attemptTimeout,
        Duration overallTimeout,
        Duration baseBackoff,
        Duration maxBackoff) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(500), Duration.ofMillis(1500),
                Duration.ofMillis(50), Duration.ofMillis(400));
    }
}
