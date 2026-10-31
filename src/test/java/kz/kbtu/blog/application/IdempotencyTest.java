package kz.kbtu.blog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.domain.exception.IdempotencyConflictException;
import kz.kbtu.blog.domain.exception.InvalidInputException;
import kz.kbtu.blog.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

class IdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    PostService postService;

    @Autowired
    CommentService commentService;

    @Autowired
    IdempotencyService idempotency;

    @Test
    void sameKeyAndPayloadCreatesOnePostAndReturnsTheOriginalResult() {
        String key = newKey();

        PostView first = postService.create(ALICE, key, "Title", "Content");
        PostView second = postService.create(ALICE, key, "Title", "Content");

        assertThat(second).isEqualTo(first);
        assertThat(count("posts")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
    }

    @Test
    void sameKeyAndPayloadCreatesOneComment() {
        long postId = postService.create(ALICE, newKey(), "Title", "Content").id();
        String key = newKey();

        CommentView first = commentService.create(BOB, key, postId, "Nice post");
        CommentView second = commentService.create(BOB, key, postId, "Nice post");

        assertThat(second).isEqualTo(first);
        assertThat(count("comments")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        String key = newKey();
        postService.create(ALICE, key, "Title", "Content");

        assertThatThrownBy(() -> postService.create(ALICE, key, "Title", "Different content"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(count("posts")).isEqualTo(1);
    }

    @Test
    void sameKeyOnADifferentPostIsRejected() {
        long firstPost = postService.create(ALICE, newKey(), "One", "Content").id();
        long secondPost = postService.create(ALICE, newKey(), "Two", "Content").id();
        String key = newKey();
        commentService.create(BOB, key, firstPost, "Nice post");

        assertThatThrownBy(() -> commentService.create(BOB, key, secondPost, "Nice post"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void keysAreScopedToTheUser() {
        String key = newKey();

        PostView alices = postService.create(ALICE, key, "Title", "Content");
        PostView bobs = postService.create(BOB, key, "Title", "Content");

        assertThat(bobs.id()).isNotEqualTo(alices.id());
        assertThat(bobs.authorId()).isEqualTo(BOB);
        assertThat(count("posts")).isEqualTo(2);
    }

    @Test
    void keysAreScopedToTheOperation() {
        String key = newKey();
        long postId = postService.create(ALICE, key, "Title", "Content").id();

        CommentView comment = commentService.create(ALICE, key, postId, "Content");

        assertThat(comment.postId()).isEqualTo(postId);
    }

    @Test
    void storedResponseIsProtocolNeutral() {
        String key = newKey();
        PostView post = postService.create(ALICE, key, "Title", "Content");

        Map<String, Object> record = jdbc.queryForMap("SELECT * FROM idempotency_records");

        assertThat(record.get("user_id")).isEqualTo(ALICE);
        assertThat(record.get("operation_type")).isEqualTo("CREATE_POST");
        assertThat(record.get("idempotency_key")).hasToString(key);
        assertThat((String) record.get("request_hash")).hasSize(64);
        // A word rather than an HTTP code, and the view's fields rather than REST's "postId".
        assertThat(record.get("response_status")).isEqualTo("CREATED");
        assertThat((String) record.get("response_body"))
                .contains("\"id\":" + post.id())
                .doesNotContain("postId");
    }

    @Test
    void replayStillReturnsTheOriginalAfterTheResourceChanged() {
        String key = newKey();
        PostView created = postService.create(ALICE, key, "Title", "Content");
        postService.update(ALICE, created.id(), "Edited", "Edited");
        postService.delete(ALICE, created.id());

        // The key answers "what did my original request do?", not "what exists now?".
        assertThat(postService.create(ALICE, key, "Title", "Content")).isEqualTo(created);
        assertThat(count("posts")).isZero();
    }

    @Test
    void failedOperationLeavesNoRecordSoTheKeyIsNotBurned() {
        String key = newKey();

        assertThatThrownBy(() -> commentService.create(BOB, key, UNKNOWN_ID, "Nice post"))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(count("idempotency_records")).isZero();
        assertThat(count("comments")).isZero();
    }

    @Test
    void idempotencyServiceRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> idempotency.execute(ALICE, OperationType.CREATE_POST, UUID.randomUUID(), "hash",
                String.class, () -> "result"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("idempotency_records")).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "not-a-uuid", "1-1-1-1-1"})
    void invalidKeysAreRejected(String key) {
        assertThatThrownBy(() -> postService.create(ALICE, key, "Title", "Content"))
                .isInstanceOf(InvalidInputException.class);
        assertThat(count("idempotency_records")).isZero();
    }

    @Test
    void validatedKeyIsCaseInsensitive() {
        String key = newKey();

        PostView lower = postService.create(ALICE, key, "Title", "Content");
        PostView upper = postService.create(ALICE, key.toUpperCase(), "Title", "Content");

        assertThat(upper).isEqualTo(lower);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
