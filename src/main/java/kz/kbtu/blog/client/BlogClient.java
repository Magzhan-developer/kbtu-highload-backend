package kz.kbtu.blog.client;

/**
 * The operations the benchmark compares. Every method retries under the same RetryPolicy.
 * Creates take the key from the caller, because it must outlive this call: a caller that
 * tries again later, even after a restart, has to send the same key.
 */
public interface BlogClient extends AutoCloseable {

    CallResult createPost(long userId, String idempotencyKey, String title, String content) throws Exception;

    CallResult createComment(long userId, String idempotencyKey, long postId, String content) throws Exception;

    CallResult getPost(long postId) throws Exception;

    CallResult getAuthorPosts(long authorId, int limit) throws Exception;

    CallResult getComments(long postId, int limit) throws Exception;

    @Override
    void close();
}
