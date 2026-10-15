package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlDialect.ProductInfo;

@Tag("integration")
class JdbcCodeGenerationIdentifierIntegrationTest extends TestBase {

    @Nested
    class GeneratedStatementContracts {
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
                assertEquals("SELECT NOTE FROM warehouse.t WHERE ID > ?",
                        JdbcCodeGenerationUtil.generateSelectSql(conn, "warehouse.t", List.of("id"), "ID > ?"));
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
            for (final String product : List.of("Microsoft SQL Server", "Adaptive Server Enterprise", "MySQL", "MariaDB", "Spark SQL", "Databricks",
                    "Apache Hive", "Google BigQuery", "PostgreSQL")) {
                assertEquals("UPDATE warehouse.t SET a = 1, b = 2, c = 3", JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(productDataSource(product),
                        "INSERT INTO warehouse.t(a, b, c) VALUES (1, 2, 3)", null), product);
            }
        }

        @Test
        void convertInsertSqlToUpdateSqlRejectsColumnsThatNeedQuotingAcrossProducts() throws Exception {
            for (final String product : List.of("Microsoft SQL Server", "Adaptive Server Enterprise", "MySQL", "MariaDB", "Spark SQL", "Databricks",
                    "Apache Hive", "Google BigQuery", "PostgreSQL")) {
                final DataSource ds = productDataSource(product);
                assertUnquotable("a b",
                        () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(\"a b\", c, \"Mixed\") VALUES (1, 2, 3)", null));
                assertUnquotable("Mixed", () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(\"Mixed\", c) VALUES (1, 2)", null));
                assertUnquotable("USER", () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(USER, c) VALUES (1, 2)", null));
            }
        }
    }

    @Nested
    class MetadataIdentifierValidation {
        @Test
        void generatedSelectRejectsUserInsteadOfSilentlySelectingTheCurrentUserFunction() throws Exception {
            try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:codegen_review_user", "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE user_values (id INT, \"USER\" VARCHAR(80))");
                stmt.execute("INSERT INTO user_values VALUES (1, 'stored payload')");

                // Unquoted USER succeeds but reads a function rather than the stored column, so SQL preparation alone cannot guard this case.
                try (ResultSet rows = stmt.executeQuery("SELECT USER, \"USER\" FROM user_values")) {
                    assertTrue(rows.next());
                    assertEquals("SA", rows.getString(1));
                    assertEquals("stored payload", rows.getString(2));
                }
                assertUnquotable("USER", () -> JdbcCodeGenerationUtil.generateSelectSql(conn, "user_values"));
            }
        }

