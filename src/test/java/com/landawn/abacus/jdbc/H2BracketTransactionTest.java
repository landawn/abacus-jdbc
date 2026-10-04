package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("2025")
class H2BracketTransactionTest {
    private static final String WRITE = " FROM FINAL TABLE (UPDATE t SET id = 2) WHERE 'a]' = 'a]'";

    @Test
    void bracketAliasWriteRollsBackWithActualH2Metadata() throws Exception {
        assertWritesRollBack("h2_bracket_alias", "SELECT id [x']" + WRITE);
    }

    @Test
    void mixedArraysAndBracketAliasesRollBackInEitherProjectionOrder() throws Exception {
        // MSSQLServer mode accepts array query constructors, but reserves square brackets for identifiers.
        assertWritesRollBack("h2_mixed_brackets",
                "SELECT id [x'], ARRAY(SELECT 1)" + WRITE,
                "SELECT ARRAY(SELECT 1), id [x']" + WRITE,
                "SELECT id [x/*], ARRAY(SELECT 1)" + WRITE + " /* end */",
                "SELECT id [x--], ARRAY(SELECT 1)" + WRITE + "\n");
    }

    @Test
    void bracketAliasInsideAnArraySubqueryCannotHideAWrite() throws Exception {
        assertWritesRollBack("h2_nested_bracket_alias", "SELECT ARRAY(SELECT id [x']" + WRITE + ")",
                "WITH c AS (SELECT ARRAY(SELECT id [x']" + WRITE + ") AS a) SELECT a FROM c");
    }

    @Test
    void changingModeAfterDialectResolutionDoesNotLetWritesEscape() throws Exception {
        JdbcDataSource ds = source("h2_changed_mode", false);
        try (Connection setup = ds.getConnection(); Statement stmt = setup.createStatement()) {
            stmt.execute("CREATE TABLE t(id INT)");
            stmt.execute("INSERT INTO t VALUES (1)");
            SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
            try {
                // Resolve/cache the H2 dialect before changing compatibility mode on another connection.
                assertNull(JdbcUtil.getTransaction(ds, "SELECT ARRAY[1]", SqlTransaction.CreatedBy.JDBC_UTIL));
                stmt.execute("SET MODE MSSQLServer");
                executeWrite(ds, "SELECT id [x']" + WRITE);
            } finally {
                tran.rollbackIfNotCommitted();
            }
            assertValue(stmt, 1);
        }
    }

    @Test
    void ordinaryArrayAndBracketReadsStillBypassUpdateOnlyTransactions() throws Exception {
        for (boolean sqlServerMode : new boolean[] { false, true }) {
            JdbcDataSource ds = source("h2_reads_" + sqlServerMode, sqlServerMode);
            String[] queries = sqlServerMode
                    ? new String[] { "SELECT abs(1) [x'] WHERE 'a]' = 'a]'", "SELECT ARRAY(SELECT 1) [x'] WHERE 'a]' = 'a]'",
                            "WITH c AS (SELECT 1 [x]) SELECT * FROM c" }
                    : new String[] { "SELECT ARRAY[ARRAY[1]]", "SELECT (ARRAY[10, 20])[1]",
                            "SELECT (ARRAY[10, 20])[CASE WHEN 'a]' = 'a]' THEN 1 ELSE 2 END]",
                            "WITH c AS (SELECT ARRAY[ARRAY[1]] AS a) SELECT a FROM c" };
            try (Connection setup = ds.getConnection(); Statement stmt = setup.createStatement()) {
                SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
                try {
                    for (String sql : queries) {
                        try (ResultSet rows = stmt.executeQuery(sql)) {
                            assertTrue(rows.next());
                        }
                        assertNull(JdbcUtil.getTransaction(ds, sql, SqlTransaction.CreatedBy.JDBC_UTIL), sql);
                    }
                } finally {
                    tran.rollbackIfNotCommitted();
                }
            }
        }
    }

    private static void assertWritesRollBack(String name, String... statements) throws Exception {
        JdbcDataSource ds = source(name, true);
        try (Connection setup = ds.getConnection(); Statement stmt = setup.createStatement()) {
            assertEquals("H2", setup.getMetaData().getDatabaseProductName());
            stmt.execute("CREATE TABLE t(id INT)");
            stmt.execute("INSERT INTO t VALUES (1)");
            for (String sql : statements) {
                SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
                try {
                    executeWrite(ds, sql);
                } finally {
                    tran.rollbackIfNotCommitted();
                }
                assertValue(stmt, 1);
            }
        }
    }

    private static void executeWrite(JdbcDataSource ds, String sql) throws Exception {
        JdbcUtil.prepareQuery(ds, sql).query((Jdbc.ResultExtractor<Void>) rows -> {
            assertTrue(rows.next(), sql);
            assertNotNull(rows.getObject(1), sql);
            return null;
        });
    }

    private static void assertValue(Statement stmt, int expected) throws Exception {
        try (ResultSet rows = stmt.executeQuery("SELECT id FROM t")) {
            assertTrue(rows.next());
            assertEquals(expected, rows.getInt(1), "The update must participate in rollback");
        }
    }

    private static JdbcDataSource source(String name, boolean sqlServerMode) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + (sqlServerMode ? ";MODE=MSSQLServer" : ""));
        return ds;
    }
}
