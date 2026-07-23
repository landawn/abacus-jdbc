package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SqlDialect.ProductInfo;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.stream.ObjIteratorEx;

/**
 * Integration coverage for {@link JdbcUtil}'s query/transaction execution paths against a
 * live in-memory H2 database. These exercise {@code prepareQuery}, {@code prepareNamedQuery},
 * {@code beginTransaction}, and the {@code AbstractQuery} execution methods that
 * mock-based unit tests cannot drive end to end.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class JdbcUtilIntegrationTest extends TestBase {

    public static class Widget {
        private Long id;
        private String name;
        private int qty;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public int getQty() {
            return qty;
        }

        public void setQty(final int qty) {
            this.qty = qty;
        }
    }

    private DataSource ds;

    @BeforeAll
    public void initDb() throws SQLException {
        ds = JdbcUtil.createHikariDataSource("jdbc:h2:mem:jdbcutil_it;DB_CLOSE_DELAY=-1", "sa", "");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS widget (" + "id BIGINT AUTO_INCREMENT PRIMARY KEY, " + "name VARCHAR(64), " + "qty INT)");
        }
    }

    @AfterAll
    public void dropDb() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS widget");
        }
    }

    @BeforeEach
    public void cleanTable() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE widget");
        }
    }

    private long insertWidget(final String name, final int qty) throws SQLException {
        final Optional<Long> key = JdbcUtil.prepareQuery(ds, "INSERT INTO widget (name, qty) VALUES (?, ?)", true).setString(1, name).setInt(2, qty).insert();
        assertTrue(key.isPresent());
        return key.get();
    }

    // prepareQuery with auto-generated keys returns the inserted id; select reads it back.
    @Test
    public void testPrepareQuery_InsertAndSelect() throws SQLException {
        final long id = insertWidget("gizmo", 7);
        assertTrue(id > 0);

        final OptionalInt qty = JdbcUtil.prepareQuery(ds, "SELECT qty FROM widget WHERE id = ?").setLong(1, id).queryForInt();
        assertTrue(qty.isPresent());
        assertEquals(7, qty.getAsInt());

        final Nullable<String> name = JdbcUtil.prepareQuery(ds, "SELECT name FROM widget WHERE id = ?").setLong(1, id).queryForString();
        assertEquals("gizmo", name.orElse(null));
    }

    // prepareQuery update() returns the affected-row count.
    @Test
    public void testPrepareQuery_Update() throws SQLException {
        final long id = insertWidget("old", 1);

        final int updated = JdbcUtil.prepareQuery(ds, "UPDATE widget SET name = ?, qty = ? WHERE id = ?")
                .setString(1, "new")
                .setInt(2, 99)
                .setLong(3, id)
                .update();
        assertEquals(1, updated);

        final List<Widget> rows = JdbcUtil.prepareQuery(ds, "SELECT id, name, qty FROM widget WHERE id = ?").setLong(1, id).list(Widget.class);
        assertEquals(1, rows.size());
        assertEquals("new", rows.get(0).getName());
        assertEquals(99, rows.get(0).getQty());
    }

    // prepareNamedQuery binds parameters by name and maps rows to beans.
    @Test
    public void testPrepareNamedQuery_InsertAndList() throws SQLException {
        final Optional<Long> key = JdbcUtil.prepareNamedQuery(ds, "INSERT INTO widget (name, qty) VALUES (:name, :qty)", true)
                .setString("name", "named")
                .setInt("qty", 12)
                .insert();
        assertTrue(key.isPresent());

        final List<Widget> rows = JdbcUtil.prepareNamedQuery(ds, "SELECT id, name, qty FROM widget WHERE name = :name")
                .setString("name", "named")
                .list(Widget.class);
        assertEquals(1, rows.size());
        assertEquals(12, rows.get(0).getQty());
    }

    // A committed transaction persists its writes.
    @Test
    public void testBeginTransaction_Commit() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
        try {
            insertWidget("tx-commit-a", 1);
            insertWidget("tx-commit-b", 2);
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        final OptionalInt count = JdbcUtil.prepareQuery(ds, "SELECT COUNT(*) FROM widget").queryForInt();
        assertEquals(2, count.getAsInt());
    }

    // A rolled-back transaction discards its writes.
    @Test
    public void testBeginTransaction_Rollback() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
        try {
            insertWidget("tx-rollback", 5);
        } finally {
            tran.rollbackIfNotCommitted();
        }

        final OptionalInt count = JdbcUtil.prepareQuery(ds, "SELECT COUNT(*) FROM widget").queryForInt();
        assertEquals(0, count.getAsInt());
    }

    // queryForInt on an empty result set is absent (no-row branch).
    @Test
    public void testQueryForInt_NoRow_IsEmpty() throws SQLException {
        final OptionalInt v = JdbcUtil.prepareQuery(ds, "SELECT qty FROM widget WHERE id = ?").setLong(1, -1L).queryForInt();
        assertFalse(v.isPresent());
    }

    // list mapping each row to a Map keeps all selected columns.
    @Test
    public void testList_AsMap() throws SQLException {
        insertWidget("m1", 3);
        insertWidget("m2", 4);

        final List<Map> rows = JdbcUtil.prepareQuery(ds, "SELECT name, qty FROM widget ORDER BY qty").list(Map.class);
        assertEquals(2, rows.size());
        assertNotNull(rows.get(0));
        assertEquals(2, rows.size());
    }

    // ---- JdbcUtil.iterateAllResultSets (real embedded DB, no mock) ----

    private static List<String> drainNames(final ResultSet rs) throws SQLException {
        final List<String> names = new ArrayList<>();
        while (rs.next()) {
            names.add(rs.getString(1));
        }
        return names;
    }

    // Multiple query results: H2's plain Statement does NOT expose multiple JDBC
    // ResultSets from a single execute (it runs only the first command of a
    // ';'-separated script), so a genuine multi-result-set Statement is produced with
    // an HSQLDB stored procedure declared with DYNAMIC RESULT SETS. This is still a
    // real embedded database, not a mock.
    @Test
    public void testIterateAllResultSets_MultipleResults() throws SQLException {
        final DataSource hsqlDs = JdbcUtil.createHikariDataSource("jdbc:hsqldb:mem:iter_multi", "SA", "");

        try (Connection conn = hsqlDs.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE t (id INT, name VARCHAR(20))");
                st.execute("INSERT INTO t VALUES (1,'a'),(2,'b'),(3,'c')");
                st.execute("CREATE PROCEDURE three_rs() READS SQL DATA DYNAMIC RESULT SETS 3 " + "BEGIN ATOMIC "
                        + "  DECLARE r1 CURSOR WITH RETURN FOR SELECT name FROM t WHERE id = 1; "
                        + "  DECLARE r2 CURSOR WITH RETURN FOR SELECT name FROM t WHERE id IN (2,3) ORDER BY id; "
                        + "  DECLARE r3 CURSOR WITH RETURN FOR SELECT name FROM t ORDER BY id; " + "  OPEN r1; OPEN r2; OPEN r3; " + "END");
            }

            try (CallableStatement cs = conn.prepareCall("{call three_rs()}")) {
                final boolean isFirstResultSet = cs.execute();

                final ObjIteratorEx<ResultSet> iter = JdbcUtil.iterateAllResultSets(cs, isFirstResultSet);

                assertTrue(iter.hasNext());
                assertEquals(List.of("a"), drainNames(iter.next()));

                assertTrue(iter.hasNext());
                assertEquals(List.of("b", "c"), drainNames(iter.next()));

                // next() without a preceding hasNext() still advances to the 3rd result.
                assertEquals(List.of("a", "b", "c"), drainNames(iter.next()));

                assertFalse(iter.hasNext());
                assertThrows(java.util.NoSuchElementException.class, iter::next);

                // close() is idempotent.
                iter.closeResource();
                iter.closeResource();
            }
        } finally {
            try (Connection c = hsqlDs.getConnection();
                 Statement st = c.createStatement()) {
                st.execute("DROP PROCEDURE three_rs");
                st.execute("DROP TABLE t");
            }
        }
    }

    // Single result set against real H2: exercises the first-result-set branch and the
    // end-of-results path (getUpdateCount() == -1 -> noMoreResult).
    @Test
    public void testIterateAllResultSets_SingleResult_H2() throws SQLException {
        insertWidget("only", 42);

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            final boolean isFirstResultSet = stmt.execute("SELECT name FROM widget WHERE qty = 42");

            final ObjIteratorEx<ResultSet> iter = JdbcUtil.iterateAllResultSets(stmt, isFirstResultSet);

            assertTrue(iter.hasNext());
            // hasNext is idempotent (does not advance).
            assertTrue(iter.hasNext());
            assertEquals(List.of("only"), drainNames(iter.next()));

            assertFalse(iter.hasNext());
            assertThrows(java.util.NoSuchElementException.class, iter::next);
            iter.closeResource();
        }
    }

    // ========================================================================================
    // Coverage additions (2026-06-29): prepare*/queryByPage/batch/stream/table helpers against H2.
    // ========================================================================================

    private long widgetCount(final String namePrefix) throws SQLException {
        return JdbcUtil.prepareQuery(ds, "SELECT COUNT(*) FROM widget WHERE name LIKE ?").setString(1, namePrefix + "%").queryForLong().orElse(0L);
    }

    // prepareQuery(DataSource|Connection, sql, BiFunction stmtCreator): caller-supplied statement factory.
    @Test
    public void testPrepareQuery_StmtCreator_ByDataSource() throws SQLException {
        insertWidget("sc-ds", 11);
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> creator = (conn, sql) -> conn.prepareStatement(sql);
        final OptionalInt qty = JdbcUtil.prepareQuery(ds, "SELECT qty FROM widget WHERE name = ?", creator).setString(1, "sc-ds").queryForInt();
        assertEquals(11, qty.getAsInt());
    }

    @Test
    public void testPrepareQuery_StmtCreator_ByConnection() throws SQLException {
        insertWidget("sc-conn", 12);
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> creator = (conn, sql) -> conn.prepareStatement(sql);
        try (Connection conn = ds.getConnection()) {
            final OptionalInt qty = JdbcUtil.prepareQuery(conn, "SELECT qty FROM widget WHERE name = ?", creator).setString(1, "sc-conn").queryForInt();
            assertEquals(12, qty.getAsInt());
        }
    }

    // prepareNamedQuery(DataSource|Connection, namedSql, BiFunction stmtCreator).
    @Test
    public void testPrepareNamedQuery_StmtCreator_ByDataSource() throws SQLException {
        insertWidget("nq-sc", 13);
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> creator = (conn, sql) -> conn.prepareStatement(sql);
        final OptionalInt qty = JdbcUtil.prepareNamedQuery(ds, "SELECT qty FROM widget WHERE name = :name", creator).setString("name", "nq-sc").queryForInt();
        assertEquals(13, qty.getAsInt());
    }

    @Test
    public void testPrepareNamedQuery_StmtCreator_ByConnection() throws SQLException {
        insertWidget("nq-sc2", 14);
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> creator = (conn, sql) -> conn.prepareStatement(sql);
        try (Connection conn = ds.getConnection()) {
            final OptionalInt qty = JdbcUtil.prepareNamedQuery(conn, "SELECT qty FROM widget WHERE name = :name", creator)
                    .setString("name", "nq-sc2")
                    .queryForInt();
            assertEquals(14, qty.getAsInt());
        }
    }

    // prepareNamedQuery with returnColumnIndexes -> generated key on insert.
    @Test
    public void testPrepareNamedQuery_ReturnColumnIndexes() throws SQLException {
        final Optional<Long> key = JdbcUtil.prepareNamedQuery(ds, "INSERT INTO widget (name, qty) VALUES (:name, :qty)", new int[] { 1 })
                .setString("name", "rci")
                .setInt("qty", 3)
                .insert();
        assertTrue(key.isPresent());

        try (Connection conn = ds.getConnection()) {
            final Optional<Long> key2 = JdbcUtil.prepareNamedQuery(conn, "INSERT INTO widget (name, qty) VALUES (:name, :qty)", new int[] { 1 })
                    .setString("name", "rci2")
                    .setInt("qty", 4)
                    .insert();
            assertTrue(key2.isPresent());
        }
    }

    // prepareNamedQuery from a pre-parsed ParsedSql (autoGeneratedKeys boolean, returnColumnIndexes, and BiFunction).
    @Test
    public void testPrepareNamedQuery_ParsedSql_Variants() throws SQLException {
        final ParsedSql insertSql = ParsedSql.parse("INSERT INTO widget (name, qty) VALUES (:name, :qty)");

        // autoGeneratedKeys=true (DataSource)
        final Optional<Long> k1 = JdbcUtil.prepareNamedQuery(ds, insertSql, true).setString("name", "ps1").setInt("qty", 1).insert();
        assertTrue(k1.isPresent());

        // returnColumnIndexes (DataSource)
        final Optional<Long> k2 = JdbcUtil.prepareNamedQuery(ds, insertSql, new int[] { 1 }).setString("name", "ps2").setInt("qty", 2).insert();
        assertTrue(k2.isPresent());

        // BiFunction stmtCreator (DataSource) on a SELECT
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> creator = (conn, sql) -> conn.prepareStatement(sql);
        final ParsedSql selectSql = ParsedSql.parse("SELECT qty FROM widget WHERE name = :name");
        final OptionalInt q = JdbcUtil.prepareNamedQuery(ds, selectSql, creator).setString("name", "ps1").queryForInt();
        assertEquals(1, q.getAsInt());

        // Connection + ParsedSql variants
        try (Connection conn = ds.getConnection()) {
            final Optional<Long> k3 = JdbcUtil.prepareNamedQuery(conn, insertSql, true).setString("name", "ps3").setInt("qty", 3).insert();
            assertTrue(k3.isPresent());

            final Optional<Long> k4 = JdbcUtil.prepareNamedQuery(conn, insertSql, new int[] { 1 }).setString("name", "ps4").setInt("qty", 4).insert();
            assertTrue(k4.isPresent());

            final OptionalInt q2 = JdbcUtil.prepareNamedQuery(conn, selectSql, creator).setString("name", "ps3").queryForInt();
            assertEquals(3, q2.getAsInt());
        }
    }

    // prepareCallableQuery(DataSource|Connection, sql, BiFunction stmtCreator).
    @Test
    public void testPrepareCallableQuery_StmtCreator() throws SQLException {
        insertWidget("cq", 21);
        final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> creator = (conn, sql) -> conn.prepareCall(sql);

        final OptionalInt q = JdbcUtil.prepareCallableQuery(ds, "SELECT qty FROM widget WHERE name = ?", creator).setString(1, "cq").queryForInt();
        assertEquals(21, q.getAsInt());

        try (Connection conn = ds.getConnection()) {
            final OptionalInt q2 = JdbcUtil.prepareCallableQuery(conn, "SELECT qty FROM widget WHERE name = ?", creator).setString(1, "cq").queryForInt();
            assertEquals(21, q2.getAsInt());
        }
    }

    // prepareCall(Connection, sql, params): positional params are applied to the CallableStatement.
    @Test
    public void testPrepareCall_WithParams() throws SQLException {
        insertWidget("pc", 22);
        try (Connection conn = ds.getConnection();
             CallableStatement cs = JdbcUtil.prepareCall(conn, "SELECT qty FROM widget WHERE name = ?", "pc");
             ResultSet rs = cs.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(22, rs.getInt(1));
        }
    }

    // prepareBatchStmt(Connection, sql, parametersList): each row is added as a batch entry.
    @Test
    public void testPrepareBatchStmt() throws SQLException {
        final List<Object[]> rows = List.of(new Object[] { "bs1", 1 }, new Object[] { "bs2", 2 });
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = JdbcUtil.prepareBatchStmt(conn, "INSERT INTO widget (name, qty) VALUES (?, ?)", rows)) {
            final int[] counts = stmt.executeBatch();
            assertEquals(2, counts.length);
        }
        assertEquals(2L, widgetCount("bs"));
    }

    // prepareBatchCall(Connection, sql, parametersList): callable-statement batch variant.
    @Test
    public void testPrepareBatchCall() throws SQLException {
        final List<Object[]> rows = List.of(new Object[] { "bc1", 1 }, new Object[] { "bc2", 2 });
        try (Connection conn = ds.getConnection();
             CallableStatement cs = JdbcUtil.prepareBatchCall(conn, "INSERT INTO widget (name, qty) VALUES (?, ?)", rows)) {
            cs.executeBatch();
        }
        assertEquals(2L, widgetCount("bc"));
    }

    // queryByPage with a ResultExtractor: keyset pagination over the id cursor (DataSource).
    @Test
    public void testQueryByPage_ResultExtractor_ByDataSource() throws SQLException {
        for (int i = 1; i <= 5; i++) {
            insertWidget("pg", i);
        }
        final Jdbc.ResultExtractor<List<Widget>> extractor = Jdbc.ResultExtractor.toList(Widget.class);
        final List<List<Widget>> pages = JdbcUtil
                .queryByPage(ds, "SELECT id, name, qty FROM widget WHERE id > ? ORDER BY id LIMIT 2", 2,
                        (q, prev) -> q.setLong(1, prev == null || prev.isEmpty() ? 0L : prev.get(prev.size() - 1).getId()), extractor)
                .toList();
        final int total = pages.stream().mapToInt(List::size).sum();
        assertEquals(5, total);
    }

    // queryByPage with a BiResultExtractor (DataSource).
    @Test
    public void testQueryByPage_BiResultExtractor_ByDataSource() throws SQLException {
        for (int i = 1; i <= 3; i++) {
            insertWidget("bp", i);
        }
        final Jdbc.BiResultExtractor<List<Long>> toIds = (rs, labels) -> {
            final List<Long> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
            return ids;
        };
        final List<List<Long>> pages = JdbcUtil
                .queryByPage(ds, "SELECT id FROM widget WHERE id > ? ORDER BY id LIMIT 2", 2,
                        (q, prev) -> q.setLong(1, prev == null || prev.isEmpty() ? 0L : prev.get(prev.size() - 1)), toIds)
                .toList();
        assertEquals(3, pages.stream().mapToInt(List::size).sum());
    }

    // queryByPage over a Connection with a ResultExtractor.
    @Test
    public void testQueryByPage_ResultExtractor_ByConnection() throws SQLException {
        for (int i = 1; i <= 3; i++) {
            insertWidget("cp", i);
        }
        final Jdbc.ResultExtractor<List<Long>> extractor = rs -> {
            final List<Long> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
            return ids;
        };
        try (Connection conn = ds.getConnection()) {
            final List<List<Long>> pages = JdbcUtil
                    .queryByPage(conn, "SELECT id FROM widget WHERE id > ? ORDER BY id LIMIT 2", 2,
                            (q, prev) -> q.setLong(1, prev == null || prev.isEmpty() ? 0L : prev.get(prev.size() - 1)), extractor)
                    .toList();
            assertEquals(3, pages.stream().mapToInt(List::size).sum());
        }
    }

    // queryByPage over a Connection with a BiResultExtractor.
    @Test
    public void testQueryByPage_BiResultExtractor_ByConnection() throws SQLException {
        for (int i = 1; i <= 3; i++) {
            insertWidget("cb", i);
        }
        final Jdbc.BiResultExtractor<List<Long>> toIds = (rs, labels) -> {
            final List<Long> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
            return ids;
        };
        try (Connection conn = ds.getConnection()) {
            final List<List<Long>> pages = JdbcUtil
                    .queryByPage(conn, "SELECT id FROM widget WHERE id > ? ORDER BY id LIMIT 2", 2,
                            (q, prev) -> q.setLong(1, prev == null || prev.isEmpty() ? 0L : prev.get(prev.size() - 1)), toIds)
                    .toList();
            assertEquals(3, pages.stream().mapToInt(List::size).sum());
        }
    }

    // queryByPage default overload: each page is extracted as a Dataset (TO_DATASET).
    @Test
    public void testQueryByPage_DatasetDefault() throws SQLException {
        for (int i = 1; i <= 4; i++) {
            insertWidget("dp", i);
        }
        // column 0 is "id" (H2 returns labels upper-cased, so address it by index).
        final List<Dataset> pages = JdbcUtil.queryByPage(ds, "SELECT id, name FROM widget WHERE id > ? ORDER BY id LIMIT 2", 2, (q, prev) -> {
            long lastId = 0L;
            if (prev != null && !prev.isEmpty()) {
                lastId = ((Number) prev.get(prev.size() - 1, 0)).longValue();
            }
            q.setLong(1, lastId);
        }).toList();
        final int total = pages.stream().mapToInt(Dataset::size).sum();
        assertEquals(4, total);
    }

    // executeBatchUpdate / executeLargeBatchUpdate (DataSource + Connection, with batchSize).
    @Test
    public void testExecuteBatchUpdate_ByDataSource() throws SQLException {
        final List<Object[]> rows = List.of(new Object[] { "eb1", 1 }, new Object[] { "eb2", 2 }, new Object[] { "eb3", 3 });
        final int affected = JdbcUtil.executeBatchUpdate(ds, "INSERT INTO widget (name, qty) VALUES (?, ?)", rows, 2);
        assertEquals(3, affected);
        assertEquals(3L, widgetCount("eb"));
    }

    @Test
    public void testExecuteBatchUpdate_ByConnection() throws SQLException {
        final List<Object[]> rows = List.of(new Object[] { "ec1", 1 }, new Object[] { "ec2", 2 });
        try (Connection conn = ds.getConnection()) {
            final int affected = JdbcUtil.executeBatchUpdate(conn, "INSERT INTO widget (name, qty) VALUES (?, ?)", rows, 10);
            assertEquals(2, affected);
        }
        assertEquals(2L, widgetCount("ec"));
    }

    @Test
    public void testExecuteLargeBatchUpdate_ByDataSource() throws SQLException {
        final List<Object[]> rows = List.of(new Object[] { "lb1", 1 }, new Object[] { "lb2", 2 });
        final long affected = JdbcUtil.executeLargeBatchUpdate(ds, "INSERT INTO widget (name, qty) VALUES (?, ?)", rows, 1);
        assertEquals(2L, affected);
        assertEquals(2L, widgetCount("lb"));
    }

    // stream(ResultSet, RowFilter, RowMapper): filtered + mapped row stream.
    @Test
    public void testStream_RowFilterRowMapper() throws SQLException {
        for (int i = 1; i <= 4; i++) {
            insertWidget("st", i);
        }
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, qty FROM widget WHERE name LIKE 'st%' ORDER BY id")) {
            final Jdbc.RowFilter filter = r -> r.getInt("qty") % 2 == 0;
            final Jdbc.RowMapper<Integer> mapper = r -> r.getInt("qty");
            final List<Integer> evens = JdbcUtil.stream(rs, filter, mapper).toList();
            assertEquals(2, evens.size());
            assertTrue(evens.contains(2));
            assertTrue(evens.contains(4));
        }
    }

    // stream(ResultSet, BiRowFilter, BiRowMapper): the column-label-aware overload.
    @Test
    public void testStream_BiRowFilterBiRowMapper() throws SQLException {
        for (int i = 1; i <= 4; i++) {
            insertWidget("bt", i);
        }
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, qty FROM widget WHERE name LIKE 'bt%' ORDER BY id")) {
            final Jdbc.BiRowFilter filter = (r, labels) -> r.getInt("qty") > 2;
            final Jdbc.BiRowMapper<Integer> mapper = (r, labels) -> r.getInt("qty");
            final List<Integer> big = JdbcUtil.stream(rs, filter, mapper).toList();
            assertEquals(2, big.size());
        }
    }

    // streamAllResultSets(Statement, BiResultExtractor): single executed result set on H2.
    @Test
    public void testStreamAllResultSets_BiResultExtractor() throws SQLException {
        insertWidget("sar", 1);
        insertWidget("sar", 2);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT name FROM widget WHERE name = 'sar' ORDER BY id");
            final Jdbc.BiResultExtractor<List<String>> ext = (rs, labels) -> {
                final List<String> ns = new ArrayList<>();
                while (rs.next()) {
                    ns.add(rs.getString(1));
                }
                return ns;
            };
            final List<List<String>> all = JdbcUtil.streamAllResultSets(stmt, ext).toList();
            assertEquals(1, all.size());
            assertEquals(2, all.get(0).size());
        }
    }

    // getAllColumnValues(ResultSet, int): drive the String/Number/Boolean/Timestamp, Blob, Clob,
    // Date, "other object" (Time) and null branches with a multi-type column probe table.
    @Test
    public void testGetAllColumnValues_AllTypeBranches() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS colval (id INT PRIMARY KEY, s VARCHAR(50), n BIGINT, b BOOLEAN, "
                    + "ts TIMESTAMP, d DATE, tm TIME, bl BLOB, cl CLOB)");
            st.execute("DELETE FROM colval");
        }
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO colval (id, s, n, b, ts, d, tm, bl, cl) VALUES (?,?,?,?,?,?,?,?,?)")) {
            // two fully-populated rows
            for (int i = 1; i <= 2; i++) {
                ps.setInt(1, i);
                ps.setString(2, "s" + i);
                ps.setLong(3, 10L * i);
                ps.setBoolean(4, i % 2 == 0);
                ps.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
                ps.setDate(6, Date.valueOf("2026-06-2" + i));
                ps.setTime(7, java.sql.Time.valueOf("08:0" + i + ":00"));
                ps.setBytes(8, new byte[] { (byte) i, (byte) (i + 1) });
                ps.setString(9, "clob" + i);
                ps.addBatch();
            }
            // a third all-null row to drive the null / null-inner branches
            ps.setInt(1, 3);
            ps.setNull(2, java.sql.Types.VARCHAR);
            ps.setNull(3, java.sql.Types.BIGINT);
            ps.setNull(4, java.sql.Types.BOOLEAN);
            ps.setNull(5, java.sql.Types.TIMESTAMP);
            ps.setNull(6, java.sql.Types.DATE);
            ps.setNull(7, java.sql.Types.TIME);
            ps.setNull(8, java.sql.Types.BLOB);
            ps.setNull(9, java.sql.Types.CLOB);
            ps.addBatch();
            ps.executeBatch();
        }

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            // String column (+ inner getObject loop + trailing null)
            try (ResultSet rs = st.executeQuery("SELECT s FROM colval ORDER BY id")) {
                final List<Object> vals = JdbcUtil.getAllColumnValues(rs, 1);
                assertEquals(3, vals.size());
                assertEquals("s1", vals.get(0));
            }
            // null-first branch (ORDER BY id DESC puts the all-null row first)
            try (ResultSet rs = st.executeQuery("SELECT s FROM colval ORDER BY id DESC")) {
                final List<Object> vals = JdbcUtil.getAllColumnValues(rs, 1);
                assertEquals(3, vals.size());
                assertNull(vals.get(0));
            }
            // Number column
            try (ResultSet rs = st.executeQuery("SELECT n FROM colval ORDER BY id")) {
                assertEquals(3, JdbcUtil.getAllColumnValues(rs, 1).size());
            }
            // Boolean column
            try (ResultSet rs = st.executeQuery("SELECT b FROM colval ORDER BY id")) {
                assertEquals(3, JdbcUtil.getAllColumnValues(rs, 1).size());
            }
            // Timestamp column
            try (ResultSet rs = st.executeQuery("SELECT ts FROM colval ORDER BY id")) {
                assertEquals(3, JdbcUtil.getAllColumnValues(rs, 1).size());
            }
            // Date column (java.sql.Date branch)
            try (ResultSet rs = st.executeQuery("SELECT d FROM colval ORDER BY id")) {
                assertEquals(3, JdbcUtil.getAllColumnValues(rs, 1).size());
            }
            // Time column -> the generic "other object" branch
            try (ResultSet rs = st.executeQuery("SELECT tm FROM colval ORDER BY id")) {
                assertEquals(3, JdbcUtil.getAllColumnValues(rs, 1).size());
            }
            // Blob column (+ null-inner branch)
            try (ResultSet rs = st.executeQuery("SELECT bl FROM colval ORDER BY id")) {
                final List<Object> vals = JdbcUtil.getAllColumnValues(rs, 1);
                assertEquals(3, vals.size());
                assertTrue(vals.get(0) instanceof byte[]);
                assertNull(vals.get(2));
            }
            // Clob column (+ null-inner branch)
            try (ResultSet rs = st.executeQuery("SELECT cl FROM colval ORDER BY id")) {
                final List<Object> vals = JdbcUtil.getAllColumnValues(rs, 1);
                assertEquals(3, vals.size());
                assertEquals("clob1", vals.get(0));
                assertNull(vals.get(2));
            }
        } finally {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS colval");
            }
        }
    }

    // tableExists / createTableIfNotExists / dropTableIfExists (Connection overloads).
    @Test
    public void testTableExists_CreateAndDrop() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            assertTrue(JdbcUtil.tableExists(conn, "widget"));
            assertFalse(JdbcUtil.tableExists(conn, "no_such_table_xyz"));

            final boolean created = JdbcUtil.createTableIfNotExists(conn, "TMP_COV_TBL", "CREATE TABLE TMP_COV_TBL (id INT PRIMARY KEY, label VARCHAR(20))");
            assertTrue(created);
            assertTrue(JdbcUtil.tableExists(conn, "TMP_COV_TBL"));

            // already exists -> createTableIfNotExists is a no-op returning false
            assertFalse(JdbcUtil.createTableIfNotExists(conn, "TMP_COV_TBL", "CREATE TABLE TMP_COV_TBL (id INT PRIMARY KEY, label VARCHAR(20))"));

            final boolean dropped = JdbcUtil.dropTableIfExists(conn, "TMP_COV_TBL");
            assertTrue(dropped);
            assertFalse(JdbcUtil.tableExists(conn, "TMP_COV_TBL"));
        }
    }

    // getDBProductInfo(Connection) against a real H2 connection.
    @Test
    public void testGetDBProductInfo_H2Connection() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            final ProductInfo info = JdbcUtil.getDBProductInfo(conn);
            assertNotNull(info);
            assertNotNull(info.name());
            assertTrue(info.name().toLowerCase().contains("h2"));
        }
    }

    // callOutsideTransaction(DataSource, Function): runs the command with a fresh (non-transactional) connection.
    @Test
    public void testCallOutsideTransaction_Function() throws Exception {
        insertWidget("cot", 99);
        final int qty = JdbcUtil.callOutsideTransaction(ds,
                (final DataSource dataSource) -> JdbcUtil.prepareQuery(dataSource, "SELECT qty FROM widget WHERE name = ?")
                        .setString(1, "cot")
                        .queryForInt()
                        .orElse(-1));
        assertEquals(99, qty);
    }

    // ========================================================================================
    // Coverage additions (2026-06-29, follow-up): setParameters(ParsedSql,...) + getOutParameters.
    // ========================================================================================

    // setParameters binds named parameters from a Map's entries.
    @Test
    public void testSetParameters_Named_Map() throws SQLException {
        final ParsedSql psql = ParsedSql.parse("INSERT INTO widget (name, qty) VALUES (:name, :qty)");
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(psql.parameterizedSql())) {
            JdbcUtil.setParameters(psql, ps, new Object[] { Map.of("name", "spm", "qty", 5) });
            assertEquals(1, ps.executeUpdate());
        }
        assertEquals(1L, widgetCount("spm"));
    }

    // setParameters binds named parameters from a bean's properties.
    @Test
    public void testSetParameters_Named_Bean() throws SQLException {
        final ParsedSql psql = ParsedSql.parse("INSERT INTO widget (name, qty) VALUES (:name, :qty)");
        final Widget w = new Widget();
        w.setName("spb");
        w.setQty(6);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(psql.parameterizedSql())) {
            JdbcUtil.setParameters(psql, ps, new Object[] { w });
            assertEquals(1, ps.executeUpdate());
        }
        assertEquals(1L, widgetCount("spb"));
    }

    // setParameters binds named parameters from an EntityId.
    @Test
    public void testSetParameters_Named_EntityId() throws SQLException {
        final ParsedSql psql = ParsedSql.parse("INSERT INTO widget (name, qty) VALUES (:name, :qty)");
        final com.landawn.abacus.util.EntityId eid = com.landawn.abacus.util.EntityId.of("name", "spe", "qty", 7);
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(psql.parameterizedSql())) {
            JdbcUtil.setParameters(psql, ps, new Object[] { eid });
            assertEquals(1, ps.executeUpdate());
        }
        assertEquals(1L, widgetCount("spe"));
    }

    // setParameters with positional '?' params: direct, single-array unwrap, single-collection unwrap.
    @Test
    public void testSetParameters_Positional_Variants() throws SQLException {
        final ParsedSql psql = ParsedSql.parse("INSERT INTO widget (name, qty) VALUES (?, ?)");
        try (Connection conn = ds.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(psql.parameterizedSql())) {
                JdbcUtil.setParameters(psql, ps, new Object[] { "pos1", 1 });
                assertEquals(1, ps.executeUpdate());
            }
            // a single Object[] element is unwrapped into the parameter values
            try (PreparedStatement ps = conn.prepareStatement(psql.parameterizedSql())) {
                JdbcUtil.setParameters(psql, ps, new Object[] { new Object[] { "pos2", 2 } });
                assertEquals(1, ps.executeUpdate());
            }
            // a single Collection element is unwrapped into the parameter values
            try (PreparedStatement ps = conn.prepareStatement(psql.parameterizedSql())) {
                JdbcUtil.setParameters(psql, ps, new Object[] { List.of("pos3", 3) });
                assertEquals(1, ps.executeUpdate());
            }
        }
        assertEquals(3L, widgetCount("pos"));
    }

    // setParameters on a no-parameter SQL returns immediately (no-op).
    @Test
    public void testSetParameters_NoParams_NoOp() throws SQLException {
        final ParsedSql psql = ParsedSql.parse("DELETE FROM widget WHERE 1 = 2");
        assertEquals(0, psql.parameterCount());
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(psql.parameterizedSql())) {
            assertDoesNotThrow(() -> JdbcUtil.setParameters(psql, ps, new Object[0]));
        }
    }

    // setParameters with empty params for a parameterized SQL throws IllegalArgumentException.
    @Test
    public void testSetParameters_EmptyParams_Throws() throws SQLException {
        final ParsedSql psql = ParsedSql.parse("SELECT * FROM widget WHERE name = ?");
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(psql.parameterizedSql())) {
            assertThrows(IllegalArgumentException.class, () -> JdbcUtil.setParameters(psql, ps, new Object[0]));
        }
    }

    // setParameters with a bean missing a named property throws IllegalArgumentException.
    @Test
    public void testSetParameters_Named_Bean_MissingProp_Throws() throws SQLException {
        final ParsedSql psql = ParsedSql.parse("SELECT * FROM widget WHERE name = :bogus");
        final Widget w = new Widget();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(psql.parameterizedSql())) {
            assertThrows(IllegalArgumentException.class, () -> JdbcUtil.setParameters(psql, ps, new Object[] { w }));
        }
    }

    // setParameters with a Map missing a named key throws IllegalArgumentException.
    @Test
    public void testSetParameters_Named_Map_MissingKey_Throws() throws SQLException {
        final ParsedSql psql = ParsedSql.parse("INSERT INTO widget (name, qty) VALUES (:name, :qty)");
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(psql.parameterizedSql())) {
            assertThrows(IllegalArgumentException.class, () -> JdbcUtil.setParameters(psql, ps, new Object[] { Map.of("name", "x") }));
        }
    }

    // getOutParameters with no out-params returns an empty result.
    @Test
    public void testGetOutParameters_Empty() throws SQLException {
        try (Connection conn = ds.getConnection();
             CallableStatement cs = conn.prepareCall("SELECT 1")) {
            final Jdbc.OutParamResult res = JdbcUtil.getOutParameters(cs, java.util.Collections.emptyList());
            assertEquals(0, res.getOutParamValues().size());
            assertEquals(0, res.getOutParams().size());
        }
    }

    // getOutParameters reads a registered OUT parameter by index from an HSQLDB procedure.
    @Test
    public void testGetOutParameters_IndexedOutParam() throws SQLException {
        final DataSource hsqlDs = JdbcUtil.createHikariDataSource("jdbc:hsqldb:mem:outparam_it", "SA", "");
        try (Connection conn = hsqlDs.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE PROCEDURE add_one(IN a INT, OUT b INT) BEGIN ATOMIC SET b = a + 1; END");
            }
            try (CallableStatement cs = conn.prepareCall("{call add_one(?, ?)}")) {
                cs.setInt(1, 41);
                cs.registerOutParameter(2, java.sql.Types.INTEGER);
                cs.execute();
                final Jdbc.OutParamResult res = JdbcUtil.getOutParameters(cs, List.of(Jdbc.OutParam.of(2, java.sql.Types.INTEGER)));
                assertEquals(1, res.getOutParamValues().size());
                assertEquals(42, ((Number) res.getOutParamValue(2)).intValue());
            }
        } finally {
            try (Connection c = hsqlDs.getConnection();
                 Statement st = c.createStatement()) {
                st.execute("DROP PROCEDURE add_one");
            }
        }
    }

    // ResultExtractor.toMergedList(Class, Collection) must merge rows by the supplied composite key,
    // not by the bean's default @Id. Three rows with distinct ids but two distinct names must collapse
    // to two entities when merging by "name". (Regression: the collection was previously passed to the
    // Dataset selectPropNames slot, so merging fell back to the default id and produced three entities.)
    @Test
    public void testResultExtractorToMergedList_MergesByGivenCompositeKey() throws SQLException {
        insertWidget("Alice", 1);
        insertWidget("Alice", 2);
        insertWidget("Bob", 3);

        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT id, name, qty FROM widget ORDER BY id");
             ResultSet rs = stmt.executeQuery()) {

            final List<Widget> merged = Jdbc.ResultExtractor.toMergedList(Widget.class, List.of("name")).apply(rs);

            assertEquals(2, merged.size());
            assertEquals("Alice", merged.get(0).getName());
            assertEquals("Bob", merged.get(1).getName());
        }
    }

    // The single-String merge-key overload merges by the given property in the same way.
    @Test
    public void testResultExtractorToMergedList_MergesBySingleGivenKey() throws SQLException {
        insertWidget("Alice", 1);
        insertWidget("Alice", 2);
        insertWidget("Bob", 3);

        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT id, name, qty FROM widget ORDER BY id");
             ResultSet rs = stmt.executeQuery()) {

            final List<Widget> merged = Jdbc.ResultExtractor.toMergedList(Widget.class, "name").apply(rs);

            assertEquals(2, merged.size());
        }
    }
}
