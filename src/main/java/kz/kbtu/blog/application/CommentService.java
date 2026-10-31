package kz.kbtu.blog.application;

import java.util.List;
import java.util.UUID;
import kz.kbtu.blog.domain.Comment;
import kz.kbtu.blog.domain.exception.PermissionDeniedException;
import kz.kbtu.blog.domain.exception.ResourceNotFoundException;
import kz.kbtu.blog.persistence.CommentRepository;
import kz.kbtu.blog.persistence.PostRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentService {

    private final CommentRepository comments;
    private final PostRepository posts;
    private final ActorGuard actors;
    private final IdempotencyService idempotency;

    public CommentService(
            CommentRepository comments, PostRepository posts, ActorGuard actors, IdempotencyService idempotency) {
        this.comments = comments;
        this.posts = posts;
        this.actors = actors;
        this.idempotency = idempotency;
    }

    // READ COMMITTED is required by IdempotencyService.replay, so it is stated explicitly.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommentView create(long userId, String idempotencyKey, long postId, String content) {
        InputRules.requireCommentContent(content);
        UUID key = InputRules.requireIdempotencyKey(idempotencyKey);
        actors.requireKnownUser(userId);
        String requestHash = RequestHash.of(String.valueOf(postId), content);
        return idempotency.execute(
                userId, OperationType.CREATE_COMMENT, key, requestHash, CommentView.class,
                () -> insertComment(userId, postId, content));
    }

    private CommentView insertComment(long userId, long postId, String content) {
        try {
            return CommentView.from(comments.save(new Comment(postId, userId, content, Timestamps.now())));
        } catch (DataIntegrityViolationException e) {
            // The foreign key is the check, so a post deleted at this moment is also caught.
            // The user was verified earlier, which leaves the post as the missing reference.
            throw new ResourceNotFoundException("post " + postId + " not found");
        }
    }

    @Transactional(readOnly = true)
    public CursorPage<CommentView> listByPost(long postId, int requestedLimit, String encodedCursor) {
        int limit = CursorPage.resolveLimit(requestedLimit);
        Cursor cursor = Cursor.decodeOrNull(encodedCursor);
        List<Comment> rows = cursor == null
                ? comments.findFirstPage(postId, limit + 1)
                : comments.findPageAfter(postId, cursor.createdAt(), cursor.id(), limit + 1);
        // Checked only when empty, so normal reads do not pay for an extra query.
        if (rows.isEmpty() && !posts.existsById(postId)) {
            throw new ResourceNotFoundException("post " + postId + " not found");
        }
        return CursorPage.fromRows(rows, limit, CommentView::from,
                comment -> new Cursor(comment.getCreatedAt(), comment.getId()));
    }

    @Transactional
    public CommentView update(long actorId, long commentId, String content) {
        InputRules.requireCommentContent(content);
        actors.requireKnownUser(actorId);
        Comment comment = findOwnedComment(actorId, commentId);
        comment.update(content, Timestamps.now());
        return CommentView.from(comment);
    }

    @Transactional
    public void delete(long actorId, long commentId) {
        actors.requireKnownUser(actorId);
        comments.delete(findOwnedComment(actorId, commentId));
    }

    private Comment findOwnedComment(long actorId, long commentId) {
        Comment comment = comments.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("comment " + commentId + " not found"));
        if (comment.getUserId() != actorId) {
            throw new PermissionDeniedException("only the author can change comment " + commentId);
        }
        return comment;
    }
}
