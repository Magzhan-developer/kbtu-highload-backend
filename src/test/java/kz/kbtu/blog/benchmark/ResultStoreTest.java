package kz.kbtu.blog.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultStoreTest {

    @TempDir
    Path directory;

    @Test
    void existingResultsAreNeverOverwritten() throws Exception {
        Path existing = directory.resolve("raw-runs.csv");
        Files.writeString(existing, "measured data");

        assertThatThrownBy(() -> new ResultStore(directory)).isInstanceOf(FileAlreadyExistsException.class);

        assertThat(Files.readString(existing)).isEqualTo("measured data");
    }
}
