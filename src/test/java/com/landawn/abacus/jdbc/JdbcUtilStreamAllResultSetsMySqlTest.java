package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.stream.Stream;

/**
 * Integration coverage for {@link JdbcUtil#streamAllResultSets(Statement)} (the
 * {@code Stream<Dataset>} overload) against a live MySQL database, exercising the
 * <em>multiple result set</em> path that mock-based unit tests cannot drive end to end.
 *
 * <p>Two genuine multi-result-set sources are covered:
 * <ul>
 *   <li>a stored procedure with several {@code SELECT} statements, and</li>
 *   <li>a {@code ;}-separated multi-statement script via {@code allowMultiQueries=true}.</li>
 * </ul>
 *
 * <p>If MySQL is not reachable at {@code localhost:3306/stock} the tests are skipped
 * (JUnit assumption) rather than failing, so the rest of the suite is unaffected.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class JdbcUtilStreamAllResultSetsMySqlTest extends TestBase {

    private static final String URL = "jdbc:mysql://localhost:3306/stock";
    private static final String USER = "root";
    private static final String PASSWORD = "admin";

    private DataSource ds;

    @BeforeAll
    public void initDb() {
        try {
            ds = JdbcUtil.createHikariDataSource(URL, USER, PASSWORD);

            try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
                st.execute("DROP PROCEDURE IF EXISTS sars_three_rs");
                st.execute("DROP TABLE IF EXISTS sars_widget");
                st.execute("CREATE TABLE sars_widget ("
                        + "id INT PRIMARY KEY AUTO_INCREMENT, "
                        + "name VARCHAR(64), "
                        + "qty INT)");
                st.execute("INSERT INTO sars_widget (name, qty) VALUES ('a', 1), ('b', 2), ('c', 3)");
                st.execute("CREATE PROCEDURE sars_three_rs() "
                        + "BEGIN "
                        + "  SELECT name FROM sars_widget WHERE id = 1; "
                        + "  SELECT name FROM sars_widget WHERE id IN (2, 3) ORDER BY id; "
                        + "  SELECT name, qty FROM sars_widget ORDER BY id; "
                        + "END");
            }
        } catch (final SQLException | RuntimeException e) {
            // MySQL not available in this environment — skip the whole class.
            Assumptions.abort("MySQL not reachable at " + URL + " (" + e.getMessage() + ")");
        }
    }

    @AfterAll
    public void dropDb() throws SQLException {
        if (ds == null) {
            return;
        }
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP PROCEDURE IF EXISTS sars_three_rs");
            st.execute("DROP TABLE IF EXISTS sars_widget");
        }
    }

    // A stored procedure that issues three SELECTs yields three Datasets, in order,
    // each carrying its own columns and rows.
    @Test
    public void testStreamAllResultSets_StoredProcedure_ThreeResults() throws SQLException {
        try (Connection conn = ds.getConnection();
                CallableStatement cs = conn.prepareCall("{call sars_three_rs()}")) {

            cs.execute();

            final List<Dataset> datasets = JdbcUtil.streamAllResultSets(cs).toList();

            assertEquals(3, datasets.size(), "should yield one Dataset per SELECT in the procedure");

            final Dataset d1 = datasets.get(0);
            assertEquals(List.of("name"), d1.columnNames());
            assertEquals(1, d1.size());
            assertEquals("a", d1.getColumn("name").get(0));

            final Dataset d2 = datasets.get(1);
            assertEquals(List.of("name"), d2.columnNames());
            assertEquals(2, d2.size());
            assertEquals(List.of("b", "c"), d2.getColumn("name"));

            final Dataset d3 = datasets.get(2);
            assertEquals(List.of("name", "qty"), d3.columnNames());
            assertEquals(3, d3.size());
            assertEquals(List.of("a", "b", "c"), d3.getColumn("name"));
            assertEquals(List.of(1, 2, 3), d3.getColumn("qty"));
        }
    }

    // The same procedure, consumed lazily via the Stream API (onClose closes the
    // CallableStatement) — verifies the stream maps each ResultSet to a Dataset.
    @Test
    public void testStreamAllResultSets_StoredProcedure_StreamPipeline() throws SQLException {
        final Connection conn = ds.getConnection();
        final CallableStatement cs = conn.prepareCall("{call sars_three_rs()}");
        cs.execute();

        final List<Integer> rowCounts;
        try (Stream<Dataset> stream = JdbcUtil.streamAllResultSets(cs)
                .onClose(() -> {
                    JdbcUtil.closeQuietly(cs);
                    JdbcUtil.closeQuietly(conn);
                })) {
            rowCounts = stream.map(Dataset::size).toList();
        }

        assertEquals(List.of(1, 2, 3), rowCounts);
    }

    // A ';'-separated multi-statement script (allowMultiQueries=true) is another genuine
    // multi-result-set Statement; streamAllResultSets walks every SELECT's result set.
    @Test
    public void testStreamAllResultSets_MultiQueryScript_TwoResults() throws SQLException {
        final DataSource mqDs = JdbcUtil.createHikariDataSource(
                URL + "?allowMultiQueries=true", USER, PASSWORD);

        try (Connection conn = mqDs.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT name FROM sars_widget WHERE id = 1; "
                    + "SELECT name, qty FROM sars_widget ORDER BY id");

            final List<Dataset> datasets = JdbcUtil.streamAllResultSets(stmt).toList();

            assertEquals(2, datasets.size());
            assertEquals(1, datasets.get(0).size());
            assertEquals("a", datasets.get(0).getColumn("name").get(0));
            assertEquals(3, datasets.get(1).size());
            assertEquals(List.of("name", "qty"), datasets.get(1).columnNames());
        }
    }

    // A single-result statement still produces exactly one Dataset (boundary: the
    // multi-result iteration terminates after the first/only result set).
    @Test
    public void testStreamAllResultSets_SingleResult() throws SQLException {
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT name, qty FROM sars_widget ORDER BY id");

            final List<Dataset> datasets = JdbcUtil.streamAllResultSets(stmt).toList();

            assertEquals(1, datasets.size());
            assertEquals(3, datasets.get(0).size());
            assertEquals(List.of("a", "b", "c"), datasets.get(0).getColumn("name"));
        }
    }

    // null Statement is rejected up front.
    @Test
    public void testStreamAllResultSets_NullStatement_Throws() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.streamAllResultSets((Statement) null));
    }

    // An UPDATE/DDL-only statement exposes no result set, so the stream is empty.
    @Test
    public void testStreamAllResultSets_NoResultSet_EmptyStream() throws SQLException {
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            final boolean isResultSet = stmt.execute("UPDATE sars_widget SET qty = qty WHERE id = 1");
            assertFalse(isResultSet);

            assertTrue(JdbcUtil.streamAllResultSets(stmt).toList().isEmpty());
        }
    }
}
