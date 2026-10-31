package kz.kbtu.blog.api.rest;

import java.net.URI;
import kz.kbtu.blog.api.DevAuth;
import kz.kbtu.blog.application.PostService;
import kz.kbtu.blog.application.PostView;
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
public class PostController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PostService posts;

    public PostController(PostService posts) {
        this.posts = posts;
    }

    @PostMapping("/posts")
    public ResponseEntity<PostResponse> create(
            @RequestHeader(value = DevAuth.USER_ID_HEADER, required = false) String userIdHeader,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody PostRequest request) {
        long userId = DevAuth.parseUserId(userIdHeader);
        PostView post = posts.create(userId, idempotencyKey, request.title(), request.content());
        return ResponseEntity.created(URI.create("/api/v1/posts/" + post.id()))
                .body(PostResponse.from(post));
    }

    @GetMapping("/posts/{postId}")
    public PostResponse get(@PathVariable long postId) {
        return PostResponse.from(posts.get(postId));
    }

    @GetMapping("/users/{userId}/posts")
    public PageResponse<PostResponse> listByAuthor(
            @PathVariable long userId,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(required = false) String cursor) {
        return PageResponse.from(posts.listByAuthor(userId, limit, cursor), PostResponse::from);
    }

    @PutMapping("/posts/{postId}")
    public PostResponse update(
            @RequestHeader(value = DevAuth.USER_ID_HEADER, required = false) String userIdHeader,
            @PathVariable long postId,
            @RequestBody PostRequest request) {
        long userId = DevAuth.parseUserId(userIdHeader);
        return PostResponse.from(posts.update(userId, postId, request.title(), request.content()));
    }

    @DeleteMapping("/posts/{postId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestHeader(value = DevAuth.USER_ID_HEADER, required = false) String userIdHeader,
            @PathVariable long postId) {
        posts.delete(DevAuth.parseUserId(userIdHeader), postId);
    }
}
