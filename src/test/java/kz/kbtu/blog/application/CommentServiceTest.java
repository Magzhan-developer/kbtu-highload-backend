package kz.kbtu.blog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.domain.exception.InvalidInputException;
import kz.kbtu.blog.domain.exception.PermissionDeniedException;
import kz.kbtu.blog.domain.exception.ResourceNotFoundException;
import kz.kbtu.blog.domain.exception.UnauthenticatedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommentServiceTest extends AbstractIntegrationTest {

    @Autowired
    PostService postService;

    @Autowired
    CommentService commentService;

    private long postId;

    @BeforeEach
    void createPost() {
        postId = postService.create(ALICE, newKey(), "Title", "Content").id();
    }

    @Test
    void createdCommentAppearsInThePostsComments() {
        CommentView created = commentService.create(BOB, newKey(), postId, "Nice post");

        assertThat(created.postId()).isEqualTo(postId);
        assertThat(created.userId()).isEqualTo(BOB);
        assertThat(commentService.listByPost(postId, 10, null).items()).containsExactly(created);
    }

    @Test
    void commentsOfOtherPostsAreNotReturned() {
        long otherPost = postService.create(ALICE, newKey(), "Other", "Content").id();
        commentService.create(BOB, newKey(), otherPost, "Elsewhere");

        assertThat(commentService.listByPost(postId, 10, null).items()).isEmpty();
    }

    @Test
    void commentingOnAMissingPostFailsAsNotFound() {
        assertThatThrownBy(() -> commentService.create(BOB, newKey(), UNKNOWN_ID, "Nice post"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unknownUserCannotComment() {
        assertThatThrownBy(() -> commentService.create(UNKNOWN_USER, newKey(), postId, "Nice post"))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void invalidContentIsRejected() {
        assertThatThrownBy(() -> commentService.create(BOB, newKey(), postId, ""))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> commentService.create(BOB, newKey(), postId, "x".repeat(InputRules.COMMENT_CONTENT_MAX + 1)))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void commentPagesWalkEveryCommentExactlyOnceNewestFirst() {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            expected.add(0, commentService.create(BOB, newKey(), postId, "Comment " + i).id());
        }

        List<Long> actual = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<CommentView> page = commentService.listByPost(postId, 3, cursor);
            page.items().forEach(comment -> actual.add(comment.id()));
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void listingCommentsOfAMissingPostFails() {
        assertThatThrownBy(() -> commentService.listByPost(UNKNOWN_ID, 10, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void authorCanUpdateAndDeleteAComment() {
        CommentView created = commentService.create(BOB, newKey(), postId, "Nice post");

        CommentView updated = commentService.update(BOB, created.id(), "Edited");

        assertThat(updated.content()).isEqualTo("Edited");
        assertThat(commentService.listByPost(postId, 10, null).items()).containsExactly(updated);

        commentService.delete(BOB, created.id());

        assertThat(commentService.listByPost(postId, 10, null).items()).isEmpty();
    }

    @Test
    void onlyTheCommentAuthorCanUpdateOrDeleteIt() {
        CommentView created = commentService.create(BOB, newKey(), postId, "Nice post");

        // Alice owns the post, but comment moderation is not a requirement.
        assertThatThrownBy(() -> commentService.update(ALICE, created.id(), "Hacked"))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> commentService.delete(ALICE, created.id()))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void updatingOrDeletingAMissingCommentFails() {
        assertThatThrownBy(() -> commentService.update(BOB, UNKNOWN_ID, "Edited"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> commentService.delete(BOB, UNKNOWN_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
