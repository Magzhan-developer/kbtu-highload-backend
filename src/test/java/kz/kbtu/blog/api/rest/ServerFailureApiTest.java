package kz.kbtu.blog.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.api.rest.RestTestClient.Response;
import kz.kbtu.blog.application.PostService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.CannotCreateTransactionException;

/** A broken database is hard to arrange on demand, so the service is mocked to fail. */
class ServerFailureApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @MockitoBean
    PostService postService;

    @Test
    void noDatabaseConnectionIs503() throws Exception {
        when(postService.get(1L)).thenThrow(new CannotCreateTransactionException("pool exhausted"));

        assertUnavailable(new RestTestClient(port).get("/api/v1/posts/1"));
    }

    @Test
    void queryTimeoutIs503() throws Exception {
        when(postService.get(1L)).thenThrow(new QueryTimeoutException("too slow"));

        assertUnavailable(new RestTestClient(port).get("/api/v1/posts/1"));
    }

    @Test
    void unexpectedErrorIs500WithoutLeakingDetails() throws Exception {
        when(postService.get(1L)).thenThrow(new IllegalStateException("secret internal detail"));

        Response response = new RestTestClient(port).get("/api/v1/posts/1");

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.json().get("code").asText()).isEqualTo("INTERNAL");
        assertThat(response.raw().body()).doesNotContain("secret internal detail");
    }

    private static void assertUnavailable(Response response) {
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.json().get("code").asText()).isEqualTo("UNAVAILABLE");
    }
}