        @Test
        void everyStatementFamilyRejectsReservedColumnNames() throws Exception {
            try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:codegen_review_order", "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE order_values (id INT, \"ORDER\" VARCHAR(80))");
                assertEveryFamilyRejects(conn, "order_values", "ORDER");
            }
        }

        @Test
        void everyStatementFamilyRejectsBareExpressionColumnNames() throws Exception {
            for (final String name : List.of("ROWNUM", "CURRENT_PATH")) {
                try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:codegen_review_expression_" + name, "sa", "");
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE expression_values (id INT, \"" + name + "\" VARCHAR(80))");
                    stmt.execute("INSERT INTO expression_values VALUES (1, 'stored payload')");

                    // Both spellings parse successfully, but only the delimited spelling reads the stored column.
                    try (ResultSet rows = stmt.executeQuery("SELECT " + name + ", \"" + name + "\" FROM expression_values")) {
                        assertTrue(rows.next());
                        assertNotEquals("stored payload", rows.getString(1));
                        assertEquals("stored payload", rows.getString(2));
                    }
                    assertEveryFamilyRejects(conn, "expression_values", name);
                    assertEquals("SELECT ID FROM expression_values", JdbcCodeGenerationUtil.generateSelectSql(conn, "expression_values", List.of(name), null));
                }
            }
        }

        @Test
        void updateKeyPredicatesRejectBareExpressionsForPositionalAndNamedParameters() throws Exception {
            for (final String name : List.of("ROWNUM", "CURRENT_PATH")) {
                try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:codegen_review_expression_key_" + name, "sa", "");
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE expression_keys (id INT, \"" + name + "\" VARCHAR(80), note VARCHAR(80))");
                    stmt.execute("INSERT INTO expression_keys VALUES (1, '42', 'first'), (2, '1', 'second')");

                    // The key is excluded from SET, so it must also be checked when rendering the WHERE predicate.
                    assertAll(name, () -> assertUnquotable(name, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "expression_keys", name)),
                            () -> assertUnquotable(name, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "expression_keys", name)),
                            () -> assertUnquotable(name,
                                    () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "expression_keys", List.of(), List.of(name), null)),
                            () -> assertUnquotable(name,
                                    () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "expression_keys", List.of(), List.of(name), null)),
                            () -> assertUnquotable(name,
                                    () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "expression_keys", List.of(), List.of("id", name), null)),
                            () -> assertUnquotable(name,
                                    () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "expression_keys", List.of(), List.of("id", name), null)));
                }
            }
        }

        @Test
        void exactStoredCaseCannotSilentlyResolveToAnotherUnquotedColumn() throws Exception {
            for (final boolean lowerCase : new boolean[] { false, true }) {
                final String database = "jdbc:h2:mem:codegen_review_case_" + lowerCase + (lowerCase ? ";DATABASE_TO_LOWER=TRUE" : "");
                try (Connection conn = DriverManager.getConnection(database, "sa", "");
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE case_values (id INT, mixedcase VARCHAR(80), \"MixedCase\" VARCHAR(80))");
                    stmt.execute("INSERT INTO case_values VALUES (1, 'ordinary column', 'exact column')");
                    try (ResultSet rows = stmt.executeQuery("SELECT mixedcase, \"MixedCase\" FROM case_values")) {
                        assertTrue(rows.next());
                        assertEquals("ordinary column", rows.getString(1));
                        assertEquals("exact column", rows.getString(2));
                    }
                    assertEveryFamilyRejects(conn, "case_values", "MixedCase");
                }
            }
        }

        @Test
        void generatedInsertUpdateAndSelectStillExecuteForOrdinaryColumns() throws Exception {
            try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:codegen_review_ordinary", "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE plain_values (id INT, note VARCHAR(80))");

                try (PreparedStatement insert = conn.prepareStatement(JdbcCodeGenerationUtil.generateInsertSql(conn, "plain_values"))) {
                    insert.setInt(1, 1);
                    insert.setString(2, "first");
                    assertEquals(1, insert.executeUpdate());
                }

                try (PreparedStatement update = conn.prepareStatement(JdbcCodeGenerationUtil.generateUpdateSql(conn, "plain_values", "id"))) {
                    update.setString(1, "second");
                    update.setInt(2, 1);
                    assertEquals(1, update.executeUpdate());
                }

                try (ResultSet rows = stmt.executeQuery(JdbcCodeGenerationUtil.generateSelectSql(conn, "plain_values"))) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertEquals("second", rows.getString(2));
                }
            }
        }

        private static void assertEveryFamilyRejects(final Connection conn, final String tableName, final String identifier) {
            assertAll(tableName, () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, tableName)),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, tableName, List.of(), null)),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateInsertSql(conn, tableName)),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateInsertSql(conn, tableName, List.of())),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(conn, tableName)),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(conn, tableName, List.of())),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, tableName)),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, tableName, "id")),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, tableName, List.of(), List.of("id"), null)),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, tableName)),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, tableName, "id")),
                    () -> assertUnquotable(identifier, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, tableName, List.of(), List.of("id"), null)));
        }

        private static void assertUnquotable(final String identifier, final org.junit.jupiter.api.function.Executable call) {
            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, call);
            assertTrue(failure.getMessage().contains(identifier), failure.getMessage());
        }
    }
}
