package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlOperation;

@Tag("2025")
public class JdbcUtilArrayScannerTest extends TestBase {
    @Test
    void nestedArrayCtesAreClassifiedAsSelectAndBypassUpdateOnlyTransactions() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:array_scanner_reads");
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            String sql = "WITH c AS (SELECT ARRAY[ARRAY[1]] AS a) SELECT a FROM c";
            try (ResultSet rs = stmt.executeQuery(sql)) { assertTrue(rs.next()); }
            assertEquals(SqlOperation.SELECT, JdbcUtil.getSqlOperation(sql));
            SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
            try {
                assertNull(JdbcUtil.getTransaction(ds, sql, SqlTransaction.CreatedBy.JDBC_UTIL));
            } finally { tran.rollbackIfNotCommitted(); }
        }
    }

    @Test
    void arrayProjectionDoesNotLetAWriteEscapeRollback() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:array_scanner_writes");
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("create table t(id int)");
            stmt.execute("insert into t values(1)");
            for (String expression : new String[] {"ARRAY[ARRAY[1]]", "(ARRAY[10, 20])[ARRAY[1][1]]", "ARRAY[ARRAY[']', ',(']]"}) {
                SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
                try {
                    String sql = "SELECT " + expression + " AS a FROM FINAL TABLE (UPDATE t SET id = 2)";
                    JdbcUtil.prepareQuery(ds, sql).query((Jdbc.ResultExtractor<Void>) rows -> { assertTrue(rows.next()); return null; });
                } finally { tran.rollbackIfNotCommitted(); }
                try (ResultSet rs = stmt.executeQuery("select id from t")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), expression);
                }
            }
        }
    }

    @Test
    void arrayAndIdentifierLexingPreservesCommentsStringsAndBracketEscapes() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:array_scanner_lexing");
        SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
        try {
            for (String expression : new String[] {"ARRAY /* [)] */ [[1, 2], [3, 4]]", "payload[indexes[1]]",
                    "ARRAY[[$q$],($q$, 'x'], ['y', 'z']]", "ARRAY -- [)]\n [ARRAY[1]]"}) {
                assertEquals(SqlOperation.SELECT, JdbcUtil.getSqlOperation("WITH c AS (SELECT " + expression + " AS a) SELECT a FROM c"));
                assertSame(tran, JdbcUtil.getTransaction(ds, "SELECT " + expression + " INTO copy FROM t", SqlTransaction.CreatedBy.JDBC_UTIL));
            }
            for (String sql : new String[] {"SELECT [a]]b(into] FROM t", "SELECT TOP (1) [a[b] FROM [c]]d]",
                    "SELECT [a]]b'into] FROM t", "SELECT 1 [a]]into]", "SELECT [a/*into] FROM t",
                    "SELECT ARRAY['into', '(UPDATE t SET a=1)']", "SELECT a[1] FROM t", "SELECT abs(1) [into]",
                    "SELECT name [prefix into suffix] FROM t", "SELECT ARRAY[(SELECT abs(1) [into])]"}) {
                assertNull(JdbcUtil.getTransaction(ds, sql, SqlTransaction.CreatedBy.JDBC_UTIL), sql);
            }
        } finally { tran.rollbackIfNotCommitted(); }
    }

    @Test
    void aWriteInsideAnArraySubqueryStillParticipatesInRollback() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:array_scanner_inner_write");
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("create table t(id int)");
            stmt.execute("insert into t values(1)");
            SqlTransaction tran = JdbcUtil.beginTransaction(ds, IsolationLevel.READ_COMMITTED, true);
            try {
                JdbcUtil.prepareQuery(ds, "SELECT ARRAY[(SELECT id FROM FINAL TABLE (UPDATE t SET id = 2))]")
                        .query((Jdbc.ResultExtractor<Void>) rows -> { assertTrue(rows.next()); return null; });
            } finally { tran.rollbackIfNotCommitted(); }
            try (ResultSet rows = stmt.executeQuery("select id from t")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}
