package kz.kbtu.blog.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import kz.kbtu.blog.AbstractIntegrationTest;
import kz.kbtu.blog.api.rest.RestTestClient.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;

class ErrorHandlingApiTest extends AbstractIntegrationTest {

    private static final String VALID_POST = "{\"title\":\"Title\",\"content\":\"Content\"}";
    private static final String ALICE_ID = String.valueOf(ALICE);
    private static final String BOB_ID = String.valueOf(BOB);

    @LocalServerPort
    int port;

    private RestTestClient api;

    @BeforeEach
    void createClient() {
        api = new RestTestClient(port);
    }

    @Test
    void missingUserHeaderIs401() throws Exception {
        assertError(api.post("/api/v1/posts", null, VALID_POST), 401, "UNAUTHENTICATED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
    void malformedUserHeaderIs401(String header) throws Exception {
        assertError(api.post("/api/v1/posts", header, VALID_POST), 401, "UNAUTHENTICATED");
    }

    @Test
    void unknownUserIs401() throws Exception {
        assertError(api.post("/api/v1/posts", String.valueOf(UNKNOWN_USER), VALID_POST), 401, "UNAUTHENTICATED");
    }

    @Test
    void changingSomeoneElsesPostIs403() throws Exception {
        long postId = api.post("/api/v1/posts", ALICE_ID, VALID_POST).json().get("postId").asLong();

        assertError(api.put("/api/v1/posts/" + postId, BOB_ID, VALID_POST), 403, "PERMISSION_DENIED");
        assertError(api.delete("/api/v1/posts/" + postId, BOB_ID), 403, "PERMISSION_DENIED");
    }

    @Test
    void missingResourcesAre404() throws Exception {
        assertError(api.get("/api/v1/posts/" + UNKNOWN_ID), 404, "NOT_FOUND");
        assertError(api.get("/api/v1/users/" + UNKNOWN_USER + "/posts"), 404, "NOT_FOUND");
        assertError(api.get("/api/v1/posts/" + UNKNOWN_ID + "/comments"), 404, "NOT_FOUND");
        assertError(api.put("/api/v1/posts/" + UNKNOWN_ID, ALICE_ID, VALID_POST), 404, "NOT_FOUND");
        assertError(api.delete("/api/v1/comments/" + UNKNOWN_ID, ALICE_ID), 404, "NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\" \",\"content\":\"Content\"}",
            "{\"content\":\"Content\"}",
            "{\"title\":\"Title\"}",
            "{}"})
    void invalidFieldsAre400(String body) throws Exception {
        assertError(api.post("/api/v1/posts", ALICE_ID, body), 400, "INVALID_ARGUMENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{not json", "null", "[]"})
    void unreadableBodiesAre400(String body) throws Exception {
        assertError(api.post("/api/v1/posts", ALICE_ID, body), 400, "INVALID_ARGUMENT");
    }

    @Test
    void emptyBodyIs400() throws Exception {
        assertError(api.post("/api/v1/posts", ALICE_ID, null), 400, "INVALID_ARGUMENT");
    }

    @Test
    void nonNumericIdsAre400() throws Exception {
        assertError(api.get("/api/v1/posts/abc"), 400, "INVALID_ARGUMENT");
        assertError(api.get("/api/v1/posts/99999999999999999999"), 400, "INVALID_ARGUMENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=-1", "limit=101", "limit=abc", "cursor=garbage"})
    void badPaginationParametersAre400(String query) throws Exception {
        assertError(api.get("/api/v1/users/" + ALICE + "/posts?" + query), 400, "INVALID_ARGUMENT");
    }

    @Test
    void frameworkErrorsKeepTheirStatusAndUseOurFormat() throws Exception {
        assertError(api.send("PATCH", "/api/v1/posts/1", ALICE_ID, VALID_POST, "application/json"),
                405, "METHOD_NOT_ALLOWED");
        assertError(api.send("POST", "/api/v1/posts", ALICE_ID, VALID_POST, "text/plain"),
                415, "UNSUPPORTED_MEDIA_TYPE");
        assertError(api.get("/api/v1/no-such-endpoint"), 404, "NOT_FOUND");
    }

    private static void assertError(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.json().get("code").asText()).isEqualTo(code);
        assertThat(response.json().get("message").asText()).isNotBlank();
    }
}
