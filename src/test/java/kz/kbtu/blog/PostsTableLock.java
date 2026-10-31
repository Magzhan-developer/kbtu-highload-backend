package kz.kbtu.blog;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Holds a real PostgreSQL lock so every request touching the posts table stalls until
 * this is closed. Tests use it to freeze a request at a known point with no test hooks
 * in production code and no fragile sleeps.
 */
public final class PostsTableLock implements AutoCloseable {

    private final Connection connection;

    public PostsTableLock(DataSource dataSource) throws SQLException {
        connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("LOCK TABLE posts IN ACCESS EXCLUSIVE MODE");
        }
    }

    @Override
    public void close() throws SQLException {
        connection.rollback();
        connection.setAutoCommit(true);
        connection.close();
    }
}
