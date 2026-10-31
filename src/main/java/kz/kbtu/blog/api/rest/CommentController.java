package kz.kbtu.blog.api.rest;

import java.net.URI;
import kz.kbtu.blog.api.DevAuth;
import kz.kbtu.blog.application.CommentService;
import kz.kbtu.blog.application.CommentView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CommentController {

    private final CommentService comments;

    public CommentController(CommentService comments) {
        this.comments = comments;
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommentResponse> create(
            @RequestHeader(value = DevAuth.USER_ID_HEADER, required = false) String userIdHeader,
            @RequestHeader(value = PostController.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable long postId,
            @RequestBody CommentRequest request) {
        long userId = DevAuth.parseUserId(userIdHeader);
        CommentView comment = comments.create(userId, idempotencyKey, postId, request.content());
        return ResponseEntity.created(URI.create("/api/v1/comments/" + comment.id()))
                .body(CommentResponse.from(comment));
    }

    @GetMapping("/posts/{postId}/comments")
    public PageResponse<CommentResponse> listByPost(
            @PathVariable long postId,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(required = false) String cursor) {
        return PageResponse.from(comments.listByPost(postId, limit, cursor), CommentResponse::from);
    }

    @PutMapping("/comments/{commentId}")
    public CommentResponse update(
            @RequestHeader(value = DevAuth.USER_ID_HEADER, required = false) String userIdHeader,
            @PathVariable long commentId,
            @RequestBody CommentRequest request) {
        long userId = DevAuth.parseUserId(userIdHeader);
        return CommentResponse.from(comments.update(userId, commentId, request.content()));
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestHeader(value = DevAuth.USER_ID_HEADER, required = false) String userIdHeader,
            @PathVariable long commentId) {
        comments.delete(DevAuth.parseUserId(userIdHeader), commentId);
    }
}
