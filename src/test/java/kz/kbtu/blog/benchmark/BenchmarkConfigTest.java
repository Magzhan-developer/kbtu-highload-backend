package kz.kbtu.blog.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BenchmarkConfigTest {

    @Test
    void everyRequiredSettingCanBeChanged() {
        BenchmarkConfig config = BenchmarkConfig.parse(
                "--requests=100", "--concurrency=2, 8", "--warmup=10", "--iterations=6", "--attempt-timeout-ms=250");

        assertThat(config.readRequests()).isEqualTo(100);
        assertThat(config.concurrencyLevels()).containsExactly(2, 8);
        assertThat(config.warmupRequests()).isEqualTo(10);
        assertThat(config.iterations()).isEqualTo(6);
        assertThat(config.retryPolicy().attemptTimeout()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void defaultsMatchTheSharedRetryPolicy() {
        BenchmarkConfig config = BenchmarkConfig.parse();

        assertThat(config.retryPolicy().maxAttempts()).isEqualTo(3);
        assertThat(config.retryPolicy().attemptTimeout()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void eachRunGetsItsOwnFolderButSharesThePendingJournal() {
        BenchmarkConfig config = BenchmarkConfig.parse("--run-id=run-7", "--label=nightly");

        assertThat(config.runId()).isEqualTo("run-7");
        assertThat(config.label()).isEqualTo("nightly");
        assertThat(config.outputDirectory()).isEqualTo(Path.of("benchmark-results", "run-7"));
        assertThat(config.journalFile()).isEqualTo(Path.of("benchmark-results", "pending-operations.log"));
    }

    @Test
    void generatedRunIdsAreUsableFolderNames() {
        assertThat(BenchmarkConfig.parse().runId()).matches("run-\\d{8}-\\d{6}");
    }

    @Test
    void unsafeRunIdsAreRejected() {
        assertThatThrownBy(() -> BenchmarkConfig.parse("--run-id=../run-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mistypedOptionsAreRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> BenchmarkConfig.parse("--request=100"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("request");
        assertThatThrownBy(() -> BenchmarkConfig.parse("requests=100"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
