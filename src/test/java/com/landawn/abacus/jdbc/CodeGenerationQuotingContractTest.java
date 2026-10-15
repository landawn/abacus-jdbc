package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlDialect.ProductInfo;

/**
 * Verifies the default disabled-quoting contract: generated identifiers remain unquoted, names requiring delimiters
 * fail clearly, and connection-based delimiter discovery is disabled with SQL identifier quoting.
 */
@Tag("2025")
public class CodeGenerationQuotingContractTest extends TestBase {
    private static Connection dialect(final Connection real, final String product, final String quote) throws SQLException {
        final Connection conn = mock(Connection.class, delegatesTo(real));
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class, delegatesTo(real.getMetaData()));
        doReturn(metadata).when(conn).getMetaData();
        doReturn(product).when(metadata).getDatabaseProductName();
        doReturn(quote).when(metadata).getIdentifierQuoteString();
        return conn;
    }

    private static DataSource productDataSource(final String product) throws SQLException {
        final DataSource ds = mock(DataSource.class);
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        when(metadata.getDatabaseProductVersion()).thenReturn("1.0");
        return ds;
    }

    private static void assertUnquotable(final String identifier, final org.junit.jupiter.api.function.Executable call) {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
        assertTrue(e.getMessage().toLowerCase(Locale.ROOT).contains(identifier.toLowerCase(Locale.ROOT)), e.getMessage());
    }

    private static void assertEveryFamilyRejects(final Connection conn, final String tableName, final String identifier) {
        assertAll(tableName, () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, tableName)),
                () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, tableName, List.of(), null)),
                () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateInsertSql(conn, tableName)),
                () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(conn, tableName)),
                () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, tableName, "id")),
                () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, tableName, "id")));
    }

    @Test
    void theDriverReportedQuoteIsIgnoredWhenIdentifierQuotingIsDisabled() throws Exception {
        try (Connection real = DriverManager.getConnection("jdbc:h2:mem:codegen_contract_ansi");
             Statement stmt = real.createStatement()) {
            stmt.execute("create table t(id int, note varchar)");
            final Connection conn = dialect(real, "MySQL", "\"");
            assertNull(SqlIdentifierUtil.quoteString(conn, ProductInfo.of("MySQL", "1")));
            assertEquals("SELECT ID, NOTE FROM t", JdbcCodeGenerationUtil.generateSelectSql(conn, "t"));
            assertEquals("UPDATE t SET NOTE = ? WHERE ID = ?", JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", "ID"));
        }
    }

    @Test
    void ordinaryQualifiedTableNamesRemainUnquotedForEveryStatementFamily() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:codegen_contract_qualified");
             Statement stmt = conn.createStatement()) {
            stmt.execute("create schema warehouse");
            stmt.execute("create table warehouse.t(id int, note varchar)");

            assertEquals("SELECT ID, NOTE FROM warehouse.t", JdbcCodeGenerationUtil.generateSelectSql(conn, "warehouse.t"));
            assertEquals("SELECT NOTE FROM warehouse.t WHERE ID > ?", JdbcCodeGenerationUtil.generateSelectSql(conn, "warehouse.t", List.of("id"), "ID > ?"));
            assertEquals("INSERT INTO warehouse.t(ID, NOTE) VALUES (?, ?)", JdbcCodeGenerationUtil.generateInsertSql(conn, "warehouse.t"));
            assertEquals("INSERT INTO warehouse.t(ID, NOTE) VALUES (:id, :note)", JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "warehouse.t"));
            assertEquals("UPDATE warehouse.t SET NOTE = ? WHERE ID = ?", JdbcCodeGenerationUtil.generateUpdateSql(conn, "warehouse.t", "id"));
            assertEquals("UPDATE warehouse.t SET NOTE = :note WHERE ID = :id", JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "warehouse.t", "id"));
        }
    }

    @Test
    void explicitlyDelimitedSimpleTableAndKeyNamesAreRejected() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:codegen_contract_explicit");
             Statement stmt = conn.createStatement()) {
            stmt.execute("create table t(id int, note varchar)");

            for (final String tableName : List.of("\"T\"", "`T`", "[T]", "PUBLIC.\"T\"", "\"PUBLIC\".T", "PUBLIC.[T]")) {
                assertEveryFamilyRejects(conn, tableName, tableName.contains("\"PUBLIC\"") ? "PUBLIC" : "T");
            }
            for (final String key : List.of("\"ID\"", "`ID`", "[ID]")) {
                assertUnquotable("ID", () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", key));
                assertUnquotable("ID", () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t", key));
                assertUnquotable("ID", () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", List.of(), List.of(key), null));
                assertUnquotable("ID", () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t", List.of(), List.of(key), null));
            }
        }
    }

    @Test
    void namesNeedingDelimitersAreRejectedEvenWhenTheDriverSupportsQuoting() throws Exception {
        try (Connection real = DriverManager.getConnection("jdbc:h2:mem:codegen_contract_noquote");
             Statement stmt = real.createStatement()) {
            stmt.execute("create table \"my table\"(id int, note varchar)");
            stmt.execute("create table plain(id int, note varchar, \"a-b\" int)");
            final Connection conn = dialect(real, "Informix", "\"");

            assertEveryFamilyRejects(conn, "\"my table\"", "my table");
            assertEveryFamilyRejects(conn, "plain", "a-b");
            assertEquals("SELECT ID, NOTE FROM plain", JdbcCodeGenerationUtil.generateSelectSql(conn, "plain", List.of("a-b"), null));
            assertEquals("INSERT INTO plain(ID, NOTE) VALUES (?, ?)", JdbcCodeGenerationUtil.generateInsertSql(conn, "plain", List.of("a-b")));
            assertEquals("INSERT INTO plain(ID, NOTE) VALUES (:id, :note)", JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "plain", List.of("a-b")));
            assertEquals("UPDATE plain SET NOTE = ? WHERE ID = ?",
                    JdbcCodeGenerationUtil.generateUpdateSql(conn, "plain", List.of("a-b"), List.of("id"), null));
            assertEquals("UPDATE plain SET NOTE = :note WHERE ID = :id",
                    JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "plain", List.of("a-b"), List.of("id"), null));
        }
    }

    @Test
    void convertInsertSqlToUpdateSqlKeepsOrdinaryIdentifiersUnquotedAcrossProducts() throws Exception {
        for (final String product : List.of("Microsoft SQL Server", "Adaptive Server Enterprise", "MySQL", "MariaDB", "Spark SQL", "Databricks", "Apache Hive",
                "Google BigQuery", "PostgreSQL")) {
            assertEquals("UPDATE warehouse.t SET a = 1, b = 2, c = 3",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(productDataSource(product), "INSERT INTO warehouse.t(a, b, c) VALUES (1, 2, 3)", null),
                    product);
        }
    }

    @Test
    void convertInsertSqlToUpdateSqlRejectsColumnsThatNeedQuotingAcrossProducts() throws Exception {
        for (final String product : List.of("Microsoft SQL Server", "Adaptive Server Enterprise", "MySQL", "MariaDB", "Spark SQL", "Databricks", "Apache Hive",
                "Google BigQuery", "PostgreSQL")) {
            final DataSource ds = productDataSource(product);
            assertUnquotable("a b",
                    () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(\"a b\", c, \"Mixed\") VALUES (1, 2, 3)", null));
            assertUnquotable("Mixed", () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(\"Mixed\", c) VALUES (1, 2)", null));
            assertUnquotable("USER", () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(USER, c) VALUES (1, 2)", null));
        }
    }
}
