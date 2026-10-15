package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@Tag("2025")
class JdbcUtilQuotedSqlIntegrationTest {

    @Test
    void ambiguousQuotedWritesStayInTheTransactionAcrossDialects() throws SQLException {
        for (final String product : new String[] { "PostgreSQL", "MySQL", "MariaDB", "Microsoft SQL Server", "H2" }) {
            final DataSource ds = mockDataSource(product);
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.DEFAULT, true);
            try {
                for (final String sql : new String[] { "SELECT '\\' INTO archive", "SELECT 'a\\'b' INTO archive", "SELECT E'a\\'b' INTO archive",
                        "SELECT \"a\\\" INTO archive FROM source", "SELECT '\\' AS ordinary, E'a\\'b' AS escaped FROM FINAL TABLE (UPDATE source SET qty = 42)",
                        "WITH data AS (SELECT '\\' AS value) UPDATE source SET qty = 42" }) {
                    assertSame(tran, JdbcUtil.getTransaction(ds, sql + " /* dialect lookup */", SqlTransaction.CreatedBy.JDBC_UTIL), product + ": " + sql);
                }
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }
    }

    @Test
    void escapeStringPrefixFollowsTheDatabaseDialect() throws SQLException {
        // PostgreSQL-family products and Vertica support E'...' strings. In the other tested dialects, e'\' is a name
        // (or MySQL's user variable @e) followed by an ordinary string whose backslash does not escape the closing quote.
        // The INTO clause is real and the statement must stay in the transaction. Look up the dialect even without a
        // comment or bracket. The CTE fixture only classifies as a write when the CTE scanner also drops the prefix:
        // read as an escape string, its body swallows the UPDATE and a plain SELECT follows the CTE.
        final String[] generic = { "SELECT e'\\' INTO archive FROM (VALUES (1)) AS t(e)", "SELECT E'\\' INTO archive FROM source /* comment */",
                "WITH data AS (SELECT e'\\') UPDATE source SET qty = 42 WHERE note = ') SELECT 1'" };
        final String[] mySqlOnly = { "SELECT @e'\\' INTO @out", "SELECT @e'\\' INTO OUTFILE '/tmp/out'",
                "WITH data AS (SELECT @e'\\') UPDATE source SET qty = 42 WHERE note = ') SELECT 1'" };
        for (final String product : new String[] { "MySQL", "MariaDB", "Microsoft SQL Server", "H2", "Oracle", "DB2/LINUXX8664" }) {
            final DataSource ds = mockDataSource(product);
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.DEFAULT, true);
            try {
                for (final String sql : generic) {
                    assertSame(tran, JdbcUtil.getTransaction(ds, sql, SqlTransaction.CreatedBy.JDBC_UTIL), product + ": " + sql);
                }
                if (product.equals("MySQL") || product.equals("MariaDB")) {
                    for (final String sql : mySqlOnly) {
                        assertSame(tran, JdbcUtil.getTransaction(ds, sql, SqlTransaction.CreatedBy.JDBC_UTIL), product + ": " + sql);
                    }
                }
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }

        // PostgreSQL and its derivatives read the same text as an escape string, which hides the INTO and the UPDATE.
        // Vertica (reported as "Vertica Database") shares that lexer and also has SELECT ... INTO TABLE: an escape
        // string followed by an ordinary backslash string hides the INTO from a scanner that ignores the E prefix,
        // so the write must still join the transaction.
        final String verticaWrite = "SELECT E'a\\'b' AS escaped, '\\' AS ordinary INTO TABLE archive";
        for (final String product : new String[] { "PostgreSQL", "EnterpriseDB", "Redshift", "Vertica Database" }) {
            final DataSource ds = mockDataSource(product);
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.DEFAULT, true);
            try {
                for (final String sql : generic) {
                    assertNull(JdbcUtil.getTransaction(ds, sql, SqlTransaction.CreatedBy.JDBC_UTIL), product + ": " + sql);
                }
                assertSame(tran, JdbcUtil.getTransaction(ds, verticaWrite, SqlTransaction.CreatedBy.JDBC_UTIL), product + ": " + verticaWrite);
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }
    }

