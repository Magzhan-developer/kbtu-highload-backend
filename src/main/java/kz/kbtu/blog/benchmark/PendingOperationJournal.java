package kz.kbtu.blog.benchmark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers each write's idempotency key before it is sent. If the benchmark dies while a
 * write is in flight, nobody knows whether it succeeded, so the next start resends it with
 * the same key instead of risking a duplicate.
 *
 * <p>Writing here is the client-side version of a browser keeping its key in local storage,
 * so that a page refresh does not create a new key.
 */
final class PendingOperationJournal implements AutoCloseable {

    record PendingOperation(String key, BenchmarkOperation operation, long userId, long postId, String title) {
    }

    private static final String PENDING = "P";
    private static final String DONE = "D";

    private final Path file;
    private final Map<String, PendingOperation> pending = new LinkedHashMap<>();
    private final BufferedWriter writer;

    PendingOperationJournal(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            load();
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t");
            if (fields.length == 6 && fields[0].equals(PENDING)) {
                pending.put(fields[1], new PendingOperation(fields[1], BenchmarkOperation.valueOf(fields[2]),
                        Long.parseLong(fields[3]), Long.parseLong(fields[4]), fields[5]));
            } else if (fields.length == 2 && fields[0].equals(DONE)) {
                pending.remove(fields[1]);
            }
            // Anything else is a last line cut short by a crash. Its write was never sent.
        }
    }

    /** Must return before the write is sent. Flushed so it survives the process dying. */
    synchronized void recordPending(PendingOperation operation) {
        pending.put(operation.key(), operation);
        append(String.join("\t", PENDING, operation.key(), operation.operation().name(),
                String.valueOf(operation.userId()), String.valueOf(operation.postId()), operation.title()));
    }

    synchronized void recordDone(String key) {
        pending.remove(key);
        append(DONE + "\t" + key);
    }

    synchronized List<PendingOperation> pendingOperations() {
        return new ArrayList<>(pending.values());
    }

    private void append(String line) {
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Drops finished entries from the file, so it only grows during a run. */
    @Override
    public synchronized void close() throws IOException {
        writer.close();
        List<String> remaining = new ArrayList<>();
        for (PendingOperation operation : pending.values()) {
            remaining.add(String.join("\t", PENDING, operation.key(), operation.operation().name(),
                    String.valueOf(operation.userId()), String.valueOf(operation.postId()), operation.title()));
        }
        Files.write(file, remaining, StandardCharsets.UTF_8);
    }
}
