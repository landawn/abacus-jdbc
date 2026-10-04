package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("2025")
class IdentifierQuoteMetadataTest {
    private static Connection dialect(Connection real, String product, String quote, List<String> statements) throws Exception {
        Connection conn = mock(Connection.class, delegatesTo(real));
        DatabaseMetaData metadata = mock(DatabaseMetaData.class, delegatesTo(real.getMetaData()));
        doReturn(metadata).when(conn).getMetaData();
        doReturn(product).when(metadata).getDatabaseProductName();
        doReturn(quote).when(metadata).getIdentifierQuoteString();
        doAnswer(call -> {
            String sql = call.getArgument(0);
            statements.add(sql);
            // H2 folds backtick names even though its double-quoted names preserve case. Execute
            // the equivalent H2 syntax while retaining the exact generated dialect SQL above.
            return real.prepareStatement(sql.replace('`', '"'));
        }).when(conn).prepareStatement(anyString());
        doAnswer(call -> {
            String sql = call.getArgument(0);
            statements.add(sql);
            return real.prepareStatement(sql.replace('`', '"'), call.getArgument(1), call.getArgument(2));
        }).when(conn).prepareStatement(anyString(), anyInt(), anyInt());
        return conn;
    }

    @Test
    void codeGenerationUsesDriverQuotesForEveryStatementFamily() throws Exception {
        for (String product : List.of("Spark SQL", "Databricks", "Apache Hive", "Google BigQuery", "OtherDB")) {
            try (Connection real = DriverManager.getConnection("jdbc:h2:mem:metadata_quotes"); Statement stmt = real.createStatement()) {
                stmt.execute("create table t(id int, \"USER\" varchar, \"MixedCase\" int)");
                Connection conn = dialect(real, product, "`", new ArrayList<>());
                assertEquals("SELECT `ID`, `USER`, `MixedCase` FROM t", JdbcCodeGenerationUtil.generateSelectSql(conn, "t"));
                assertTrue(JdbcCodeGenerationUtil.generateSelectSql(conn, "t", List.of("id"), null).contains("`USER`"));
                assertTrue(JdbcCodeGenerationUtil.generateInsertSql(conn, "t").contains("`ID`, `USER`, `MixedCase`"));
                assertTrue(JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "t").contains("`ID`, `USER`, `MixedCase`"));
                assertTrue(JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", "id").contains("`USER` = ?"));
                assertTrue(JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t", "id").contains("`USER` = :"));
            }
        }
    }

    @Test
    void copyHonorsBothConnectionsQuotesAndTargetCaseFolding() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:quote_copy_source");
                Connection target = DriverManager.getConnection("jdbc:h2:mem:quote_copy_target;DATABASE_TO_LOWER=TRUE");
                Statement src = source.createStatement(); Statement dst = target.createStatement()) {
            src.execute("create table t(id int, \"USER\" varchar, \"MixedCase\" varchar)");
            src.execute("insert into t values(1, 'payload', 'exact')");
            dst.execute("create table t(\"MixedCase\" varchar, id int, \"user\" varchar)");
            List<String> sourceSql = new ArrayList<>();
            List<String> targetSql = new ArrayList<>();
            Connection sourceDialect = dialect(source, "Spark SQL", "`", sourceSql);
            Connection targetDialect = dialect(target, "OtherDB", "`", targetSql);
            assertEquals(1, DataTransferUtil.copy(sourceDialect, targetDialect, "t", "t"));
            assertTrue(sourceSql.stream().anyMatch(sql -> sql.startsWith("SELECT `ID`, `USER`, `MixedCase`")));
            assertTrue(targetSql.stream().anyMatch(sql -> sql.startsWith("INSERT INTO t(`id`, `user`, `MixedCase`)")), targetSql.toString());
            try (ResultSet rs = dst.executeQuery("select id, \"user\", \"MixedCase\" from t")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("payload", rs.getString(2));
                assertEquals("exact", rs.getString(3));
            }
        }
    }

    @Test
    void unsupportedQuotingLeavesOrdinaryColumnsUnquoted() throws Exception {
        for (String quote : new String[] {null, "", " "}) {
            try (Connection real = DriverManager.getConnection("jdbc:h2:mem:no_quotes"); Statement stmt = real.createStatement()) {
                stmt.execute("create table t(id int, note varchar)");
                Connection conn = dialect(real, "Informix", quote, new ArrayList<>());
                assertEquals("SELECT ID, NOTE FROM t", JdbcCodeGenerationUtil.generateSelectSql(conn, "t"));
                assertTrue(JdbcCodeGenerationUtil.generateInsertSql(conn, "t").contains("(ID, NOTE)"));
                stmt.execute("create table tricky(\"a b\" int)");
                assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, "tricky"));
            }
        }
    }

    @Test
    void sqlServerAndAseUseBracketsRegardlessOfDoubleQuoteSessionMode() throws Exception {
        for (String product : List.of("Microsoft SQL Server", "Adaptive Server Enterprise")) {
            try (Connection real = DriverManager.getConnection("jdbc:h2:mem:bracket_quotes;MODE=MSSQLServer"); Statement stmt = real.createStatement()) {
                stmt.execute("create table t(id int, \"ORDER\" int, \"a]b\" int)");
                Connection conn = dialect(real, product, "\"", new ArrayList<>());
                String sql = JdbcCodeGenerationUtil.generateSelectSql(conn, "t");
                assertEquals("SELECT [ID], [ORDER], [a]]b] FROM t", sql);
            }
        }
    }

    @Test
    void copiesOrdinaryColumnsWhenNeitherDriverSupportsQuoting() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:no_quote_copy_source");
                Connection target = DriverManager.getConnection("jdbc:h2:mem:no_quote_copy_target");
                Statement src = source.createStatement(); Statement dst = target.createStatement()) {
            src.execute("create table t(id int, note varchar)");
            src.execute("insert into t values(1, 'actual value')");
            dst.execute("create table t(note varchar, id int)");
            List<String> targetSql = new ArrayList<>();
            assertEquals(1, DataTransferUtil.copy(dialect(source, "Informix", " ", new ArrayList<>()),
                    dialect(target, "Informix", " ", targetSql), "t", "t"));
            assertTrue(targetSql.stream().anyMatch(sql -> sql.startsWith("INSERT INTO t(ID, NOTE)")), targetSql.toString());
            try (ResultSet rs = dst.executeQuery("select id, note from t")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("actual value", rs.getString(2));
            }
        }
    }
}
