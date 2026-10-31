package kz.kbtu.blog.benchmark;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import kz.kbtu.blog.benchmark.SummaryReport.Environment;

/** Describes one run. Saved as run-metadata.json beside its CSV files. */
record RunMetadata(
        @JsonProperty("run_id") String runId,
        String label,
        @JsonProperty("started_at") String startedAt,
        @JsonProperty("finished_at") String finishedAt,
        long users,
        long posts,
        long comments,
        @JsonProperty("read_requests") int readRequests,
        @JsonProperty("write_requests") int writeRequests,
        @JsonProperty("warmup_requests") int warmupRequests,
        int iterations,
        @JsonProperty("concurrency_levels") List<Integer> concurrencyLevels,
        @JsonProperty("sample_size") int sampleSize,
        long seed,
        @JsonProperty("page_limit") int pageLimit,
        @JsonProperty("java_version") String javaVersion,
        int cores) {

    static RunMetadata of(BenchmarkConfig config, Environment environment) {
        BenchmarkDataset dataset = environment.dataset();
        return new RunMetadata(
                config.runId(), config.label(), environment.startedAt(), environment.finishedAt(),
                dataset.userCount(), dataset.postCount(), dataset.commentCount(),
                config.readRequests(), config.writeRequests(), config.warmupRequests(), config.iterations(),
                config.concurrencyLevels(), config.sampleSize(), config.seed(), config.pageLimit(),
                environment.javaVersion(), environment.cores());
    }

    void write(Path directory) throws IOException {
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(directory.resolve("run-metadata.json").toFile(), this);
    }
}
