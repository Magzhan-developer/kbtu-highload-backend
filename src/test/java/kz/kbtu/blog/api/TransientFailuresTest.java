package kz.kbtu.blog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.TransactionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

class TransientFailuresTest {

    @Test
    void springDatabaseFailuresAreTransient() {
        assertThat(TransientFailures.isDatabaseUnavailable(new CannotCreateTransactionException("no connection")))
                .isTrue();
        assertThat(TransientFailures.isDatabaseUnavailable(new QueryTimeoutException("too slow"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"08001", "08003", "08006", "57P01", "57P02", "57P03"})
    void connectionStatesAreTransientEvenWhenWrapped(String sqlState) {
        Throwable wrapped = new JpaSystemException(new RuntimeException(new SQLException("lost", sqlState)));

        assertThat(TransientFailures.isDatabaseUnavailable(wrapped)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"23505", "23503", "42P01", "22001"})
    void dataAndSchemaErrorsAreNotTransient(String sqlState) {
        Throwable wrapped = new JpaSystemException(new RuntimeException(new SQLException("bad", sqlState)));

        assertThat(TransientFailures.isDatabaseUnavailable(wrapped)).isFalse();
    }

    @Test
    void failedRollbackOnAClosedConnectionIsTransient() {
        Throwable rollbackFailure = new JpaSystemException(new TransactionException(
                "Unable to rollback against JDBC Connection", new SQLException("Connection is closed")));

        assertThat(TransientFailures.isDatabaseUnavailable(rollbackFailure)).isTrue();
    }

    @Test
    void transactionMisuseWithoutAJdbcCauseIsNotTransient() {
        Throwable misuse = new JpaSystemException(new TransactionException("nested transactions not supported"));

        assertThat(TransientFailures.isDatabaseUnavailable(misuse)).isFalse();
    }

    @Test
    void otherErrorsAreNotTransient() {
        assertThat(TransientFailures.isDatabaseUnavailable(new IllegalStateException("bug"))).isFalse();
        assertThat(TransientFailures.isDatabaseUnavailable(new SQLException("no state"))).isFalse();
    }
}
