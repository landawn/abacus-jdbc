package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

@Tag("2025")
public class DataTransferReviewTest extends TestBase {

    @Test
    void copyingAllColumnsPreservesReservedColumnNamesAndValues() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:transfer_review_source", "sa", "");
             Connection target = DriverManager.getConnection("jdbc:h2:mem:transfer_review_target", "sa", "");
             Statement sourceStmt = source.createStatement();
             Statement targetStmt = target.createStatement()) {
            sourceStmt.execute("CREATE TABLE source_values (id INT, \"ORDER\" VARCHAR(80), \"USER\" VARCHAR(80))");
            targetStmt.execute("CREATE TABLE target_values (\"USER\" VARCHAR(80), id INT, \"ORDER\" VARCHAR(80))");
            sourceStmt.execute("INSERT INTO source_values VALUES (1, 'first order', 'stored user')");

            assertEquals(1, DataTransferUtil.copy(source, target, "source_values", "target_values"));

            try (ResultSet rows = targetStmt.executeQuery("SELECT id, \"ORDER\", \"USER\" FROM target_values")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals("first order", rows.getString(2));
                assertEquals("stored user", rows.getString(3));
            }
        }
    }

    @Test
    void copyingAllColumnsStillFoldsOrdinaryNamesToTheTargetDatabaseCase() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:transfer_review_upper", "sa", "");
             Connection target = DriverManager.getConnection("jdbc:h2:mem:transfer_review_lower;DATABASE_TO_LOWER=TRUE", "sa", "");
             Statement sourceStmt = source.createStatement();
             Statement targetStmt = target.createStatement()) {
            sourceStmt.execute("CREATE TABLE source_values (id INT, note VARCHAR(80), \"MixedCase\" VARCHAR(80))");
            targetStmt.execute("CREATE TABLE target_values (note VARCHAR(80), \"MixedCase\" VARCHAR(80), id INT)");
            sourceStmt.execute("INSERT INTO source_values VALUES (1, 'folded name', 'exact name')");

            assertEquals(1, DataTransferUtil.copy(source, target, "source_values", "target_values"));

            try (ResultSet rows = targetStmt.executeQuery("SELECT id, note, \"MixedCase\" FROM target_values")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals("folded name", rows.getString(2));
                assertEquals("exact name", rows.getString(3));
            }
        }
    }
}
