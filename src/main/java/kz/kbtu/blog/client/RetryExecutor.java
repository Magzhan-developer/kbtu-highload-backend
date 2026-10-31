package kz.kbtu.blog.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Bounded retries with exponential backoff and full jitter.
 *
 * <p>Only give this idempotent work: reads, or writes that reuse one idempotency key on every
 * attempt. A timed-out write may have succeeded on the server, so retrying anything else
 * could apply it twice.
 */
public final class RetryExecutor {

    @FunctionalInterface
    public interface Attempt<T> {
        T run(Duration timeout) throws Exception;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final RetryPolicy policy;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    public RetryExecutor(RetryPolicy policy) {
        this(policy, System::nanoTime, Thread::sleep, () -> ThreadLocalRandom.current().nextDouble());
    }

    // Tests pass a fake clock, sleeper and random source, so they need no real waiting.
    RetryExecutor(RetryPolicy policy, LongSupplier nanoClock, Sleeper sleeper, DoubleSupplier random) {
        this.policy = policy;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.random = random;
    }

    /** Returns the first success, or rethrows the failure once retrying is pointless. */
    public <T> T execute(Attempt<T> attempt, Predicate<Exception> isRetryable) throws Exception {
        long deadlineNanos = nanoClock.getAsLong() + policy.overallTimeout().toNanos();
        for (int attemptNumber = 1; ; attemptNumber++) {
            Duration remaining = remaining(deadlineNanos);
            Duration timeout = remaining.compareTo(policy.attemptTimeout()) < 0 ? remaining : policy.attemptTimeout();
            try {
                return attempt.run(timeout);
            } catch (Exception e) {
                if (attemptNumber >= policy.maxAttempts() || !isRetryable.test(e)) {
                    throw e;
                }
                Duration backoff = backoffAfter(attemptNumber);
                // Waiting past the deadline would only delay a failure that is already certain.
                if (backoff.compareTo(remaining(deadlineNanos)) >= 0) {
                    throw e;
                }
                sleeper.sleep(backoff);
            }
        }
    }

    private Duration remaining(long deadlineNanos) {
        return Duration.ofNanos(deadlineNanos - nanoClock.getAsLong());
    }

    /**
     * Full jitter: a random wait between 0 and the exponential bound. Without randomness,
     * clients that failed together would all retry at the same moment and fail together again.
     */
    private Duration backoffAfter(int attemptNumber) {
        long doubled = policy.baseBackoff().toNanos() << Math.min(attemptNumber - 1, 20);
        long bound = Math.min(doubled, policy.maxBackoff().toNanos());
        return Duration.ofNanos((long) (random.getAsDouble() * bound));
    }
}
