package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.Dataset;

@Tag("integration")
class JdbcUtilResultMappingIntegrationTest extends JdbcUtilTestSupport {

    @Test
    @DisplayName("skip(): H2 forward-only cursor past the end reports the rows actually consumed and keeps absolute() enabled")
    public void testSkip_H2ForwardOnly_PastEnd_ReportsConsumedRows() throws Exception {
        final Field rsNoAbsoluteField = JdbcUtil.class.getDeclaredField("resultSetClassNotSupportAbsolute");
        rsNoAbsoluteField.setAccessible(true);

        try (Connection conn = JdbcUtil.createConnection("jdbc:h2:mem:skipForwardOnly" + System.nanoTime(), "sa", "")) {
            JdbcUtil.execute(conn, "CREATE TABLE skip_fwd (id INT PRIMARY KEY)");

            for (int i = 1; i <= 10; i++) {
                JdbcUtil.executeUpdate(conn, "INSERT INTO skip_fwd VALUES (?)", i);
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id FROM skip_fwd ORDER BY id")) {
                assertEquals(ResultSet.TYPE_FORWARD_ONLY, rs.getType());
                assertEquals(3, JdbcUtil.skip(rs, 3));
                assertEquals(7, JdbcUtil.skip(rs, 100L));
                assertTrue(rs.isAfterLast());
                assertEquals(0, JdbcUtil.skip(rs, 5L));
            }

            try (Statement stmt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
                 ResultSet rs = stmt.executeQuery("SELECT id FROM skip_fwd ORDER BY id")) {
                assertEquals(3, JdbcUtil.skip(rs, 3));
                assertEquals(7, JdbcUtil.skip(rs, 100L));
                assertTrue(rs.isAfterLast());
            }

            assertTrue(((Set<?>) rsNoAbsoluteField.get(null)).isEmpty(), "H2 result sets must not be blacklisted for absolute()");
        }
    }

    @Test
    @DisplayName("Dataset extraction: SELECT * over a join whose tables share column names renames the later duplicates")
    public void testDatasetExtraction_H2JoinWithDuplicateLabels() throws Exception {
        try (Connection conn = JdbcUtil.createConnection("jdbc:h2:mem:duplicateLabels" + System.nanoTime(), "sa", "")) {
            JdbcUtil.execute(conn, "CREATE TABLE dup_a (id INT PRIMARY KEY, name VARCHAR(20))");
            JdbcUtil.execute(conn, "CREATE TABLE dup_b (id INT PRIMARY KEY, a_id INT, name VARCHAR(20))");
            JdbcUtil.executeUpdate(conn, "INSERT INTO dup_a VALUES (1, 'a1')");
            JdbcUtil.executeUpdate(conn, "INSERT INTO dup_b VALUES (10, 1, 'b10')");
            final String sql = "SELECT * FROM dup_a JOIN dup_b ON dup_a.id = dup_b.a_id";
            final List<String> expectedNames = List.of("ID", "NAME", "ID_2", "A_ID", "NAME_2");

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                final Dataset dataset = JdbcUtil.extractData(rs);
                assertEquals(expectedNames, dataset.columnNames());
                assertEquals(10, (Integer) dataset.moveToRow(0).get("ID_2"));
                assertEquals("b10", dataset.moveToRow(0).get("NAME_2"));
            }

            final Dataset queried = JdbcUtil.prepareQuery(conn, sql).query();
            assertEquals(expectedNames, queried.columnNames());
            assertEquals(1, queried.size());
            assertEquals(expectedNames, JdbcUtil.prepareQuery(conn, sql).query(Jdbc.ResultExtractor.TO_DATASET).columnNames());
        }
    }
}