    @Test
    void nonAsciiIdentifierCharactersEndANameBeforeAnEscapeStringPrefix() throws SQLException {
        // PostgreSQL identifiers may contain any non-ASCII character, including combining marks that
        // Character.isLetterOrDigit rejects: the e in "foóe'" ends the type name instead of opening an E-string.
        final DataSource ds = mockDataSource("PostgreSQL");
        final SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.DEFAULT, true);
        try {
            for (final String sql : new String[] { "SELECT x::foóe'\\' INTO archive FROM source", "SELECT x::fooée'\\' INTO archive FROM source",
                    "SELECT payload[LENGTH(foóe'\\')] INTO archive FROM source" }) {
                assertSame(tran, JdbcUtil.getTransaction(ds, sql, SqlTransaction.CreatedBy.JDBC_UTIL), sql);
            }
            assertNull(JdbcUtil.getTransaction(ds, "SELECT x::foó e'\\' INTO archive FROM source", SqlTransaction.CreatedBy.JDBC_UTIL));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    void explicitEscapeStringsAndDoubledDelimitersKeepPlainReadsOutsideTheTransaction() throws SQLException {
        final DataSource ds = mockDataSource("PostgreSQL");
        final SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.DEFAULT, true);
        try {
            for (final String sql : new String[] { "SELECT E'a\\' INTO archive'", "SELECT 'a'' INTO archive'", "SELECT '\\' AS value",
                    "SELECT '\\' AS ordinary, E'a\\' INTO archive' AS escaped", "SELECT payload[LENGTH('\\') + LENGTH(E'a\\' INTO archive')] FROM source" }) {
                assertNull(JdbcUtil.getTransaction(ds, sql, SqlTransaction.CreatedBy.JDBC_UTIL), sql);
            }
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    void continuedEscapeStringsKeepTheirEscapeRulesAcrossLines() throws SQLException {
        final DataSource ds = mockDataSource("PostgreSQL");
        final SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.DEFAULT, true);
        try {
            // PostgreSQL requires E only before the first segment of a newline-continued escape string.
            for (final String sql : new String[] { "SELECT E'prefix'\n'a\\' INTO archive' AS value",
                    "SELECT E'prefix' -- comment with ' and INTO archive\n'a\\' INTO archive' AS value",
                    "SELECT E'prefix'\r\n-- another comment\r\n'a\\' INTO archive' AS value",
                    "SELECT e'prefix'\t\f\u000B\r'a\\' INTO archive'\n'b\\' INTO archive' AS value",
                    "WITH data AS (SELECT E'prefix'\r\n'a\\' (UPDATE source SET qty = 42)' AS value) SELECT * FROM data",
                    "SELECT payload[LENGTH(E'prefix'\n'a\\' ] INTO archive')] FROM source" }) {
                assertNull(JdbcUtil.getTransaction(ds, sql, SqlTransaction.CreatedBy.JDBC_UTIL), sql);
            }
            assertSame(tran,
                    JdbcUtil.getTransaction(ds, "SELECT E'prefix'\n'a\\' INTO archive' AS value INTO real_archive", SqlTransaction.CreatedBy.JDBC_UTIL));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    void independentStringsDoNotInheritAnEarlierEscapePrefix() throws SQLException {
        final DataSource ds = mockDataSource("PostgreSQL");
        final SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.DEFAULT, true);
        try {
            // Same-line adjacency and block comments do not continue a PostgreSQL string literal.
            for (final String sql : new String[] { "SELECT E'prefix', 'a\\' AS value INTO real_archive -- '",
                    "SELECT E'prefix' 'a\\' AS value INTO real_archive -- '", "SELECT E'prefix',\n'a\\' AS value INTO real_archive -- '",
                    "SELECT E'prefix' ||\n'a\\' AS value INTO real_archive -- '", "SELECT E'prefix', /* comment */ 'a\\' AS value INTO real_archive -- '",
                    "SELECT E'prefix' /* comment\n */ 'a\\' AS value INTO real_archive -- '",
                    "SELECT E'prefix'\n/* comment */ 'a\\' AS value INTO real_archive -- '" }) {
                assertSame(tran, JdbcUtil.getTransaction(ds, sql, SqlTransaction.CreatedBy.JDBC_UTIL), sql);
            }
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    void backslashBeforeAClosingQuoteDoesNotHideAnEmbeddedWrite() throws SQLException {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:quoted_sql_" + UUID.randomUUID());

        try (Connection observer = ds.getConnection();
             Statement stmt = observer.createStatement()) {
            stmt.execute("CREATE TABLE scan_widget (id INT PRIMARY KEY, qty INT)");
            stmt.execute("INSERT INTO scan_widget VALUES (1, 1)");

            final SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.DEFAULT, true);
            try {
                final int updatedQty = JdbcUtil.prepareQuery(ds, "SELECT '\\' AS marker, qty FROM FINAL TABLE (UPDATE scan_widget SET qty = 42 WHERE id = 1)")
                        .query((Jdbc.ResultExtractor<Integer>) rs -> {
                            assertTrue(rs.next());
                            assertEquals("\\", rs.getString(1));
                            return rs.getInt(2);
                        });
                assertEquals(42, updatedQty);
            } finally {
                tran.rollbackIfNotCommitted();
            }

            try (ResultSet rows = stmt.executeQuery("SELECT qty FROM scan_widget WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "The embedded UPDATE must roll back with its for-update-only transaction");
            }
        }
    }

    @Test
    void backslashBeforeAClosingQuoteInAnArraySubscriptDoesNotHideAnEmbeddedWrite() throws SQLException {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:quoted_subscript_" + UUID.randomUUID());

        try (Connection observer = ds.getConnection();
             Statement stmt = observer.createStatement()) {
            stmt.execute("CREATE TABLE scan_widget (id INT PRIMARY KEY, qty INT)");
            stmt.execute("INSERT INTO scan_widget VALUES (1, 1)");

            final SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.DEFAULT, true);
            try {
                final int value = JdbcUtil
                        .prepareQuery(ds, "SELECT (ARRAY[10, 20])[LENGTH('\\') * (SELECT qty FROM FINAL TABLE (UPDATE scan_widget SET qty = 2 WHERE id = 1))]")
                        .query((Jdbc.ResultExtractor<Integer>) rs -> {
                            assertTrue(rs.next());
                            return rs.getInt(1);
                        });
                assertEquals(20, value);
            } finally {
                tran.rollbackIfNotCommitted();
            }

            try (ResultSet rows = stmt.executeQuery("SELECT qty FROM scan_widget WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "The subscript's embedded UPDATE must roll back with its for-update-only transaction");
            }
        }
    }

    private static DataSource mockDataSource(final String product) throws SQLException {
        final DataSource ds = mock(DataSource.class);
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getAutoCommit()).thenReturn(true);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        when(metadata.getDatabaseProductVersion()).thenReturn("8.0.36");
        return ds;
    }
}
