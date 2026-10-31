package kz.kbtu.blog.api;

import java.sql.SQLException;
import org.hibernate.TransactionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Decides which failures are temporary, so REST and gRPC tell clients the same thing. */
public final class TransientFailures {

    private TransientFailures() {
    }

    /**
     * True when the database is unreachable or dropped the session, so a retry may succeed.
     * The Spring exception type alone is not enough, because a killed session surfaces as a
     * generic JpaSystemException in one of two ways:
     *
     * <ul>
     *   <li>the failed statement carries the PostgreSQL SQLSTATE in its cause chain;
     *   <li>the rollback that follows also fails on the closed connection, and that second
     *       failure replaces the first, so only Hibernate's TransactionException remains.
     * </ul>
     */
    public static boolean isDatabaseUnavailable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotCreateTransactionException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof TransientDataAccessException) {
                return true;
            }
            if (cause instanceof SQLException sqlFailure && isConnectionState(sqlFailure.getSQLState())) {
                return true;
            }
            if (cause instanceof TransactionException && cause.getCause() instanceof SQLException) {
                return true;
            }
        }
        return false;
    }

    // Class 08 is "connection exception". 57P01-57P03 mean the server is shutting down or restarting.
    private static boolean isConnectionState(String sqlState) {
        return sqlState != null && (sqlState.startsWith("08") || sqlState.startsWith("57P"));
    }
}
