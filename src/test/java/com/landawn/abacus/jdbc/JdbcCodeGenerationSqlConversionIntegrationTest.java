package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JdbcCodeGenerationSqlConversionIntegrationTest extends JdbcCodeGenerationUtilTestSupport {

    // HSQLDB does not nest block comments; nesting them would reject this statement as an unclosed comment.
    @Test
    public void testConvertedUpdateWithFlatBlockCommentRunsOnHsqldb() throws SQLException {
        final org.hsqldb.jdbc.JDBCDataSource dataSource = new org.hsqldb.jdbc.JDBCDataSource();
        dataSource.setUrl("jdbc:hsqldb:mem:converter_flat_comments");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE comment_values (id INTEGER PRIMARY KEY, amount INTEGER)");
            stmt.execute("INSERT INTO comment_values VALUES (1, 10), (2, 20)");

            final String sql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource,
                    "INSERT INTO comment_values(amount) VALUES (99 /* literal /* marker */)", "id = 1");

            assertEquals(1, stmt.executeUpdate(sql));
            try (ResultSet rows = stmt.executeQuery("SELECT amount FROM comment_values ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(99, rows.getInt(1));
                assertTrue(rows.next());
                assertEquals(20, rows.getInt(1));
            }
        } finally {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS comment_values");
            }
        }
    }

    @Test
    public void testConvertedUpdateWithTrailingCommentOnlyChangesMatchingRow() throws SQLException {
        final org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:converter_line_comments");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE comment_values (id INTEGER PRIMARY KEY, amount INTEGER)");
            stmt.execute("INSERT INTO comment_values VALUES (1, 10), (2, 20)");

            final String sql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource,
                    "INSERT INTO comment_values(amount) VALUES (99 -- preserve the row predicate\n)", "id = 1");

            assertEquals(1, stmt.executeUpdate(sql));
            try (ResultSet rows = stmt.executeQuery("SELECT amount FROM comment_values ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(99, rows.getInt(1));
                assertTrue(rows.next());
                assertEquals(20, rows.getInt(1));
                assertFalse(rows.next());
            }
        }
    }
}
