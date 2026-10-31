package kz.kbtu.blog.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

/** Uses a fake clock, so timing rules are tested exactly and without real waiting. */
class RetryExecutorTest {

    private static final RetryPolicy POLICY = new RetryPolicy(4, Duration.ofMillis(500), Duration.ofMillis(1500),
            Duration.ofMillis(100), Duration.ofMillis(300));

    private final List<Duration> sleeps = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();
    private long nowNanos = 0;

    private RetryExecutor executor(RetryPolicy policy, DoubleSupplier random) {
        return new RetryExecutor(policy, () -> nowNanos, duration -> {
            sleeps.add(duration);
            nowNanos += duration.toNanos();
        }, random);
    }

    private static final class Transient extends Exception {
    }

    private static final class Permanent extends Exception {
    }

    @Test
    void returnsTheFirstSuccessWithoutRetrying() throws Exception {
        String result = executor(POLICY, () -> 1.0).execute(timeout -> "ok", e -> true);

        assertThat(result).isEqualTo("ok");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesTransientFailuresUntilSuccess() throws Exception {
        int[] attempts = {0};

        String result = executor(POLICY, () -> 1.0).execute(timeout -> {
            if (++attempts[0] < 3) {
                throw new Transient();
            }
            return "ok";
        }, e -> e instanceof Transient);

        assertThat(result).isEqualTo("ok");
        assertThat(attempts[0]).isEqualTo(3);
    }

    @Test
    void neverRetriesPermanentFailures() {
        int[] attempts = {0};

        assertThatThrownBy(() -> executor(POLICY, () -> 1.0).execute(timeout -> {
            attempts[0]++;
            throw new Permanent();
        }, e -> e instanceof Transient)).isInstanceOf(Permanent.class);

        assertThat(attempts[0]).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void stopsAtMaxAttemptsAndRethrowsTheLastFailure() {
        int[] attempts = {0};

        assertThatThrownBy(() -> executor(POLICY, () -> 0.0).execute(timeout -> {
            attempts[0]++;
            throw new Transient();
        }, e -> true)).isInstanceOf(Transient.class);

        assertThat(attempts[0]).isEqualTo(4);
    }

    @Test
    void backoffBoundDoublesAndIsCapped() {
        assertThatThrownBy(() -> executor(POLICY, () -> 1.0).execute(timeout -> {
            throw new Transient();
        }, e -> true)).isInstanceOf(Transient.class);

        // 100 ms, 200 ms, then 400 ms capped at 300 ms.
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(300));
    }

    @Test
    void jitterPicksAWaitBelowTheBound() {
        assertThatThrownBy(() -> executor(POLICY, () -> 0.25).execute(timeout -> {
            throw new Transient();
        }, e -> true)).isInstanceOf(Transient.class);

        assertThat(sleeps).containsExactly(Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(75));
    }

    @Test
    void stopsOnceTheOverallTimeIsUsedUpEvenWithAttemptsLeft() {
        assertThatThrownBy(() -> executor(POLICY, () -> 0.0).execute(timeout -> {
            timeouts.add(timeout);
            nowNanos += timeout.toNanos();
            throw new Transient();
        }, e -> true)).isInstanceOf(Transient.class);

        // The policy allows 4 attempts, but three 500 ms attempts spend the whole 1500 ms.
        assertThat(timeouts).containsExactly(
                Duration.ofMillis(500), Duration.ofMillis(500), Duration.ofMillis(500));
    }

    @Test
    void attemptTimeoutShrinksWhenLessThanItRemains() {
        assertThatThrownBy(() -> executor(POLICY, () -> 0.0).execute(timeout -> {
            timeouts.add(timeout);
            nowNanos += Duration.ofMillis(1200).toNanos();
            throw new Transient();
        }, e -> true)).isInstanceOf(Transient.class);

        // After 1200 ms of the 1500 ms budget, the second try gets the 300 ms that remain.
        assertThat(timeouts).containsExactly(Duration.ofMillis(500), Duration.ofMillis(300));
    }

    @Test
    void doesNotSleepPastTheDeadline() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(500), Duration.ofMillis(600),
                Duration.ofMillis(200), Duration.ofMillis(200));

        assertThatThrownBy(() -> executor(policy, () -> 1.0).execute(timeout -> {
            nowNanos += Duration.ofMillis(500).toNanos();
            throw new Transient();
        }, e -> true)).isInstanceOf(Transient.class);

        // 100 ms remained but the wait would be 200 ms, so it fails now instead of waiting.
        assertThat(sleeps).isEmpty();
    }

    @Test
    void policyNeedsAtLeastOneAttempt() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
