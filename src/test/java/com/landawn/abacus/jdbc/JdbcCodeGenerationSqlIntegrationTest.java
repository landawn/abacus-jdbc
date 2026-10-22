package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JdbcCodeGenerationSqlIntegrationTest extends JdbcCodeGenerationUtilTestSupport {

    // Reject case-dependent columns, while ordinary metadata names still round-trip through real generated SQL.
    @Test
    public void testGenerateSqlRejectsLabelsWhoseCaseWouldBeFolded_H2() throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:h2:mem:codegen_case_folding", "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE case_guard (id INT PRIMARY KEY, \"userId\" INT, \"note\" VARCHAR(20))");

            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, "case_guard"));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(conn, "case_guard"));
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "case_guard", null, List.of("userId"), null));

            stmt.execute("CREATE TABLE ordinary_guard (id INT PRIMARY KEY, user_id INT, note VARCHAR(20))");
            final String selectSql = JdbcCodeGenerationUtil.generateSelectSql(conn, "ordinary_guard");
            final String insertSql = JdbcCodeGenerationUtil.generateInsertSql(conn, "ordinary_guard");
            final String namedUpdateSql = JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "ordinary_guard", null, List.of("userId"), null);

            assertEquals("SELECT ID, USER_ID, NOTE FROM ordinary_guard", selectSql);
            assertEquals("INSERT INTO ordinary_guard(ID, USER_ID, NOTE) VALUES (?, ?, ?)", insertSql);
            assertEquals("UPDATE ordinary_guard SET ID = :id, NOTE = :note WHERE USER_ID = :userId", namedUpdateSql);

            assertEquals(1, JdbcUtil.executeUpdate(conn, insertSql, 1, 7, "old"));
            assertEquals(1, JdbcUtil.executeUpdate(conn, namedUpdateSql, java.util.Map.of("id", 1, "note", "new", "userId", 7)));

            try (ResultSet rows = stmt.executeQuery(selectSql)) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(2));
                assertEquals("new", rows.getString(3));
                assertFalse(rows.next());
            }
        }
    }

    // '$' is legal in Java identifiers but ends a :named parameter in ParsedSql, so ":price$usd" would be parsed as
    // ":price" followed by the literal text "$usd" (UPDATE ... = ?$usd) - the generated SQL must be rejected instead.
    @Test
    public void testGenerateNamedSqlRejectsColumnWhoseParameterNameParsedSqlWouldTruncate() throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:h2:mem:codegen_dollar_named_param", "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE dollar_guard (id INT PRIMARY KEY, price$usd INT)");

            final IllegalArgumentException positionalFailure = assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "dollar_guard", "id"));
            assertTrue(positionalFailure.getMessage().contains("quoting is disabled"), positionalFailure.getMessage());

            final IllegalArgumentException namedFailure = assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "dollar_guard", "id"));
            assertTrue(namedFailure.getMessage().contains("PRICE$USD"), namedFailure.getMessage());
            assertTrue(namedFailure.getMessage().contains("valid named parameter"), namedFailure.getMessage());
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "dollar_guard"));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "dollar_guard", null, List.of("id"), null));
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "dollar_guard"));
            // Excluding the column leaves only valid parameter names.
            assertEquals("UPDATE dollar_guard SET ID = :id",
                    JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "dollar_guard", List.of("price$usd"), null, null));

            // Other currency symbols are Java identifier characters too, and are cut off by ParsedSql the same way.
            stmt.execute("CREATE TABLE pound_guard (id INT PRIMARY KEY, \"price£gbp\" INT)");
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "pound_guard"));

            // Non-ASCII letters remain valid named parameters, but the column still needs identifier quoting.
            stmt.execute("CREATE TABLE unicode_guard (id INT PRIMARY KEY, \"名前\" VARCHAR(10))");
            final IllegalArgumentException unicodeFailure = assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "unicode_guard"));
            assertTrue(unicodeFailure.getMessage().contains("quoting is disabled"), unicodeFailure.getMessage());
            assertEquals(List.of("id", "名前"),
                    com.landawn.abacus.query.ParsedSql.parse("INSERT INTO unicode_guard(id, \"名前\") VALUES (:id, :名前)").namedParameters());
        }
    }

    // Pins the multi-key generateUpdateSql/generateNamedUpdateSql Javadoc examples: only the key columns (and the excluded
    // columns) are dropped from SET; a column referenced only by the additional where clause, such as version, is still set.
    @Test
    public void testMultiKeyUpdateJavadocExamples() throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:h2:mem:update_javadoc_examples;DATABASE_TO_LOWER=TRUE", "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE orders (id INT, status VARCHAR(20), customer_id INT, total_amount DECIMAL(10, 2), version INT,"
                    + " created_at TIMESTAMP, updated_at TIMESTAMP, PRIMARY KEY (id, status))");
            final List<String> excludedColumns = List.of("created_at", "updated_at");
            final List<String> keyColumnNames = List.of("id", "status");

            assertEquals("UPDATE orders SET customer_id = ?, total_amount = ?, version = ? WHERE id = ? AND status = ? AND (version > 1)",
                    JdbcCodeGenerationUtil.generateUpdateSql(conn, "orders", excludedColumns, keyColumnNames, "version > 1"));
            assertEquals(
                    "UPDATE orders SET customer_id = :customerId, total_amount = :totalAmount, version = :version WHERE id = :id AND status = :status AND (version > 1)",
                    JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "orders", excludedColumns, keyColumnNames, "version > 1"));
        }
    }

    @Test
    public void testGeneratedUpdateGroupsAdditionalOrPredicate() throws SQLException {
        final String condition = "status = 'OPEN' OR status = 'PENDING'";
        assertEquals("UPDATE order_history SET status = ? WHERE id = ? AND (" + condition + ")",
                JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", List.of("created_at"), List.of("id"), condition));
        assertEquals("UPDATE order_history SET status = :status WHERE id = :id AND (" + condition + ")",
                JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", List.of("created_at"), List.of("id"), condition));

        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:h2:mem:update_predicate_grouping", "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE update_guard (id INT PRIMARY KEY, status VARCHAR(20))");
            for (boolean named : new boolean[] { false, true }) {
                stmt.execute("DELETE FROM update_guard");
                stmt.execute("INSERT INTO update_guard VALUES (1, 'OPEN'), (2, 'PENDING'), (3, 'CLOSED')");
                final String sql = named ? JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "update_guard", null, List.of("id"), condition)
                        : JdbcCodeGenerationUtil.generateUpdateSql(conn, "update_guard", null, List.of("id"), condition);

                final int affected = named ? JdbcUtil.executeUpdate(conn, sql, java.util.Map.of("status", "DONE", "id", 1))
                        : JdbcUtil.executeUpdate(conn, sql, "DONE", 1);
                assertEquals(1, affected);
                try (ResultSet rows = stmt.executeQuery("SELECT status FROM update_guard ORDER BY id")) {
                    assertTrue(rows.next());
                    assertEquals("DONE", rows.getString(1));
                    assertTrue(rows.next());
                    assertEquals("PENDING", rows.getString(1));
                    assertTrue(rows.next());
                    assertEquals("CLOSED", rows.getString(1));
                    assertFalse(rows.next());
                }
            }
        }
    }
}
