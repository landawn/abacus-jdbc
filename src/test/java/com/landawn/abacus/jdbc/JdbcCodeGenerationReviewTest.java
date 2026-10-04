package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("2025")
class JdbcCodeGenerationReviewTest {

    @Test
    void generatedSelectReadsAColumnNamedUserInsteadOfTheCurrentUserFunction() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:codegen_review_user", "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE user_values (id INT, \"USER\" VARCHAR(80))");
            stmt.execute("INSERT INTO user_values VALUES (1, 'stored payload')");

            try (ResultSet rows = stmt.executeQuery(JdbcCodeGenerationUtil.generateSelectSql(conn, "user_values"))) {
                assertTrue(rows.next());
                assertEquals("stored payload", rows.getString(2));
            }
        }
    }

    @Test
    void generatedInsertAndUpdateSupportReservedColumnNames() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:codegen_review_order", "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE order_values (id INT, \"ORDER\" VARCHAR(80))");

            try (PreparedStatement insert = conn.prepareStatement(JdbcCodeGenerationUtil.generateInsertSql(conn, "order_values"))) {
                insert.setInt(1, 1);
                insert.setString(2, "first");
                assertEquals(1, insert.executeUpdate());
            }

            try (PreparedStatement update = conn.prepareStatement(JdbcCodeGenerationUtil.generateUpdateSql(conn, "order_values", "id"))) {
                update.setString(1, "second");
                update.setInt(2, 1);
                assertEquals(1, update.executeUpdate());
            }

            try (ResultSet rows = stmt.executeQuery("SELECT \"ORDER\" FROM order_values")) {
                assertTrue(rows.next());
                assertEquals("second", rows.getString(1));
            }
        }
    }
}
