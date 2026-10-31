package kz.kbtu.blog.application;

import java.util.List;
import kz.kbtu.blog.domain.Post;
import kz.kbtu.blog.domain.exception.PermissionDeniedException;
import kz.kbtu.blog.domain.exception.ResourceNotFoundException;
import kz.kbtu.blog.persistence.PostRepository;
import kz.kbtu.blog.persistence.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostService {

    private final PostRepository posts;
    private final UserRepository users;
    private final ActorGuard actors;

    public PostService(PostRepository posts, UserRepository users, ActorGuard actors) {
        this.posts = posts;
        this.users = users;
        this.actors = actors;
    }

    @Transactional
    public PostView create(long authorId, String title, String content) {
        InputRules.requireTitle(title);
        InputRules.requirePostContent(content);
        actors.requireKnownUser(authorId);
        return PostView.from(posts.save(new Post(authorId, title, content, Timestamps.now())));
    }

    @Transactional(readOnly = true)
    public PostView get(long postId) {
        return PostView.from(findPost(postId));
    }

    @Transactional(readOnly = true)
    public CursorPage<PostView> listByAuthor(long authorId, int requestedLimit, String encodedCursor) {
        int limit = CursorPage.resolveLimit(requestedLimit);
        Cursor cursor = Cursor.decodeOrNull(encodedCursor);
        List<Post> rows = cursor == null
                ? posts.findFirstFeedPage(authorId, limit + 1)
                : posts.findFeedPageAfter(authorId, cursor.createdAt(), cursor.id(), limit + 1);
        // Checked only when empty, so normal reads do not pay for an extra query.
        if (rows.isEmpty() && !users.existsById(authorId)) {
            throw new ResourceNotFoundException("user " + authorId + " not found");
        }
        return CursorPage.fromRows(rows, limit, PostView::from,
                post -> new Cursor(post.getCreatedAt(), post.getId()));
    }

    @Transactional
    public PostView update(long actorId, long postId, String title, String content) {
        InputRules.requireTitle(title);
        InputRules.requirePostContent(content);
        actors.requireKnownUser(actorId);
        Post post = findOwnedPost(actorId, postId);
        post.update(title, content, Timestamps.now());
        return PostView.from(post);
    }

    @Transactional
    public void delete(long actorId, long postId) {
        actors.requireKnownUser(actorId);
        posts.delete(findOwnedPost(actorId, postId));
    }

    private Post findPost(long postId) {
        return posts.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("post " + postId + " not found"));
    }

    private Post findOwnedPost(long actorId, long postId) {
        Post post = findPost(postId);
        if (post.getAuthorId() != actorId) {
            throw new PermissionDeniedException("only the author can change post " + postId);
        }
        return post;
    }
}
