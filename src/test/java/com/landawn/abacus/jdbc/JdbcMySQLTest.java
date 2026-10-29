package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.Tuple.Tuple4;
import com.landawn.abacus.util.stream.Stream;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Integration coverage against a live MySQL database for paths that mock-based unit tests
 * cannot drive end to end:
 * <ul>
 *   <li>{@link JdbcUtil#streamAllResultSets(Statement)} — the multiple-result-set walker.</li>
 *   <li>Every {@link CallableQuery} method that invokes the private
 *       {@code drainRemainingResultsForOutParams()} helper — exercised against MySQL
 *       stored procedures that emit OUT params alongside zero, one, or many result sets
 *       (plus update counts), so the drain behaviour can actually fail if broken.</li>
 *   <li>Multiple results streamed by Connector/J ({@code fetchSize = Integer.MIN_VALUE}), which leave the connection
 *       unusable if any of them is closed early or left unread.</li>
 * </ul>
 *
 * <p>Enable explicitly with the {@code mysql-tests} Maven profile and configure an isolated
 * database through {@code jdbc.mysql.url/user/password} or {@code ABACUS_JDBC_MYSQL_URL/USER/PASSWORD}.
 * Once enabled, connection and fixture failures fail the build.
 */
@Tag("mysql")
@EnabledIfSystemProperty(named = "jdbc.mysql.enabled", matches = "true")
@TestInstance(Lifecycle.PER_CLASS)
public class JdbcMySQLTest extends TestBase {

    private static final String URL = System.getProperty("jdbc.mysql.url", System.getenv("ABACUS_JDBC_MYSQL_URL"));
    private static final String USER = System.getProperty("jdbc.mysql.user", System.getenv("ABACUS_JDBC_MYSQL_USER"));
    private static final String PASSWORD = System.getProperty("jdbc.mysql.password", System.getenv("ABACUS_JDBC_MYSQL_PASSWORD"));

    private HikariDataSource ds;

    @BeforeAll
    public void initDb() {
        assertTrue(URL != null && !URL.isBlank(), "Configure jdbc.mysql.url or ABACUS_JDBC_MYSQL_URL for an isolated test database");
        assertTrue(USER != null && !USER.isBlank(), "Configure jdbc.mysql.user or ABACUS_JDBC_MYSQL_USER");
        try {
            ds = (HikariDataSource) JdbcUtil.createHikariDataSource(URL, USER, PASSWORD);

            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                // ---- streamAllResultSets fixtures ----
                st.execute("DROP PROCEDURE IF EXISTS sars_three_rs");
                st.execute("DROP TABLE IF EXISTS sars_widget");
                st.execute("CREATE TABLE sars_widget (" + "id INT PRIMARY KEY AUTO_INCREMENT, " + "name VARCHAR(64), " + "qty INT)");
                st.execute("INSERT INTO sars_widget (name, qty) VALUES ('a', 1), ('b', 2), ('c', 3)");
                st.execute("CREATE PROCEDURE sars_three_rs() " + "BEGIN " + "  SELECT name FROM sars_widget WHERE id = 1; "
                        + "  SELECT name FROM sars_widget WHERE id IN (2, 3) ORDER BY id; " + "  SELECT name, qty FROM sars_widget ORDER BY id; " + "END");
                // Procedures that fail after their SELECTs: the error arrives only once the preceding rows are read.
                st.execute("DROP PROCEDURE IF EXISTS sars_fail_after_one");
                st.execute("DROP PROCEDURE IF EXISTS sars_fail_after_three");
                st.execute("CREATE PROCEDURE sars_fail_after_one() BEGIN " //
                        + "  SELECT name FROM sars_widget ORDER BY id; " //
                        + "  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sars failure'; " //
                        + "END");
                st.execute("CREATE PROCEDURE sars_fail_after_three() BEGIN " //
                        + "  SELECT name FROM sars_widget WHERE id = 1; " //
                        + "  SELECT name FROM sars_widget WHERE id IN (2, 3) ORDER BY id; " //
                        + "  SELECT name, qty FROM sars_widget ORDER BY id; " //
                        + "  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sars failure'; " //
                        + "END");

                // ---- CallableQuery drain fixtures (cq_drain_*) ----
                for (String name : new String[] { "cq_drain_no_rs", "cq_drain_inout", "cq_drain_null_out", "cq_drain_one_rs", "cq_drain_one_main_one_side",
                        "cq_drain_empty_rs", "cq_drain_two_rs", "cq_drain_three_rs", "cq_drain_five_rs", "cq_drain_rs_dml_rs", "cq_drain_for_bean" }) {
                    st.execute("DROP PROCEDURE IF EXISTS " + name);
                }
                st.execute("DROP TABLE IF EXISTS cq_drain_widget");
                st.execute("DROP TABLE IF EXISTS cq_drain_scratch");
                st.execute("CREATE TABLE cq_drain_widget (" + "id INT PRIMARY KEY AUTO_INCREMENT, " + "name VARCHAR(64), " + "qty INT)");
                st.execute("INSERT INTO cq_drain_widget (name, qty) VALUES " + "('alpha', 10), ('bravo', 20), ('charlie', 30), ('delta', 40), ('echo', 50)");
                // A separate writeable table — used by the procedure that mixes DML with SELECTs,
                // so the SELECT * FROM ... at the end can reliably reflect a row count touched by
                // the procedure without contaminating cq_drain_widget across tests.
                st.execute("CREATE TABLE cq_drain_scratch (" + "id INT PRIMARY KEY, " + "marker VARCHAR(32))");

                // OUT-params only, no SELECT.
                st.execute("CREATE PROCEDURE cq_drain_no_rs(IN p_in INT, OUT p_doubled INT, OUT p_label VARCHAR(64)) " //
                        + "BEGIN " //
                        + "  SET p_doubled = p_in * 2; " //
                        + "  SET p_label = CONCAT('val_', p_in); " //
                        + "END");

                // INOUT param, no SELECT.
                st.execute("CREATE PROCEDURE cq_drain_inout(INOUT p_value INT) " //
                        + "BEGIN " //
                        + "  SET p_value = p_value + 100; " //
                        + "END");

                // OUT params never assigned by the body → MySQL leaves them NULL.
                st.execute("CREATE PROCEDURE cq_drain_null_out(IN p_unused INT, OUT p_will_be_null INT, OUT p_label VARCHAR(64)) " //
                        + "BEGIN " //
                        + "  SELECT p_unused; " // emit a side-effect RS so drain has work to do
                        + "END");

                // One SELECT + OUT params populated via aggregates.
                st.execute("CREATE PROCEDURE cq_drain_one_rs(IN p_min_qty INT, OUT p_count INT, OUT p_max_qty INT) " //
                        + "BEGIN " //
                        + "  SELECT id, name, qty FROM cq_drain_widget WHERE qty >= p_min_qty ORDER BY id; " //
                        + "  SELECT COUNT(*), MAX(qty) INTO p_count, p_max_qty FROM cq_drain_widget WHERE qty >= p_min_qty; " //
                        + "END");

                // First SELECT is the "main" payload; a second side-effect SELECT exists too,
                // so single-RS callers must drain the second one for OUT params to be reliable.
                st.execute("CREATE PROCEDURE cq_drain_one_main_one_side(IN p_in INT, OUT p_out INT) " //
                        + "BEGIN " //
                        + "  SELECT id, name, qty FROM cq_drain_widget WHERE id = p_in; " // main
                        + "  SELECT 'side-effect' AS note, COUNT(*) AS c FROM cq_drain_widget; " // side
                        + "  SET p_out = p_in * 7; " //
                        + "END");

                // Same shape but the main SELECT returns no rows — exercises the empty-RS path.
                st.execute("CREATE PROCEDURE cq_drain_empty_rs(IN p_in INT, OUT p_count INT) " //
                        + "BEGIN " //
                        + "  SELECT id, name, qty FROM cq_drain_widget WHERE id = -1; " // empty
                        + "  SELECT COUNT(*) INTO p_count FROM cq_drain_widget; " //
                        + "END");

                st.execute("CREATE PROCEDURE cq_drain_two_rs(IN p_min_qty INT, OUT p_out INT, OUT p_msg VARCHAR(64)) " //
                        + "BEGIN " //
                        + "  SELECT id, name FROM cq_drain_widget WHERE qty >= p_min_qty ORDER BY id; " //
                        + "  SELECT name, qty FROM cq_drain_widget WHERE qty < p_min_qty ORDER BY id; " //
                        + "  SET p_out = p_min_qty; " //
                        + "  SET p_msg = CONCAT('threshold=', p_min_qty); " //
                        + "END");

                st.execute("CREATE PROCEDURE cq_drain_three_rs(IN p_in INT, OUT p_out INT) " //
                        + "BEGIN " //
                        + "  SELECT id, name FROM cq_drain_widget WHERE id <= 2 ORDER BY id; " //
                        + "  SELECT id, name FROM cq_drain_widget WHERE id BETWEEN 3 AND 4 ORDER BY id; " //
                        + "  SELECT id, name FROM cq_drain_widget WHERE id >= 5 ORDER BY id; " //
                        + "  SET p_out = p_in * 3; " //
                        + "END");

                // Five SELECTs + OUT — used to confirm that callers that only take the first 2 or 3
                // result sets still drain the remaining ones before reading OUT params.
                st.execute("CREATE PROCEDURE cq_drain_five_rs(IN p_in INT, OUT p_out INT) " //
                        + "BEGIN " //
                        + "  SELECT 1 AS k, 'rs1' AS v; " //
                        + "  SELECT 2 AS k, 'rs2' AS v; " //
                        + "  SELECT 3 AS k, 'rs3' AS v; " //
                        + "  SELECT 4 AS k, 'rs4' AS v; " //
                        + "  SELECT 5 AS k, 'rs5' AS v; " //
                        + "  SET p_out = p_in + 999; " //
                        + "END");

                // SELECT, then DML (update count), then SELECT — drain must skip update counts
                // as well as result sets for the OUT param to be reliable.
                st.execute("CREATE PROCEDURE cq_drain_rs_dml_rs(IN p_marker VARCHAR(32), OUT p_out INT) " //
                        + "BEGIN " //
                        + "  SELECT id, name FROM cq_drain_widget ORDER BY id LIMIT 1; " //
                        + "  DELETE FROM cq_drain_scratch WHERE id = 1; " //
                        + "  INSERT INTO cq_drain_scratch (id, marker) VALUES (1, p_marker); " //
                        + "  SELECT id, marker FROM cq_drain_scratch WHERE id = 1; " //
                        + "  SET p_out = 4242; " //
                        + "END");

                // RS shaped for reflection-based row mapping into the static Widget bean below.
                st.execute("CREATE PROCEDURE cq_drain_for_bean(IN p_min_qty INT, OUT p_count INT) " //
                        + "BEGIN " //
                        + "  SELECT id, name, qty FROM cq_drain_widget WHERE qty >= p_min_qty ORDER BY id; " //
                        + "  SELECT COUNT(*) INTO p_count FROM cq_drain_widget WHERE qty >= p_min_qty; " //
                        + "END");
            }
        } catch (final SQLException | RuntimeException e) {
            org.junit.jupiter.api.Assertions.fail("MySQL test fixture initialization failed", e);
        }
    }

    @AfterAll
    public void dropDb() throws SQLException {
        if (ds == null) {
            return;
        }
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP PROCEDURE IF EXISTS sars_three_rs");
            st.execute("DROP PROCEDURE IF EXISTS sars_fail_after_one");
            st.execute("DROP PROCEDURE IF EXISTS sars_fail_after_three");
            st.execute("DROP TABLE IF EXISTS sars_widget");
            for (String name : new String[] { "cq_drain_no_rs", "cq_drain_inout", "cq_drain_null_out", "cq_drain_one_rs", "cq_drain_one_main_one_side",
                    "cq_drain_empty_rs", "cq_drain_two_rs", "cq_drain_three_rs", "cq_drain_five_rs", "cq_drain_rs_dml_rs", "cq_drain_for_bean" }) {
                st.execute("DROP PROCEDURE IF EXISTS " + name);
            }
            st.execute("DROP TABLE IF EXISTS cq_drain_widget");
            st.execute("DROP TABLE IF EXISTS cq_drain_scratch");
        } finally {
            ds.close();
        }
    }

    // ===================================================================================
    // streamAllResultSets — existing live-DB coverage for JdbcUtil.streamAllResultSets.
    // ===================================================================================

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
        try (Stream<Dataset> stream = JdbcUtil.streamAllResultSets(cs).onClose(() -> {
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
        try (HikariDataSource mqDs = (HikariDataSource) JdbcUtil.createHikariDataSource(URL + (URL.contains("?") ? "&" : "?") + "allowMultiQueries=true", USER,
                PASSWORD);
             Connection conn = mqDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT name FROM sars_widget WHERE id = 1; " + "SELECT name, qty FROM sars_widget ORDER BY id");

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
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
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
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            final boolean isResultSet = stmt.execute("UPDATE sars_widget SET qty = qty WHERE id = 1");
            assertFalse(isResultSet);

            assertTrue(JdbcUtil.streamAllResultSets(stmt).toList().isEmpty());
        }
    }

    // ===================================================================================
    // CallableQuery drain coverage — every method that calls drainRemainingResultsForOutParams().
    //
    // The crucial invariant the drain protects: OUT parameter values are only reliably
    // available once ALL result sets and update counts produced by the procedure have been
    // consumed. Each test below exercises a method against a procedure designed so that
    // a missing-or-broken drain would either (a) leak OUT params as null/stale, or (b)
    // leave buffered results that interfere with statement close. We assert both the
    // primary return value AND the OUT params so a regression on either side fails loudly.
    // ===================================================================================

    // -------------------- executeAndGetOutParameters (L2396) --------------------

    // Simplest happy path: procedure has no SELECT, drain is essentially a no-op,
    // OUT params come back populated.
    @Test
    public void testDrain_executeAndGetOutParameters_outOnly() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_no_rs(?, ?, ?)}")) {
            q.setInt(1, 7).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.VARCHAR);

            final Jdbc.OutParamResult out = q.executeAndGetOutParameters();

            assertEquals(14, (int) out.getOutParamValue(2));
            assertEquals("val_7", out.getOutParamValue(3));
        }
    }

    // The procedure emits a side-effect SELECT in addition to the OUT params. If drain
    // were skipped the buffered result set would prevent the statement from closing
    // cleanly. OUT params for unassigned values come back per the JDBC spec:
    // getInt() reports 0 for SQL NULL (only wasNull() distinguishes them), getString()
    // reports null. The framework's typed getters honor that contract.
    @Test
    public void testDrain_executeAndGetOutParameters_unassignedOutParams() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_null_out(?, ?, ?)}")) {
            q.setInt(1, 99).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.VARCHAR);

            final Jdbc.OutParamResult out = q.executeAndGetOutParameters();

            // p_will_be_null (INTEGER) is never assigned → SQL NULL is reported as null
            // (getOutParameters applies wasNull() so primitive getters don't mask NULL as 0)
            assertNull(out.getOutParamValue(2));
            // p_label (VARCHAR) is never assigned → getString() returns null
            assertNull(out.getOutParamValue(3));
        }
    }

    @Test
    public void testDrain_executeAndGetOutParameters_inoutParam() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_inout(?)}")) {
            q.setInt(1, 5).registerOutParameter(1, Types.INTEGER);

            final Jdbc.OutParamResult out = q.executeAndGetOutParameters();

            assertEquals(105, (int) out.getOutParamValue(1));
        }
    }

    // Procedure intersperses DML (update counts) between SELECTs. The drain loop must
    // walk past both update counts and result sets — otherwise the OUT param ends up
    // null/stale on strict drivers and the buffered DML count blocks close.
    @Test
    public void testDrain_executeAndGetOutParameters_rsAndUpdateCounts() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_rs_dml_rs(?, ?)}")) {
            q.setString(1, "exec-test").registerOutParameter(2, Types.INTEGER);

            final Jdbc.OutParamResult out = q.executeAndGetOutParameters();

            assertEquals(4242, (int) out.getOutParamValue(2));
        }
    }

    // OutParamResult exposes the full map of OUT values — confirm structure as well as content.
    @Test
    public void testDrain_executeAndGetOutParameters_outParamMap() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_no_rs(?, ?, ?)}")) {
            q.setInt(1, 3).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.VARCHAR);

            final Jdbc.OutParamResult out = q.executeAndGetOutParameters();
            final Map<Object, Object> values = out.getOutParamValues();

            assertEquals(2, values.size());
            assertEquals(6, values.get(2));
            assertEquals("val_3", values.get(3));
        }
    }

    // -------------------- queryAndGetOutParameters — Dataset overload (L2443) --------------------

    @Test
    public void testDrain_queryAndGetOutParameters_dataset_singleRs() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 30).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            final Tuple2<Dataset, Jdbc.OutParamResult> result = q.queryAndGetOutParameters();

            assertEquals(3, result._1.size()); // ids 3,4,5 → qty 30,40,50
            assertEquals(List.of("charlie", "delta", "echo"), result._1.getColumn("name"));
            assertEquals(3, (int) result._2.getOutParamValue(2));
            assertEquals(50, (int) result._2.getOutParamValue(3));
        }
    }

    // The procedure emits a second side-effect SELECT after the main one. Only the
    // first becomes the Dataset; the drain must consume the rest so the OUT param
    // (set AFTER both SELECTs in the body) is correctly populated.
    @Test
    public void testDrain_queryAndGetOutParameters_dataset_multiRs_drainsRest() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_main_one_side(?, ?)}")) {
            q.setInt(1, 2).registerOutParameter(2, Types.INTEGER);

            final Tuple2<Dataset, Jdbc.OutParamResult> result = q.queryAndGetOutParameters();

            assertEquals(1, result._1.size());
            assertEquals("bravo", result._1.getColumn("name").get(0));
            assertEquals(14, (int) result._2.getOutParamValue(2));
        }
    }

    @Test
    public void testDrain_queryAndGetOutParameters_dataset_emptyRs() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_empty_rs(?, ?)}")) {
            q.setInt(1, 0).registerOutParameter(2, Types.INTEGER);

            final Tuple2<Dataset, Jdbc.OutParamResult> result = q.queryAndGetOutParameters();

            assertEquals(0, result._1.size());
            assertEquals(5, (int) result._2.getOutParamValue(2)); // total widgets
        }
    }

    // -------------------- queryAndGetOutParameters(ResultExtractor) (L2480) --------------------

    @Test
    public void testDrain_queryAndGetOutParameters_resultExtractor_customExtraction() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 20).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            final Tuple2<Integer, Jdbc.OutParamResult> result = q.queryAndGetOutParameters(rs -> {
                int sum = 0;
                while (rs.next()) {
                    sum += rs.getInt("qty");
                }
                return sum;
            });

            assertEquals(20 + 30 + 40 + 50, (int) result._1);
            assertEquals(4, (int) result._2.getOutParamValue(2));
            assertEquals(50, (int) result._2.getOutParamValue(3));
        }
    }

    @Test
    public void testDrain_queryAndGetOutParameters_resultExtractor_multiRs_drainsRest() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_main_one_side(?, ?)}")) {
            q.setInt(1, 4).registerOutParameter(2, Types.INTEGER);

            final Tuple2<String, Jdbc.OutParamResult> result = q.queryAndGetOutParameters(rs -> {
                assertTrue(rs.next());
                return rs.getString("name");
            });

            assertEquals("delta", result._1);
            assertEquals(28, (int) result._2.getOutParamValue(2));
        }
    }

    @Test
    public void testDrain_queryAndGetOutParameters_resultExtractor_nullExtractor_throws() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 10).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            assertThrows(IllegalArgumentException.class, () -> q.queryAndGetOutParameters((Jdbc.ResultExtractor<?>) null));
        }
    }

    // -------------------- queryAndGetOutParameters(BiResultExtractor) (L2534) --------------------

    @Test
    public void testDrain_queryAndGetOutParameters_biResultExtractor_seeColumnLabels() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 30).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            final Tuple2<List<String>, Jdbc.OutParamResult> result = q.queryAndGetOutParameters((rs, labels) -> {
                assertEquals(List.of("id", "name", "qty"), labels);
                final java.util.ArrayList<String> names = new java.util.ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
                return names;
            });

            assertEquals(List.of("charlie", "delta", "echo"), result._1);
            assertEquals(3, (int) result._2.getOutParamValue(2));
        }
    }

    @Test
    public void testDrain_queryAndGetOutParameters_biResultExtractor_multiRs_drainsRest() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_main_one_side(?, ?)}")) {
            q.setInt(1, 3).registerOutParameter(2, Types.INTEGER);

            final Tuple2<Integer, Jdbc.OutParamResult> result = q.queryAndGetOutParameters((rs, labels) -> {
                assertTrue(labels.contains("id"));
                int n = 0;
                while (rs.next()) {
                    n++;
                }
                return n;
            });

            assertEquals(1, (int) result._1);
            assertEquals(21, (int) result._2.getOutParamValue(2));
        }
    }

    // -------------------- query2ResultSetsAndGetOutParameters (L2773) --------------------

    @Test
    public void testDrain_query2ResultSets_exactlyTwo() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_two_rs(?, ?, ?)}")) {
            q.setInt(1, 30).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.VARCHAR);

            final Tuple3<List<String>, List<Integer>, Jdbc.OutParamResult> result = q.query2ResultSetsAndGetOutParameters( //
                    (rs, labels) -> {
                        java.util.ArrayList<String> names = new java.util.ArrayList<>();
                        while (rs.next()) {
                            names.add(rs.getString("name"));
                        }
                        return names;
                    }, //
                    (rs, labels) -> {
                        java.util.ArrayList<Integer> qtys = new java.util.ArrayList<>();
                        while (rs.next()) {
                            qtys.add(rs.getInt("qty"));
                        }
                        return qtys;
                    });

            assertEquals(List.of("charlie", "delta", "echo"), result._1);
            assertEquals(List.of(10, 20), result._2);
            assertEquals(30, (int) result._3.getOutParamValue(2));
            assertEquals("threshold=30", result._3.getOutParamValue(3));
        }
    }

    // 5 SELECTs, we ask for the first 2 — drain must consume the trailing 3 so the OUT
    // param (set at the very end of the body) is reliable.
    @Test
    public void testDrain_query2ResultSets_moreThanTwo_drainsRest() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_five_rs(?, ?)}")) {
            q.setInt(1, 1).registerOutParameter(2, Types.INTEGER);

            final Tuple3<Integer, Integer, Jdbc.OutParamResult> result = q.query2ResultSetsAndGetOutParameters( //
                    (rs, labels) -> {
                        assertTrue(rs.next());
                        return rs.getInt("k");
                    }, //
                    (rs, labels) -> {
                        assertTrue(rs.next());
                        return rs.getInt("k");
                    });

            assertEquals(1, (int) result._1);
            assertEquals(2, (int) result._2);
            assertEquals(1000, (int) result._3.getOutParamValue(2));
        }
    }

    // Only one result set is emitted — second extractor never runs, result2 is null,
    // and drain still has nothing left to consume.
    @Test
    public void testDrain_query2ResultSets_fewerThanTwo_secondIsNull() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 40).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            final AtomicInteger secondRan = new AtomicInteger();
            final Tuple3<Integer, Integer, Jdbc.OutParamResult> result = q.query2ResultSetsAndGetOutParameters( //
                    (rs, labels) -> {
                        int n = 0;
                        while (rs.next()) {
                            n++;
                        }
                        return n;
                    }, //
                    (rs, labels) -> {
                        secondRan.incrementAndGet();
                        return -1;
                    });

            assertEquals(2, (int) result._1);
            assertNull(result._2);
            assertEquals(0, secondRan.get());
            assertEquals(2, (int) result._3.getOutParamValue(2));
            assertEquals(50, (int) result._3.getOutParamValue(3));
        }
    }

    // -------------------- query3ResultSetsAndGetOutParameters (L2864) --------------------

    @Test
    public void testDrain_query3ResultSets_exactlyThree() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_three_rs(?, ?)}")) {
            q.setInt(1, 11).registerOutParameter(2, Types.INTEGER);

            final Tuple4<List<Integer>, List<Integer>, List<Integer>, Jdbc.OutParamResult> result = q.query3ResultSetsAndGetOutParameters( //
                    (rs, labels) -> collectIds(rs), //
                    (rs, labels) -> collectIds(rs), //
                    (rs, labels) -> collectIds(rs));

            assertEquals(List.of(1, 2), result._1);
            assertEquals(List.of(3, 4), result._2);
            assertEquals(List.of(5), result._3);
            assertEquals(33, (int) result._4.getOutParamValue(2));
        }
    }

    // 5 RS, we take 3, drain absorbs the remaining 2.
    @Test
    public void testDrain_query3ResultSets_moreThanThree_drainsRest() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_five_rs(?, ?)}")) {
            q.setInt(1, 7).registerOutParameter(2, Types.INTEGER);

            final Tuple4<Integer, Integer, Integer, Jdbc.OutParamResult> result = q.query3ResultSetsAndGetOutParameters( //
                    (rs, labels) -> readK(rs), //
                    (rs, labels) -> readK(rs), //
                    (rs, labels) -> readK(rs));

            assertEquals(1, (int) result._1);
            assertEquals(2, (int) result._2);
            assertEquals(3, (int) result._3);
            assertEquals(1006, (int) result._4.getOutParamValue(2));
        }
    }

    // Only 2 RS — third extractor never runs.
    @Test
    public void testDrain_query3ResultSets_fewerThanThree_thirdIsNull() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_two_rs(?, ?, ?)}")) {
            q.setInt(1, 30).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.VARCHAR);

            final AtomicInteger thirdRan = new AtomicInteger();
            final Tuple4<Integer, Integer, Integer, Jdbc.OutParamResult> result = q.query3ResultSetsAndGetOutParameters( //
                    (rs, labels) -> countRows(rs), //
                    (rs, labels) -> countRows(rs), //
                    (rs, labels) -> {
                        thirdRan.incrementAndGet();
                        return -1;
                    });

            assertEquals(3, (int) result._1);
            assertEquals(2, (int) result._2);
            assertNull(result._3);
            assertEquals(0, thirdRan.get());
            assertEquals(30, (int) result._4.getOutParamValue(2));
            assertEquals("threshold=30", result._4.getOutParamValue(3));
        }
    }

    // -------------------- listAndGetOutParameters(Class) (L2958) --------------------

    @Test
    public void testDrain_listAndGetOutParameters_class_beanMapping() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_for_bean(?, ?)}")) {
            q.setInt(1, 20).registerOutParameter(2, Types.INTEGER);

            final Tuple2<List<Widget>, Jdbc.OutParamResult> result = q.listAndGetOutParameters(Widget.class);

            assertEquals(4, result._1.size());
            assertEquals("bravo", result._1.get(0).getName());
            assertEquals(20, result._1.get(0).getQty());
            assertEquals(4, (int) result._2.getOutParamValue(2));
        }
    }

    @Test
    public void testDrain_listAndGetOutParameters_class_nullTargetType_throws() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_for_bean(?, ?)}")) {
            q.setInt(1, 0).registerOutParameter(2, Types.INTEGER);
            assertThrows(IllegalArgumentException.class, () -> q.listAndGetOutParameters((Class<?>) null));
        }
    }

    // -------------------- listAndGetOutParameters(RowMapper) (L3010) --------------------

    @Test
    public void testDrain_listAndGetOutParameters_rowMapper_customMapping() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 20).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            final Tuple2<List<String>, Jdbc.OutParamResult> result = q.listAndGetOutParameters( //
                    rs -> rs.getString("name") + "/" + rs.getInt("qty"));

            assertEquals(List.of("bravo/20", "charlie/30", "delta/40", "echo/50"), result._1);
            assertEquals(4, (int) result._2.getOutParamValue(2));
            assertEquals(50, (int) result._2.getOutParamValue(3));
        }
    }

    // Empty RS still produces empty list, and OUT params (set by an aggregate INTO after
    // the empty SELECT) come back correctly because drain advances past the second SELECT.
    @Test
    public void testDrain_listAndGetOutParameters_rowMapper_emptyRs() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_empty_rs(?, ?)}")) {
            q.setInt(1, 0).registerOutParameter(2, Types.INTEGER);

            final Tuple2<List<Object>, Jdbc.OutParamResult> result = q.listAndGetOutParameters(rs -> rs.getObject(1));

            assertTrue(result._1.isEmpty());
            assertEquals(5, (int) result._2.getOutParamValue(2));
        }
    }

    @Test
    public void testDrain_listAndGetOutParameters_rowMapper_nullMapper_throws() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 0).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);
            assertThrows(IllegalArgumentException.class, () -> q.listAndGetOutParameters((Jdbc.RowMapper<?>) null));
        }
    }

    // -------------------- listAndGetOutParameters(RowFilter, RowMapper) (L3075) --------------------

    @Test
    public void testDrain_listAndGetOutParameters_rowFilterAndMapper_filterApplied() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 0).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            final Tuple2<List<String>, Jdbc.OutParamResult> result = q.listAndGetOutParameters( //
                    rs -> rs.getInt("qty") >= 30, //
                    rs -> rs.getString("name"));

            assertEquals(List.of("charlie", "delta", "echo"), result._1);
            assertEquals(5, (int) result._2.getOutParamValue(2)); // count is unconditional
        }
    }

    // Filter rejects everything — drain still needs to consume the second SELECT inside
    // the procedure for the aggregate-INTO OUT param to materialize.
    @Test
    public void testDrain_listAndGetOutParameters_rowFilterAndMapper_filterRejectsAll() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 0).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            final Tuple2<List<String>, Jdbc.OutParamResult> result = q.listAndGetOutParameters( //
                    rs -> false, //
                    rs -> rs.getString("name"));

            assertTrue(result._1.isEmpty());
            assertEquals(5, (int) result._2.getOutParamValue(2));
        }
    }

    // -------------------- listAndGetOutParameters(BiRowMapper) (L3144) --------------------

    @Test
    public void testDrain_listAndGetOutParameters_biRowMapper_seeColumnLabels() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 30).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            final Tuple2<List<Map<String, Object>>, Jdbc.OutParamResult> result = q.listAndGetOutParameters((rs, labels) -> {
                assertEquals(List.of("id", "name", "qty"), labels);
                Map<String, Object> row = new HashMap<>();
                for (String label : labels) {
                    row.put(label, rs.getObject(label));
                }
                return row;
            });

            assertEquals(3, result._1.size());
            assertEquals("charlie", result._1.get(0).get("name"));
            assertEquals(3, (int) result._2.getOutParamValue(2));
            assertEquals(50, (int) result._2.getOutParamValue(3));
        }
    }

    @Test
    public void testDrain_listAndGetOutParameters_biRowMapper_multiRsScenario_drainsRest() throws SQLException {
        // Use the procedure with two SELECTs (the empty-RS one's first SELECT is empty),
        // verify the second SELECT is still drained and OUT param is correct.
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_empty_rs(?, ?)}")) {
            q.setInt(1, 0).registerOutParameter(2, Types.INTEGER);

            final Tuple2<List<String>, Jdbc.OutParamResult> result = q.listAndGetOutParameters( //
                    (rs, labels) -> rs.getString("name"));

            assertTrue(result._1.isEmpty());
            assertEquals(5, (int) result._2.getOutParamValue(2));
        }
    }

    // -------------------- listAndGetOutParameters(BiRowFilter, BiRowMapper) (L3224) --------------------

    @Test
    public void testDrain_listAndGetOutParameters_biRowFilterAndMapper_filterApplied() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 0).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            final Tuple2<List<String>, Jdbc.OutParamResult> result = q.listAndGetOutParameters( //
                    (rs, labels) -> {
                        assertTrue(labels.contains("qty"));
                        return rs.getInt("qty") < 30;
                    }, //
                    (rs, labels) -> rs.getString("name"));

            assertEquals(List.of("alpha", "bravo"), result._1);
            assertEquals(5, (int) result._2.getOutParamValue(2));
            assertEquals(50, (int) result._2.getOutParamValue(3));
        }
    }

    @Test
    public void testDrain_listAndGetOutParameters_biRowFilterAndMapper_filterRejectsAll() throws SQLException {
        try (CallableQuery q = JdbcUtil.prepareCallableQuery(ds, "{call cq_drain_one_rs(?, ?, ?)}")) {
            q.setInt(1, 0).registerOutParameter(2, Types.INTEGER).registerOutParameter(3, Types.INTEGER);

            final Tuple2<List<String>, Jdbc.OutParamResult> result = q.listAndGetOutParameters( //
                    (rs, labels) -> false, //
                    (rs, labels) -> rs.getString("name"));

            assertTrue(result._1.isEmpty());
            assertEquals(5, (int) result._2.getOutParamValue(2));
        }
    }

    // ===================================================================================
    // Connector/J streaming (fetchSize = Integer.MIN_VALUE) multiple results. Connector/J
    // cannot close a streamed result while it streams the next one, and rejects every later
    // statement on the connection until all of them are read. Each test checks that the
    // results arrive unread and that the connection accepts a statement however traversal ends.
    // ===================================================================================

    @Test
    public void testStreamingResults_FullTraversal() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streamingThreeResults(conn)) {
                assertEquals(List.of(1, 2, 3), q.queryAllResultSets(JdbcMySQLTest::countRows));
            }
            assertConnectionRestored(conn);
        }
    }

    @Test
    public void testStreamingResults_PartialReads() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streamingThreeResults(conn)) {
                assertEquals(List.of("a", "b", "a"), q.queryAllResultSets(JdbcMySQLTest::firstName));
            }
            assertConnectionRestored(conn);
        }
    }

    // The third result is never delivered: closing the statement alone would leave it unread.
    @Test
    public void testStreamingResults_FewerExtractorsThanResults() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streamingThreeResults(conn)) {
                final Tuple2<String, Integer> result = q.query2ResultSets((rs, labels) -> firstName(rs), (rs, labels) -> countRows(rs));
                assertEquals("a", result._1);
                assertEquals(2, (int) result._2);
            }
            assertConnectionRestored(conn);
        }
    }

    @Test
    public void testStreamingResults_StreamClosedEarly() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streamingThreeResults(conn)) {
                assertEquals("a", q.streamAllResultSets(JdbcMySQLTest::firstName).first().orElseNull());
            }
            assertConnectionRestored(conn);
        }
    }

    @Test
    public void testStreamingResults_ExtractorFailure() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streamingThreeResults(conn)) {
                final SQLException thrown = assertThrows(SQLException.class, () -> q.queryAllResultSets(rs -> {
                    rs.next();
                    throw new SQLException("extractor failure");
                }));
                assertEquals("extractor failure", thrown.getMessage());
            }
            assertConnectionRestored(conn);
        }
    }

    @Test
    public void testStreamingResults_ReusedQuery() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streamingThreeResults(conn).closeAfterExecution(false)) {
                assertEquals("a", q.query2ResultSets((rs, labels) -> firstName(rs), (rs, labels) -> firstName(rs))._1);
                assertEquals(List.of(1, 2, 3), q.queryAllResultSets(JdbcMySQLTest::countRows));
            }
            assertConnectionRestored(conn);
        }
    }

    @Test
    public void testStreamingResults_RawStatementStreamClosedEarly() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableStatement cs = conn.prepareCall("{call sars_three_rs()}", java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY)) {
                cs.setFetchSize(Integer.MIN_VALUE);
                cs.execute();

                try (Stream<String> names = JdbcUtil.streamAllResultSets(cs, JdbcMySQLTest::firstName)) {
                    assertEquals("a", names.first().orElseNull());
                }
            }
            assertConnectionRestored(conn);
        }
    }

    @Test
    public void testStreamingResults_ExecuteAndGetOutParameters() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streamingThreeResults(conn)) {
                assertNotNull(q.executeAndGetOutParameters());
            }
            assertConnectionRestored(conn);
        }
    }

    // A single streamed SELECT read through the multiple-result API: its only result is also the last one.
    @Test
    public void testStreamingResults_SingleSelect() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (PreparedQuery q = JdbcUtil.prepareQuery(conn, "SELECT name FROM sars_widget ORDER BY id").setFetchSize(Integer.MIN_VALUE)) {
                assertEquals(List.of(3), q.queryAllResultSets(JdbcMySQLTest::countRows));
            }
            assertConnectionRestored(conn);
        }
    }

    // The extractor stops early, so the procedure's error is read only when its result is closed.
    @Test
    public void testStreamingResults_ProcedureErrorAfterPartialRead() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streaming(conn, "sars_fail_after_one")) {
                final SQLException thrown = assertThrows(SQLException.class, () -> q.queryAllResultSets(JdbcMySQLTest::firstName));
                assertEquals("sars failure", thrown.getMessage());
            }
            assertConnectionRestored(conn);
        }
    }

    // The procedure's error follows a result that is never delivered; it is read while discarding that result.
    @Test
    public void testStreamingResults_ProcedureErrorAfterUnconsumedResults() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streaming(conn, "sars_fail_after_three")) {
                final SQLException thrown = assertThrows(SQLException.class,
                        () -> q.query2ResultSets((rs, labels) -> firstName(rs), (rs, labels) -> countRows(rs)));
                assertEquals("sars failure", thrown.getMessage());
            }
            assertConnectionRestored(conn);
        }
    }

    @Test
    public void testStreamingResults_ProcedureErrorWhenStreamStopsEarly() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streaming(conn, "sars_fail_after_three")) {
                final RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
                    try (Stream<String> names = q.streamAllResultSets(JdbcMySQLTest::firstName)) {
                        names.first();
                    }
                });
                assertEquals("sars failure", rootCause(thrown).getMessage());
            }
            assertConnectionRestored(conn);
        }
    }

    @Test
    public void testStreamingResults_ProcedureErrorDoesNotReplaceExtractorFailure() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streaming(conn, "sars_fail_after_one")) {
                final SQLException thrown = assertThrows(SQLException.class, () -> q.queryAllResultSets(rs -> {
                    rs.next();
                    throw new SQLException("extractor failure");
                }));
                assertEquals("extractor failure", thrown.getMessage());
                assertEquals(1, thrown.getSuppressed().length);
                assertEquals("sars failure", thrown.getSuppressed()[0].getMessage());
            }
            assertConnectionRestored(conn);
        }
    }

    // These APIs also allow no OUT registrations, which Connector/J requires when streaming. Both extractor overloads
    // must retain trailing results until they are drained, including when the first extractor returns early or fails.
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void testStreamingSingleResultAndOutParameters_PartialReadAndReuse(final boolean withLabels) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            try (CallableQuery q = streamingThreeResults(conn).closeAfterExecution(false)) {
                for (int execution = 0; execution < 2; execution++) {
                    final Tuple2<String, Jdbc.OutParamResult> result = withLabels ? q.queryAndGetOutParameters((rs, labels) -> firstName(rs))
                            : q.queryAndGetOutParameters(JdbcMySQLTest::firstName);
                    assertEquals("a", result._1);
                    assertNotNull(result._2);
                    assertConnectionRestored(conn);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void testStreamingSingleResultAndOutParameters_ProcedureError(final boolean withLabels) throws SQLException {
        for (final String procedure : List.of("sars_fail_after_one", "sars_fail_after_three")) {
            try (Connection conn = ds.getConnection()) {
                try (CallableQuery q = streaming(conn, procedure)) {
                    final SQLException thrown = assertThrows(SQLException.class, () -> {
                        if (withLabels) {
                            q.queryAndGetOutParameters((rs, labels) -> firstName(rs));
                        } else {
                            q.queryAndGetOutParameters(JdbcMySQLTest::firstName);
                        }
                    });
                    assertEquals("sars failure", thrown.getMessage());
                }
                assertConnectionRestored(conn);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void testStreamingSingleResultAndOutParameters_ExtractorFailureDrainsTrailingResults(final boolean withLabels) throws SQLException {
        for (final String procedure : List.of("sars_three_rs", "sars_fail_after_one", "sars_fail_after_three")) {
            try (Connection conn = ds.getConnection()) {
                try (CallableQuery q = streaming(conn, procedure)) {
                    final SQLException extractionFailure = new SQLException("extractor failure");
                    final Jdbc.ResultExtractor<String> extractor = rs -> {
                        rs.next();
                        throw extractionFailure;
                    };
                    final SQLException thrown = assertThrows(SQLException.class, () -> {
                        if (withLabels) {
                            q.queryAndGetOutParameters((rs, labels) -> extractor.apply(rs));
                        } else {
                            q.queryAndGetOutParameters(extractor);
                        }
                    });
                    org.junit.jupiter.api.Assertions.assertSame(extractionFailure, thrown);
                    assertEquals(procedure.equals("sars_three_rs") ? 0 : 1, thrown.getSuppressed().length);
                    if (thrown.getSuppressed().length != 0) {
                        assertEquals("sars failure", thrown.getSuppressed()[0].getMessage());
                    }
                }
                assertConnectionRestored(conn);
            }
        }
    }

    private static CallableQuery streamingThreeResults(final Connection conn) throws SQLException {
        return streaming(conn, "sars_three_rs");
    }

    private static CallableQuery streaming(final Connection conn, final String procedure) throws SQLException {
        return JdbcUtil.prepareCallableQuery(conn, "{call " + procedure + "()}").setFetchSize(Integer.MIN_VALUE);
    }

    private static Throwable rootCause(final Throwable failure) {
        Throwable cause = failure;

        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        return cause;
    }

    // Connector/J raises net_write_timeout while streaming and restores it when the last streamed result is closed.
    // The query also proves that the connection accepts another statement. Comparing with the global value does not
    // depend on what an earlier borrower of the pooled connection left behind.
    private static void assertConnectionRestored(final Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT @@session.net_write_timeout, @@global.net_write_timeout")) {
            assertTrue(rs.next());
            assertEquals(rs.getInt(2), rs.getInt(1), "session net_write_timeout");
        }
    }

    private static String firstName(final java.sql.ResultSet rs) throws SQLException {
        return rs.next() ? rs.getString("name") : null;
    }

    // ===================================================================================
    // Helpers + test bean.
    // ===================================================================================

    private static List<Integer> collectIds(final java.sql.ResultSet rs) throws SQLException {
        final java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        while (rs.next()) {
            ids.add(rs.getInt("id"));
        }
        return ids;
    }

    private static int readK(final java.sql.ResultSet rs) throws SQLException {
        assertTrue(rs.next());
        final int k = rs.getInt("k");
        return k;
    }

    private static int countRows(final java.sql.ResultSet rs) throws SQLException {
        int n = 0;
        while (rs.next()) {
            n++;
        }
        return n;
    }

    // Mirror of cq_drain_widget's column shape — used by the reflective list(Class) overload.
    public static class Widget {
        private int id;
        private String name;
        private int qty;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getQty() {
            return qty;
        }

        public void setQty(int qty) {
            this.qty = qty;
        }
    }

    // Silence unused-import-style warnings: keep an assertNotNull reference handy.
    @SuppressWarnings("unused")
    private static void touchAssert() {
        assertNotNull("");
    }
}
