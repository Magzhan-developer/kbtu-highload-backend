package kz.kbtu.blog;

import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Runs tests against a real PostgreSQL. One container is shared by all test classes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "grpc.server.port=0")
public abstract class AbstractIntegrationTest {

    // Ids assigned by V2__seed_dev_users.sql.
    protected static final long ALICE = 1L;
    protected static final long BOB = 2L;
    protected static final long UNKNOWN_USER = 999_999L;
    protected static final long UNKNOWN_ID = 999_999_999L;

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE posts, comments, idempotency_records");
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** Waits until this many database sessions are blocked on a lock, to prove requests overlap. */
    protected void awaitBlockedSessions(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND datname = current_database()
                """, Long.class) >= count);
    }
}
