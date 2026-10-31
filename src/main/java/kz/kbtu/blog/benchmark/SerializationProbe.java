package kz.kbtu.blog.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import kz.kbtu.blog.api.rest.PageResponse;
import kz.kbtu.blog.api.rest.PostResponse;
import kz.kbtu.blog.grpc.v1.PostListResponse;

/**
 * Times JSON and Protobuf alone, with no network, server or database, on the same data.
 * The end-to-end numbers mix transport and serialization. This isolates the serialization
 * share, which shows how much of an end-to-end gap it could possibly explain.
 *
 * <p>It is a simple loop rather than a JMH benchmark, so read the numbers as indicative.
 */
final class SerializationProbe {

    record Sample(String format, String payload, int bytes, double serializeMicros, double parseMicros) {
    }

    private static final int WARMUP_LOOPS = 30_000;
    private static final int MEASURED_LOOPS = 30_000;
    private static final Instant CREATED_AT = Instant.parse("2026-10-31T10:15:30.123456Z");

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // Keeps results alive so the JIT compiler cannot remove the work being timed.
    private long sink;

    List<Sample> run() throws Exception {
        List<Sample> samples = new ArrayList<>();
        for (int items : List.of(1, 20)) {
            samples.add(measureJson(items));
            samples.add(measureProtobuf(items));
        }
        return samples;
    }

    private Sample measureJson(int items) throws Exception {
        List<PostResponse> posts = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            posts.add(new PostResponse(1_000_000L + i, 500L, "Seed post " + i, "x".repeat(1_000), CREATED_AT, CREATED_AT));
        }
        PageResponse<PostResponse> page = new PageResponse<>(posts, "MTc5MzQxNDIwMjE0MTczMDo5ODc2NTQ");
        byte[] bytes = mapper.writeValueAsBytes(page);
        TypeReference<PageResponse<PostResponse>> type = new TypeReference<>() {
        };
        double serialize = timeMicros(() -> mapper.writeValueAsBytes(page).length);
        double parse = timeMicros(() -> mapper.readValue(bytes, type).items().size());
        return new Sample("JSON", label(items), bytes.length, serialize, parse);
    }

    private Sample measureProtobuf(int items) throws Exception {
        Timestamp timestamp = Timestamp.newBuilder()
                .setSeconds(CREATED_AT.getEpochSecond()).setNanos(CREATED_AT.getNano()).build();
        PostListResponse.Builder list = PostListResponse.newBuilder().setNextCursor("MTc5MzQxNDIwMjE0MTczMDo5ODc2NTQ");
        for (int i = 0; i < items; i++) {
            list.addItems(kz.kbtu.blog.grpc.v1.PostResponse.newBuilder()
                    .setPostId(1_000_000L + i).setAuthorId(500L).setTitle("Seed post " + i).setContent("x".repeat(1_000))
                    .setCreatedAt(timestamp).setUpdatedAt(timestamp));
        }
        PostListResponse message = list.build();
        byte[] bytes = message.toByteArray();
        double serialize = timeMicros(() -> message.toByteArray().length);
        double parse = timeMicros(() -> PostListResponse.parseFrom(bytes).getItemsCount());
        return new Sample("Protobuf", label(items), bytes.length, serialize, parse);
    }

    private double timeMicros(Callable<Integer> work) throws Exception {
        for (int i = 0; i < WARMUP_LOOPS; i++) {
            sink += work.call();
        }
        long startedAt = System.nanoTime();
        for (int i = 0; i < MEASURED_LOOPS; i++) {
            sink += work.call();
        }
        return (System.nanoTime() - startedAt) / 1e3 / MEASURED_LOOPS;
    }

    private static String label(int items) {
        return items + (items == 1 ? " post" : " posts");
    }
}
