package kz.kbtu.blog.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import kz.kbtu.blog.client.BlogClient;
import kz.kbtu.blog.client.CallResult;

/** Records every call. Set {@code failEvery} to make each n-th call fail. */
class FakeBlogClient implements BlogClient {

    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final AtomicLong attempts = new AtomicLong();
    int failEvery;

    private CallResult answer(String call) throws Exception {
        calls.add(call);
        if (failEvery > 0 && attempts.incrementAndGet() % failEvery == 0) {
            throw new IllegalStateException("scripted failure");
        }
        return new CallResult(1, 10, 100);
    }

    @Override
    public CallResult createPost(long userId, String idempotencyKey, String title, String content) throws Exception {
        return answer("createPost " + userId + " " + idempotencyKey + " " + title);
    }

    @Override
    public CallResult createComment(long userId, String idempotencyKey, long postId, String content) throws Exception {
        return answer("createComment " + userId + " " + idempotencyKey + " " + postId);
    }

    @Override
    public CallResult getPost(long postId) throws Exception {
        return answer("getPost " + postId);
    }

    @Override
    public CallResult getAuthorPosts(long authorId, int limit) throws Exception {
        return answer("getAuthorPosts " + authorId);
    }

    @Override
    public CallResult getComments(long postId, int limit) throws Exception {
        return answer("getComments " + postId);
    }

    @Override
    public long attemptCount() {
        return calls.size();
    }

    @Override
    public void close() {
    }
}
