package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("2025")
class JdbcBracketAliasRegressionTest {
    private static final SqlTransaction.CreatedBy CREATED_BY = SqlTransaction.CreatedBy.JDBC_UTIL;

    @Test
    void bracketAliasesCannotHideSelectIntoOrExposeKeywordsInsideTheAlias() throws Exception {
        for (String product : new String[] { "Microsoft SQL Server", "Adaptive Server Enterprise" }) {
            Connection conn = mock(Connection.class);
            DatabaseMetaData metadata = mock(DatabaseMetaData.class);
            when(conn.getMetaData()).thenReturn(metadata);
            when(metadata.getDatabaseProductName()).thenReturn(product);
            DataSource ds = dataSource(conn);
            SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
            try {
                // The first case is the exact SELECT INTO from the review. The rest cover other
                // tokens that must remain ordinary characters inside a bracket-delimited name.
                for (String alias : new String[] { "[x']", "[x\"]", "[x`]", "[x/*]", "[x--]", "[x$$]", "[x]]']", "[x(UPDATE t SET id=2)]" }) {
                    for (String value : new String[] { "name", "abs(id)", "t.name", "\"name\"", "array" }) {
                        String projection = "SELECT " + value + " " + alias;
                        assertSame(tran, JdbcUtil.getTransaction(ds, projection + " INTO copy FROM t WHERE name = 'a]'", CREATED_BY), projection);
                        assertNull(JdbcUtil.getTransaction(ds, projection + " FROM t WHERE name = 'a]'", CREATED_BY), projection);
                    }
                }
                verify(metadata, times(1)).getDatabaseProductName();
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }
    }

    @Test
    void cteOperationScanningKeepsBracketAliasesOpaque() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        DataSource ds = dataSource(conn);
        SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
        try {
            for (String alias : new String[] { "[x']", "[x(]", "[x]]']", "[x/*]" }) {
                String cte = "WITH c AS (SELECT name " + alias + " FROM t WHERE name = 'a]') ";
                assertNull(JdbcUtil.getTransaction(ds, cte + "SELECT * FROM c", CREATED_BY), cte);
                assertSame(tran, JdbcUtil.getTransaction(ds, cte + "SELECT * INTO copy FROM c", CREATED_BY), cte);
                assertSame(tran, JdbcUtil.getTransaction(ds, cte + "UPDATE t SET name = 'changed'", CREATED_BY), cte);
            }
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    void unavailableDialectCannotLetBracketAliasesHideWrites() throws Exception {
        for (String product : new String[] { null, "", "failure" }) {
            Connection conn = mock(Connection.class);
            DatabaseMetaData metadata = mock(DatabaseMetaData.class);
            when(conn.getMetaData()).thenReturn(metadata);
            if ("failure".equals(product)) {
                when(metadata.getDatabaseProductName()).thenThrow(new SQLException("metadata unavailable"));
            } else {
                when(metadata.getDatabaseProductName()).thenReturn(product);
            }
            DataSource ds = dataSource(conn);
            SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
            try {
                assertSame(tran, JdbcUtil.getTransaction(ds, "SELECT name [x'] INTO copy FROM t WHERE name = 'a]'", CREATED_BY));
                assertSame(tran, JdbcUtil.getTransaction(ds,
                        "WITH c AS (SELECT name [x'] FROM t WHERE name = 'a]') SELECT * INTO copy FROM c", CREATED_BY));
                assertSame(tran, JdbcUtil.getTransaction(ds, "SELECT ARRAY[ARRAY[1]] INTO copy FROM t", CREATED_BY));
                verify(metadata, times(1)).getDatabaseProductName();
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }
    }

    @Test
    void insertConversionPreservesBracketAliasesAndSeparatesFollowingValues() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        when(metadata.getDatabaseProductVersion()).thenReturn("16.0");
        DataSource ds = dataSource(conn);
        for (String alias : new String[] { "[x']", "[x\"]", "[x/*]", "[x--]", "[x$$]", "[x]]']" }) {
            String expression = "(SELECT name " + alias + " FROM t WHERE name = 'a]')";
            assertEquals("UPDATE dest SET a = " + expression + ", b = 2 WHERE id = 1",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds,
                            "INSERT INTO dest(a, b) VALUES (" + expression + ", 2)", "id = 1"));
        }
    }

    @Test
    void arraySubscriptsStillRecognizeBracketCharactersInsideStringLiterals() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:bracket_in_array_literal");
        String expression = "(ARRAY[10, 20])[CASE WHEN 'a]' = 'a]' THEN 1 ELSE 2 END]";
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement();
                ResultSet rows = stmt.executeQuery("SELECT " + expression)) {
            assertTrue(rows.next());
            assertEquals(10, rows.getInt(1));
            SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
            try {
                assertNull(JdbcUtil.getTransaction(ds, "SELECT " + expression, CREATED_BY));
                assertSame(tran, JdbcUtil.getTransaction(ds, "SELECT " + expression + " INTO copy", CREATED_BY));
                assertEquals("UPDATE dest SET a = " + expression + ", b = 2",
                        JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO dest(a, b) VALUES (" + expression + ", 2)"));
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }
    }

    @Test
    void aWriteFollowingABracketAliasUsesTheConnectionThatRollsBack() throws Exception {
        JdbcDataSource realDs = new JdbcDataSource();
        realDs.setURL("jdbc:h2:mem:bracket_alias_rollback;MODE=MSSQLServer");
        try (Connection setup = realDs.getConnection(); Statement stmt = setup.createStatement(); Connection real = realDs.getConnection()) {
            stmt.execute("CREATE TABLE t(id INT)");
            stmt.execute("INSERT INTO t VALUES (1)");
            // Execute bracket syntax with H2's SQL Server mode, using SQL Server metadata for routing.
            Connection conn = mock(Connection.class, delegatesTo(real));
            DatabaseMetaData metadata = mock(DatabaseMetaData.class, delegatesTo(real.getMetaData()));
            doReturn(metadata).when(conn).getMetaData();
            doReturn("Microsoft SQL Server").when(metadata).getDatabaseProductName();
            DataSource ds = dataSource(conn);
            when(ds.getConnection()).thenReturn(conn).thenAnswer(invocation -> realDs.getConnection());
            SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
            try {
                JdbcUtil.prepareQuery(ds, "SELECT id [x'] FROM FINAL TABLE (UPDATE t SET id = 2) WHERE 'a]' = 'a]'")
                        .query((Jdbc.ResultExtractor<Void>) rows -> {
                            assertTrue(rows.next());
                            assertEquals(2, rows.getInt(1));
                            return null;
                        });
            } finally {
                tran.rollbackIfNotCommitted();
            }
            try (ResultSet rows = stmt.executeQuery("SELECT id FROM t")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            verify(conn).rollback();
        }
    }

    private static DataSource dataSource(Connection conn) throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);
        return ds;
    }
}
