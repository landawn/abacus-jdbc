package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

@Tag("2025")
public class JdbcCodeGenerationReviewTest extends TestBase {

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
                        () -> assertUnquotable(name, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "expression_keys", List.of(), List.of(name), null)),
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
