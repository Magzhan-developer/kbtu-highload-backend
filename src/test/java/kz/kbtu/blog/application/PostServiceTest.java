package kz.kbtu.blog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.domain.Post;
import kz.kbtu.blog.domain.exception.InvalidInputException;
import kz.kbtu.blog.domain.exception.PermissionDeniedException;
import kz.kbtu.blog.domain.exception.ResourceNotFoundException;
import kz.kbtu.blog.domain.exception.UnauthenticatedException;
import kz.kbtu.blog.persistence.PostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PostServiceTest extends AbstractIntegrationTest {

    @Autowired
    PostService postService;

    @Autowired
    CommentService commentService;

    @Autowired
    PostRepository posts;

    @Test
    void createdPostCanBeRetrievedWithIdenticalValues() {
        PostView created = postService.create(ALICE, "Title", "Content");

        assertThat(created.authorId()).isEqualTo(ALICE);
        assertThat(created.updatedAt()).isEqualTo(created.createdAt());
        // Equal timestamps prove nothing was lost to database rounding.
        assertThat(postService.get(created.id())).isEqualTo(created);
    }

    @Test
    void unicodeContentIsPreserved() {
        PostView created = postService.create(ALICE, "Қазақша 日本語", "Сәлем 🌍");

        assertThat(postService.get(created.id()).title()).isEqualTo("Қазақша 日本語");
        assertThat(postService.get(created.id()).content()).isEqualTo("Сәлем 🌍");
    }

    @Test
    void gettingAMissingPostFails() {
        assertThatThrownBy(() -> postService.get(UNKNOWN_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> postService.create(ALICE, " ", "Content"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> postService.create(ALICE, null, "Content"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> postService.create(ALICE, "x".repeat(InputRules.TITLE_MAX + 1), "Content"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> postService.create(ALICE, "Title", "x".repeat(InputRules.POST_CONTENT_MAX + 1)))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> postService.create(ALICE, "Title", "bad\0content"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void valuesAtTheLengthLimitsAreAccepted() {
        PostView created = postService.create(ALICE,
                "x".repeat(InputRules.TITLE_MAX), "x".repeat(InputRules.POST_CONTENT_MAX));

        assertThat(created.id()).isPositive();
    }

    @Test
    void unknownUserCannotCreateAPost() {
        assertThatThrownBy(() -> postService.create(UNKNOWN_USER, "Title", "Content"))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void authorCanUpdateAPost() throws InterruptedException {
        PostView created = postService.create(ALICE, "Title", "Content");
        Thread.sleep(2);

        PostView updated = postService.update(ALICE, created.id(), "New title", "New content");

        assertThat(updated.title()).isEqualTo("New title");
        assertThat(updated.content()).isEqualTo("New content");
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.updatedAt()).isAfter(created.updatedAt());
        assertThat(postService.get(created.id())).isEqualTo(updated);
    }

    @Test
    void onlyTheAuthorCanUpdateOrDeleteAPost() {
        PostView created = postService.create(ALICE, "Title", "Content");

        assertThatThrownBy(() -> postService.update(BOB, created.id(), "Hacked", "Hacked"))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> postService.delete(BOB, created.id()))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(postService.get(created.id())).isEqualTo(created);
    }

    @Test
    void updatingOrDeletingAMissingPostFails() {
        assertThatThrownBy(() -> postService.update(ALICE, UNKNOWN_ID, "Title", "Content"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> postService.delete(ALICE, UNKNOWN_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deletingAPostAlsoDeletesItsComments() {
        PostView post = postService.create(ALICE, "Title", "Content");
        commentService.create(BOB, post.id(), "Comment");

        postService.delete(ALICE, post.id());

        assertThatThrownBy(() -> postService.get(post.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments", Long.class)).isZero();
    }

    @Test
    void authorFeedWalksEveryPostExactlyOnceNewestFirst() {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            expected.add(0, postService.create(ALICE, "Post " + i, "Content").id());
        }
        postService.create(BOB, "Other author", "Content");

        assertThat(readWholeFeed(ALICE, 3)).isEqualTo(expected);
    }

    @Test
    void feedHandlesPostsWithIdenticalTimestamps() {
        // A cursor on created_at alone would lose rows here: the id breaks the tie.
        Instant sameTime = Instant.parse("2026-10-31T10:00:00.123456Z");
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(0, posts.save(new Post(ALICE, "Post " + i, "Content", sameTime)).getId());
        }

        assertThat(readWholeFeed(ALICE, 2)).isEqualTo(expected);
    }

    @Test
    void lastPageHasNoCursorWhenPostCountEqualsPageSize() {
        postService.create(ALICE, "One", "Content");
        postService.create(ALICE, "Two", "Content");

        CursorPage<PostView> page = postService.listByAuthor(ALICE, 2, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void feedUsesDefaultLimitWhenNotSet() {
        for (int i = 0; i < CursorPage.DEFAULT_LIMIT + 1; i++) {
            postService.create(ALICE, "Post " + i, "Content");
        }

        assertThat(postService.listByAuthor(ALICE, 0, null).items()).hasSize(CursorPage.DEFAULT_LIMIT);
    }

    @Test
    void feedRejectsLimitsOutOfRange() {
        assertThatThrownBy(() -> postService.listByAuthor(ALICE, -1, null))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> postService.listByAuthor(ALICE, CursorPage.MAX_LIMIT + 1, null))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void feedRejectsMalformedCursor() {
        assertThatThrownBy(() -> postService.listByAuthor(ALICE, 10, "garbage"))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void cursorWithTheLargestAllowedTimestampDoesNotReachPostgresAsAnError() {
        postService.create(ALICE, "Post", "Content");
        String farFuture = new Cursor(Instant.EPOCH.plusSeconds(Long.MAX_VALUE / 1_000_000), Long.MAX_VALUE).encode();

        assertThat(postService.listByAuthor(ALICE, 10, farFuture).items()).hasSize(1);
    }

    @Test
    void feedOfUserWithNoPostsIsEmptyButUnknownUserIsNotFound() {
        CursorPage<PostView> page = postService.listByAuthor(BOB, 10, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThatThrownBy(() -> postService.listByAuthor(UNKNOWN_USER, 10, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private List<Long> readWholeFeed(long authorId, int pageSize) {
        List<Long> ids = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<PostView> page = postService.listByAuthor(authorId, pageSize, cursor);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(pageSize);
            page.items().forEach(post -> ids.add(post.id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return ids;
    }
}
