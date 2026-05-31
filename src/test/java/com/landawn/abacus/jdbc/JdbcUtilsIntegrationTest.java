package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.landawn.abacus.TestBase;

/**
 * End-to-end integration coverage for {@link JdbcUtils} table-to-table {@code copy} and CSV
 * {@code importCsv}/{@code exportCsv} bodies, backed by a real in-memory H2 database. The existing
 * {@code JdbcUtilsTest} mocks the {@code DataSource}/{@code Connection}, so the SQL-generating
 * helpers ({@code generateSelectSql}/{@code generateInsertSql}), the streaming copy loop and the
 * file-based CSV round-trip never actually execute. This test drives them against live tables.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class JdbcUtilsIntegrationTest extends TestBase {

    private DataSource ds;

    @BeforeAll
    public void initDb() throws SQLException {
        ds = JdbcUtil.createHikariDataSource("jdbc:h2:mem:jdbcutils_it;DB_CLOSE_DELAY=-1", "sa", "");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS copy_src (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DOUBLE)");
            st.execute("CREATE TABLE IF NOT EXISTS copy_tgt (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DOUBLE)");
            st.execute("CREATE TABLE IF NOT EXISTS csv_tgt (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DOUBLE)");
        }
    }

    @AfterAll
    public void dropDb() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS copy_src");
            st.execute("DROP TABLE IF EXISTS copy_tgt");
            st.execute("DROP TABLE IF EXISTS csv_tgt");
        }
    }

    @BeforeEach
    public void seed() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE copy_src");
            st.execute("TRUNCATE TABLE copy_tgt");
            st.execute("TRUNCATE TABLE csv_tgt");
            st.execute("INSERT INTO copy_src (id, name, amount) VALUES (1, 'Alice', 10.5), (2, 'Bob', 20.0), (3, 'Cara', 30.25)");
        }
    }

    private long count(final String table) throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // copy(sourceConn, targetConn, sourceTable, targetTable) — schema-derived (metadata) copy path.
    @Test
    public void testCopy_TableToTable_AllColumns() throws SQLException {
        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection()) {
            final long copied = JdbcUtils.copy(src, tgt, "copy_src", "copy_tgt");
            assertEquals(3, copied);
        }
        assertEquals(3, count("copy_tgt"));
    }

    // copy(..., selectColumnNames = null) drives the empty-columns branch of generateSelectSql/generateInsertSql.
    @Test
    public void testCopy_TableToTable_NullColumns_GeneratesFullSql() throws SQLException {
        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection()) {
            final long copied = JdbcUtils.copy(src, tgt, "copy_src", "copy_tgt", (Collection<String>) null);
            assertEquals(3, copied);
        }
        assertEquals(3, count("copy_tgt"));
    }

    // copy(..., explicit columns) drives the non-empty column loops of generateSelectSql/generateInsertSql.
    @Test
    public void testCopy_TableToTable_ExplicitColumns() throws SQLException {
        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection()) {
            final long copied = JdbcUtils.copy(src, tgt, "copy_src", "copy_tgt", List.of("id", "name", "amount"));
            assertEquals(3, copied);
        }
        assertEquals(3, count("copy_tgt"));
    }

    // copy(sourceConn, selectSql, targetConn, insertSql, stmtSetter) — the custom-stmtSetter streaming copy loop.
    @Test
    public void testCopy_SelectInsertSql_WithStmtSetter() throws SQLException {
        final String selectSql = "SELECT id, name, amount FROM copy_src";
        final String insertSql = "INSERT INTO copy_tgt (id, name, amount) VALUES (?, ?, ?)";

        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection()) {
            final long copied = JdbcUtils.copy(src, selectSql, tgt, insertSql, (pq, rs) -> {
                pq.setLong(1, rs.getLong(1));
                pq.setString(2, rs.getString(2));
                pq.setDouble(3, rs.getDouble(3));
            });
            assertEquals(3, copied);
        }
        assertEquals(3, count("copy_tgt"));
    }

    // exportCsv(rs, selectColumnNames, File) then importCsv(File, conn, insertSql, batchSize, interval, stmtSetter):
    // exercises both file-based CSV bodies as a round-trip.
    @Test
    public void testExportThenImportCsv_RoundTrip() throws SQLException, java.io.IOException {
        final File csv = File.createTempFile("jdbcutils_it_", ".csv");
        csv.deleteOnExit();

        try (Connection conn = ds.getConnection();
             // Quoted aliases keep the result-set column labels lower-case so they match selectColumnNames
             // (H2 upper-cases unquoted identifiers).
             PreparedStatement st = conn.prepareStatement("SELECT id AS \"id\", name AS \"name\", amount AS \"amount\" FROM copy_src ORDER BY id");
             ResultSet rs = st.executeQuery()) {
            final long exported = JdbcUtils.exportCsv(rs, List.of("id", "name", "amount"), csv);
            assertEquals(3, exported);
        }

        assertTrue(csv.length() > 0L);

        try (Connection conn = ds.getConnection()) {
            final long imported = JdbcUtils.importCsv(csv, conn, "INSERT INTO csv_tgt (id, name, amount) VALUES (?, ?, ?)", 100, 0L, (pq, row) -> {
                pq.setLong(1, Long.parseLong(row[0]));
                pq.setString(2, row[1]);
                pq.setDouble(3, Double.parseDouble(row[2]));
            });
            assertEquals(3, imported);
        }

        assertEquals(3, count("csv_tgt"));

        // The round-tripped values survived: id=1 still maps to name "Alice".
        try (Connection conn = ds.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT name FROM csv_tgt WHERE id = 1");
             ResultSet rs = st.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("Alice", rs.getString(1));
        }
    }

    // exportCsv(rs, selectColumnNames, File) onto a fresh (non-existent) path drives the createNewFile branch.
    @Test
    public void testExportCsv_CreatesNewFile() throws SQLException, java.io.IOException {
        final File dir = java.nio.file.Files.createTempDirectory("jdbcutils_it_").toFile();
        final File csv = new File(dir, "fresh_export.csv");
        csv.deleteOnExit();
        dir.deleteOnExit();

        try (Connection conn = ds.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT id AS \"id\", name AS \"name\", amount AS \"amount\" FROM copy_src");
             ResultSet rs = st.executeQuery()) {
            final long exported = JdbcUtils.exportCsv(rs, List.of("id", "name", "amount"), csv);
            assertEquals(3, exported);
        }

        assertTrue(csv.exists());
        assertTrue(csv.length() > 0L);
    }
}
