package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

@Tag("2025")
public class DataTransferReviewTest extends TestBase {

    @Test
    void copyingAllColumnsRejectsReservedNamesBeforeWritingAnyRows() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:transfer_review_source", "sa", "");
             Connection target = DriverManager.getConnection("jdbc:h2:mem:transfer_review_target", "sa", "");
             Statement sourceStmt = source.createStatement();
             Statement targetStmt = target.createStatement()) {
            sourceStmt.execute("CREATE TABLE source_values (id INT, \"ORDER\" VARCHAR(80), \"USER\" VARCHAR(80))");
            targetStmt.execute("CREATE TABLE target_values (\"USER\" VARCHAR(80), id INT, \"ORDER\" VARCHAR(80))");
            sourceStmt.execute("INSERT INTO source_values VALUES (1, 'first order', 'stored user')");

            assertUnquotable("ORDER", () -> DataTransferUtil.copy(source, target, "source_values", "target_values"));
            assertEmpty(targetStmt, "target_values");
        }
    }

    @Test
    void tableCopiesRejectBareExpressionColumnsButExplicitSqlPreservesTheirValues() throws Exception {
        for (final String name : List.of("ROWNUM", "CURRENT_PATH")) {
            try (Connection source = DriverManager.getConnection("jdbc:h2:mem:transfer_expression_source_" + name, "sa", "");
                 Connection target = DriverManager.getConnection("jdbc:h2:mem:transfer_expression_target_" + name, "sa", "");
                 Statement sourceStmt = source.createStatement();
                 Statement targetStmt = target.createStatement()) {
                sourceStmt.execute("CREATE TABLE source_values (id INT, \"" + name + "\" VARCHAR(80))");
                targetStmt.execute("CREATE TABLE target_values (id INT, \"" + name + "\" VARCHAR(80))");
                sourceStmt.execute("INSERT INTO source_values VALUES (1, 'stored payload')");

                assertUnquotable(name, () -> DataTransferUtil.copy(source, target, "source_values", "target_values"));
                assertEmpty(targetStmt, "target_values");
                assertUnquotable(name, () -> DataTransferUtil.copy(source, target, "source_values", "target_values", List.of("id", name)));
                assertEmpty(targetStmt, "target_values");

                assertEquals(1, DataTransferUtil.copy(source, "SELECT id, \"" + name + "\" FROM source_values", target,
                        "INSERT INTO target_values(id, \"" + name + "\") VALUES (?, ?)"));
                try (ResultSet rows = targetStmt.executeQuery("SELECT id, \"" + name + "\" FROM target_values")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertEquals("stored payload", rows.getString(2));
                    assertFalse(rows.next());
                }
            }
        }
    }

    @Test
    void copyingOrdinaryColumnsPreservesValuesAcrossCaseFoldingAndDifferentColumnOrders() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:transfer_review_upper", "sa", "");
             Connection target = DriverManager.getConnection("jdbc:h2:mem:transfer_review_lower;DATABASE_TO_LOWER=TRUE", "sa", "");
             Statement sourceStmt = source.createStatement();
             Statement targetStmt = target.createStatement()) {
            sourceStmt.execute("CREATE TABLE source_values (id INT, note VARCHAR(80), details VARCHAR(80))");
            targetStmt.execute("CREATE TABLE target_values (note VARCHAR(80), details VARCHAR(80), id INT)");
            sourceStmt.execute("INSERT INTO source_values VALUES (1, 'first note', 'first details'), (2, 'second note', 'second details')");

            assertEquals(2, DataTransferUtil.copy(source, target, "source_values", "target_values"));

            try (ResultSet rows = targetStmt.executeQuery("SELECT id, note, details FROM target_values ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals("first note", rows.getString(2));
                assertEquals("first details", rows.getString(3));
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
                assertEquals("second note", rows.getString(2));
                assertEquals("second details", rows.getString(3));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void copyingAllColumnsRejectsExactStoredCaseInsteadOfReadingTheFoldedColumn() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:transfer_review_exact_source", "sa", "");
             Connection target = DriverManager.getConnection("jdbc:h2:mem:transfer_review_exact_target", "sa", "");
             Statement sourceStmt = source.createStatement();
             Statement targetStmt = target.createStatement()) {
            sourceStmt.execute("CREATE TABLE source_values (id INT, mixedcase VARCHAR(80), \"MixedCase\" VARCHAR(80))");
            targetStmt.execute("CREATE TABLE target_values (\"MixedCase\" VARCHAR(80), id INT, mixedcase VARCHAR(80))");
            sourceStmt.execute("INSERT INTO source_values VALUES (1, 'ordinary column', 'exact column')");

            assertUnquotable("MixedCase", () -> DataTransferUtil.copy(source, target, "source_values", "target_values"));
            assertEmpty(targetStmt, "target_values");
        }
    }

    @Test
    void tableCopyRejectsExplicitlyDelimitedTableAndSelectedColumnNames() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:transfer_review_explicit_source", "sa", "");
             Connection target = DriverManager.getConnection("jdbc:h2:mem:transfer_review_explicit_target", "sa", "");
             Statement sourceStmt = source.createStatement();
             Statement targetStmt = target.createStatement()) {
            sourceStmt.execute("CREATE TABLE source_values (id INT, note VARCHAR(80))");
            targetStmt.execute("CREATE TABLE target_values (id INT, note VARCHAR(80))");
            sourceStmt.execute("INSERT INTO source_values VALUES (1, 'payload')");

            assertUnquotable("SOURCE_VALUES", () -> DataTransferUtil.copy(source, target, "\"SOURCE_VALUES\"", "target_values"));
            assertUnquotable("TARGET_VALUES", () -> DataTransferUtil.copy(source, target, "source_values", "PUBLIC.\"TARGET_VALUES\""));
            for (final String column : List.of("\"ID\"", "`ID`", "[ID]")) {
                assertUnquotable("ID", () -> DataTransferUtil.copy(source, target, "source_values", "target_values", List.of(column, "note")));
            }
            assertEmpty(targetStmt, "target_values");
        }
    }

    @Test
    void explicitSqlCopyPreservesCallerControlledQuoting() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:transfer_review_raw_source", "sa", "");
             Connection target = DriverManager.getConnection("jdbc:h2:mem:transfer_review_raw_target", "sa", "");
             Statement sourceStmt = source.createStatement();
             Statement targetStmt = target.createStatement()) {
            sourceStmt.execute("CREATE TABLE \"source values\" (id INT, \"ORDER\" VARCHAR(80), \"USER\" VARCHAR(80), \"MixedCase\" VARCHAR(80))");
            targetStmt.execute("CREATE TABLE \"target values\" (\"MixedCase\" VARCHAR(80), \"USER\" VARCHAR(80), id INT, \"ORDER\" VARCHAR(80))");
            sourceStmt.execute("INSERT INTO \"source values\" VALUES (1, 'first order', 'stored user', 'exact value')");

            assertEquals(1, DataTransferUtil.copy(source, "SELECT id, \"ORDER\", \"USER\", \"MixedCase\" FROM \"source values\"", target,
                    "INSERT INTO \"target values\"(id, \"ORDER\", \"USER\", \"MixedCase\") VALUES (?, ?, ?, ?)"));
            try (ResultSet rows = targetStmt.executeQuery("SELECT id, \"ORDER\", \"USER\", \"MixedCase\" FROM \"target values\"")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals("first order", rows.getString(2));
                assertEquals("stored user", rows.getString(3));
                assertEquals("exact value", rows.getString(4));
                assertFalse(rows.next());
            }
        }
    }

    private static void assertUnquotable(final String identifier, final org.junit.jupiter.api.function.Executable call) {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, call);
        assertTrue(failure.getMessage().contains(identifier), failure.getMessage());
    }

    private static void assertEmpty(final Statement stmt, final String tableName) throws SQLException {
        try (ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }
}
