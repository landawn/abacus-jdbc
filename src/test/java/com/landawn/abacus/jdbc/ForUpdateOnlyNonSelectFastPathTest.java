package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

/**
 * A for-update-only transaction always joins a non-SELECT statement. Classifying a statement only depends on the database
 * through '[' (bracket identifier vs. array), so dialect-dependent text such as comments must not trigger a metadata lookup.
 */
@Tag("2025")
public class ForUpdateOnlyNonSelectFastPathTest extends TestBase {
    private DataSource dataSource;
    private DatabaseMetaData metadata;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getDatabaseProductVersion()).thenReturn("8.0.33");
    }

    @Test
    void dialectDependentNonSelectStatementsJoinWithoutReadingMetadata() throws SQLException {
        for (final String sql : new String[] { "UPDATE t SET a = 1 -- note", "UPDATE t SET a = 1 # note", "DELETE FROM t /* note */",
                "INSERT INTO t VALUES (1_000)", "WITH c AS (SELECT 1 /* x */) DELETE FROM t", "MERGE INTO t USING s ON (1 = 1) -- note\n" }) {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED, true);

            try {
                clearInvocations(metadata);
                assertSame(tran, JdbcUtil.getTransaction(dataSource, sql, SqlTransaction.CreatedBy.JDBC_UTIL), sql);
                verify(metadata, never()).getDatabaseProductName();
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }
    }

    @Test
    void dialectDependentSelectsAndBracketedStatementsStillReadMetadata() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            clearInvocations(metadata);
            assertNull(JdbcUtil.getTransaction(dataSource, "SELECT a FROM t -- note", SqlTransaction.CreatedBy.JDBC_UTIL));
            verify(metadata, atLeastOnce()).getDatabaseProductName();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        // '[' can change how a WITH statement is classified, so the dialect is still resolved; the write still joins.
        tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            clearInvocations(metadata);
            assertSame(tran, JdbcUtil.getTransaction(dataSource, "WITH c AS (SELECT a [x] FROM t) UPDATE t SET a = 1",
                    SqlTransaction.CreatedBy.JDBC_UTIL));
            verify(metadata, atLeastOnce()).getDatabaseProductName();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // A SELECT without '[' is classified once, by the fast path; the write scan must still run under the resolved dialect.
    @Test
    void bracketFreeSelectsStillScanForWritesAfterTheFastPath() throws SQLException {
        final String[][] cases = { { "SELECT a INTO @x FROM t -- note", "join" }, { "SELECT a FROM t -- copied into archive", "skip" },
                { "SELECT a FROM t # copied into archive", "skip" }, { "WITH u AS (UPDATE t SET a = 1 RETURNING a) SELECT * FROM u /* x */", "join" },
                { "WITH c AS (SELECT 1 /* x */) SELECT * FROM c", "skip" } };

        for (final String[] c : cases) {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED, true);

            try {
                final SqlTransaction joined = JdbcUtil.getTransaction(dataSource, c[0], SqlTransaction.CreatedBy.JDBC_UTIL);

                if ("join".equals(c[1])) {
                    assertSame(tran, joined, c[0]);
                } else {
                    assertNull(joined, c[0]);
                }
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }
    }

    @Test
    void unreadableMetadataStillJoinsNonSelectStatements() throws SQLException {
        when(metadata.getDatabaseProductName()).thenThrow(new SQLException("metadata unavailable"));
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            assertSame(tran, JdbcUtil.getTransaction(dataSource, "UPDATE t SET a = 1 -- note", SqlTransaction.CreatedBy.JDBC_UTIL));
            assertSame(tran, JdbcUtil.getTransaction(dataSource, "WITH c AS (SELECT a [x] FROM t) DELETE FROM t",
                    SqlTransaction.CreatedBy.JDBC_UTIL));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }
}
