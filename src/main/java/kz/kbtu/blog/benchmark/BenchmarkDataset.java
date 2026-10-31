package kz.kbtu.blog.benchmark;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ids sampled from the database before the benchmark, so requests target rows that exist.
 * Ordering by a seeded hash gives a random but repeatable sample.
 */
public record BenchmarkDataset(
        long[] postIds,
        long[] authorIds,
        long[] postIdsWithComments,
        long userCount,
        long postCount,
        long commentCount) {

    static BenchmarkDataset load(BenchmarkConfig config) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                config.dbUrl(), config.dbUser(), config.dbPassword())) {
            long[] postIds = sample(connection, "SELECT id AS sample_id FROM posts", config);
            long[] authorIds = sample(connection, "SELECT DISTINCT author_id AS sample_id FROM posts", config);
            long[] commented = sample(connection, "SELECT DISTINCT post_id AS sample_id FROM comments", config);
            if (postIds.length == 0 || authorIds.length == 0 || commented.length == 0) {
                throw new IllegalStateException("The database has no data. Run scripts/seed-dataset.sql first.");
            }
            return new BenchmarkDataset(postIds, authorIds, commented,
                    count(connection, "users"), count(connection, "posts"), count(connection, "comments"));
        }
    }

    private static long[] sample(Connection connection, String source, BenchmarkConfig config) throws SQLException {
        String sql = "SELECT sample_id FROM (" + source + ") s ORDER BY md5(sample_id::text || ?) LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, String.valueOf(config.seed()));
            statement.setInt(2, config.sampleSize());
            try (ResultSet rows = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
                return ids.stream().mapToLong(Long::longValue).toArray();
            }
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + table);
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
