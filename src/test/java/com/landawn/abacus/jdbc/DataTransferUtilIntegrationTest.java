package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collection;
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
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Dataset;

/**
 * End-to-end integration coverage for {@link DataTransferUtil} table-to-table {@code copy} and CSV
 * {@code importCsv}/{@code exportCsv} bodies, backed by a real in-memory H2 database. The existing
 * {@code DataTransferUtilTest} mocks the {@code DataSource}/{@code Connection}, so the SQL-generating
 * helpers ({@code generateSelectSql}/{@code generateInsertSql}), the streaming copy loop and the
 * file-based CSV round-trip never actually execute. This test drives them against live tables.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class DataTransferUtilIntegrationTest extends TestBase {

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
            final long copied = DataTransferUtil.copy(src, tgt, "copy_src", "copy_tgt");
            assertEquals(3, copied);
        }
        assertEquals(3, count("copy_tgt"));
    }

    // copy(..., selectColumnNames = null) drives the empty-columns branch of generateSelectSql/generateInsertSql.
    @Test
    public void testCopy_TableToTable_NullColumns_GeneratesFullSql() throws SQLException {
        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection()) {
            final long copied = DataTransferUtil.copy(src, tgt, "copy_src", "copy_tgt", (Collection<String>) null);
            assertEquals(3, copied);
        }
        assertEquals(3, count("copy_tgt"));
    }

    // copy(..., explicit columns) drives the non-empty column loops of generateSelectSql/generateInsertSql.
    @Test
    public void testCopy_TableToTable_ExplicitColumns() throws SQLException {
        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection()) {
            final long copied = DataTransferUtil.copy(src, tgt, "copy_src", "copy_tgt", List.of("id", "name", "amount"));
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
            final long copied = DataTransferUtil.copy(src, selectSql, tgt, insertSql, (pq, rs) -> {
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
            final long exported = DataTransferUtil.exportCsv(rs, List.of("id", "name", "amount"), csv);
            assertEquals(3, exported);
        }

        assertTrue(csv.length() > 0L);

        try (Connection conn = ds.getConnection()) {
            final long imported = DataTransferUtil.importCsv(csv, conn, "INSERT INTO csv_tgt (id, name, amount) VALUES (?, ?, ?)", 100, 0L, (pq, row) -> {
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
            final long exported = DataTransferUtil.exportCsv(rs, List.of("id", "name", "amount"), csv);
            assertEquals(3, exported);
        }

        assertTrue(csv.exists());
        assertTrue(csv.length() > 0L);
    }

    private static final String CSV_INSERT_SQL = "INSERT INTO csv_tgt (id, name, amount) VALUES (?, ?, ?)";

    private Dataset threeRowDataset() {
        return Dataset.rows(List.of("id", "name", "amount"), new Object[][] { { 1L, "Alice", 10.5 }, { 2L, "Bob", 20.0 }, { 3L, "Cara", 30.25 } });
    }

    private String nameOf(final long id) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT name FROM csv_tgt WHERE id = ?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // importFrom(dataset).to(dataSource, insertSql) — default all-columns path.
    @Test
    public void testImportFrom_AllColumns_IntoDataSource() throws SQLException {
        final int imported = DataTransferUtil.importFrom(threeRowDataset()).to(ds, CSV_INSERT_SQL);

        assertEquals(3, imported);
        assertEquals(3, count("csv_tgt"));
        assertEquals("Alice", nameOf(1));
    }

    // importFrom(dataset).columns(..).filter(..).batchSize(..).to(conn, insertSql)
    @Test
    public void testImportFrom_SelectColumns_Filter_BatchSize() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            final int imported = DataTransferUtil.importFrom(threeRowDataset())
                    .columns(List.of("id", "name", "amount"))
                    .filter(row -> ((Double) row[2]) >= 20.0) // drops Alice (10.5)
                    .batchSize(1)
                    .to(conn, CSV_INSERT_SQL);

            assertEquals(2, imported);
        }

        assertEquals(2, count("csv_tgt"));
    }

    // importFrom(dataset).parameterSetter(..).to(stmt) — custom parameter binding, terminal PreparedStatement.
    @Test
    public void testImportFrom_StmtSetter_IntoStatement() throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(CSV_INSERT_SQL)) {
            final int imported = DataTransferUtil.importFrom(threeRowDataset()).parameterSetter((pq, row) -> {
                pq.setLong(1, (Long) row[0]);
                pq.setString(2, ((String) row[1]).toUpperCase());
                pq.setDouble(3, (Double) row[2]);
            }).to(stmt);

            assertEquals(3, imported);
        }

        assertEquals(3, count("csv_tgt"));
        assertEquals("ALICE", nameOf(1));
    }

    // importFrom(dataset).columnTypes(..).to(dataSource, insertSql) — type-mapped value binding.
    @SuppressWarnings("rawtypes")
    @Test
    public void testImportFrom_ColumnTypeMap() throws SQLException {
        final Map<String, Type> columnTypeMap = Map.of("id", Type.of(Long.class), "name", Type.of(String.class), "amount", Type.of(Double.class));

        final int imported = DataTransferUtil.importFrom(threeRowDataset()).columnTypes(columnTypeMap).to(ds, CSV_INSERT_SQL);

        assertEquals(3, imported);
        assertEquals(3, count("csv_tgt"));
    }

    // Configuring more than one value-mapping strategy is rejected when the import runs.
    @Test
    public void testImportFrom_MutuallyExclusiveStrategies_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.importFrom(threeRowDataset())
                        .columns(List.of("id", "name", "amount"))
                        .parameterSetter((pq, row) -> pq.setLong(1, (Long) row[0]))
                        .to(ds, CSV_INSERT_SQL));
    }

    private static final String COPY_SELECT_SQL = "SELECT id, name, amount FROM copy_src";
    private static final String COPY_INSERT_SQL = "INSERT INTO copy_tgt (id, name, amount) VALUES (?, ?, ?)";

    private String copyTgtName(final long id) throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT name FROM copy_tgt WHERE id = ?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // copyFrom(DataSource, selectSql).fetchSize(..).batchSize(..).to(DataSource, insertSql)
    @Test
    public void testCopyFrom_DataSource_Sql() throws SQLException {
        final long copied = DataTransferUtil.copyFrom(ds, COPY_SELECT_SQL).fetchSize(1000).batchSize(2).to(ds, COPY_INSERT_SQL);

        assertEquals(3, copied);
        assertEquals(3, count("copy_tgt"));
    }

    // copyFrom(Connection, selectSql).parameterSetter(..).to(Connection, insertSql)
    @Test
    public void testCopyFrom_Connection_Sql_WithStmtSetter() throws SQLException {
        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection()) {
            final long copied = DataTransferUtil.copyFrom(src, COPY_SELECT_SQL).batchSize(1).parameterSetter((pq, rs) -> {
                pq.setLong(1, rs.getLong(1));
                pq.setString(2, rs.getString(2).toUpperCase());
                pq.setDouble(3, rs.getDouble(3));
            }).to(tgt, COPY_INSERT_SQL);

            assertEquals(3, copied);
        }

        assertEquals(3, count("copy_tgt"));
        assertEquals("ALICE", copyTgtName(1));
    }

    // copyFrom(PreparedStatement).to(PreparedStatement)
    @Test
    public void testCopyFrom_Statement_To_Statement() throws SQLException {
        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection();
             PreparedStatement sel = src.prepareStatement(COPY_SELECT_SQL);
             PreparedStatement ins = tgt.prepareStatement(COPY_INSERT_SQL)) {
            final long copied = DataTransferUtil.copyFrom(sel).batchSize(2).to(ins);

            assertEquals(3, copied);
        }

        assertEquals(3, count("copy_tgt"));
    }

    // copyTable(DataSource, table).to(DataSource, table) — schema-derived all-columns copy.
    @Test
    public void testCopyTable_DataSource_AllColumns() throws SQLException {
        final long copied = DataTransferUtil.copyTable(ds, "copy_src").to(ds, "copy_tgt");

        assertEquals(3, copied);
        assertEquals(3, count("copy_tgt"));
        assertEquals("Alice", copyTgtName(1));
    }

    // copyTable(Connection, table).columns(..).batchSize(..).to(Connection, table)
    @Test
    public void testCopyTable_Connection_SelectColumns() throws SQLException {
        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection()) {
            final long copied = DataTransferUtil.copyTable(src, "copy_src").columns(List.of("id", "name")).batchSize(2).to(tgt, "copy_tgt");

            assertEquals(3, copied);
        }

        assertEquals(3, count("copy_tgt"));
        assertEquals("Alice", copyTgtName(1));
    }

    // ===== RowImportBuilder: importFrom / importCsvFrom (File / Reader / Iterator) =====

    private static final class Rec {
        final long id;
        final String name;
        final double amount;

        Rec(final long id, final String name, final double amount) {
            this.id = id;
            this.name = name;
            this.amount = amount;
        }
    }

    private List<Rec> threeRecs() {
        return List.of(new Rec(1, "Alice", 10.5), new Rec(2, "Bob", 20.0), new Rec(3, "Cara", 30.25));
    }

    // importFrom(Iterator).parameterSetter(..).to(DataSource, insertSql)
    @Test
    public void testImportFrom_Iterator_StmtSetter() throws SQLException {
        final long n = DataTransferUtil.importFrom(threeRecs().iterator()).parameterSetter((q, r) -> {
            q.setLong(1, r.id);
            q.setString(2, r.name);
            q.setDouble(3, r.amount);
        }).to(ds, CSV_INSERT_SQL);

        assertEquals(3, n);
        assertEquals(3, count("csv_tgt"));
        assertEquals("Alice", nameOf(1));
    }

    // importFrom(Iterator).filter(..).parameterSetter(..).to(Connection, insertSql)
    @Test
    public void testImportFrom_Iterator_StmtSetter_Filter() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            final long n = DataTransferUtil.importFrom(threeRecs().iterator())
                    .filter(r -> r.id != 2) // skip Bob
                    .parameterSetter((q, r) -> {
                        q.setLong(1, r.id);
                        q.setString(2, r.name);
                        q.setDouble(3, r.amount);
                    })
                    .to(conn, CSV_INSERT_SQL);

            assertEquals(2, n);
        }

        assertEquals(2, count("csv_tgt"));
        assertEquals("Alice", nameOf(1));
        assertEquals("Cara", nameOf(3));
    }

    // importCsvFrom(Reader).parameterSetter(..).to(Connection, insertSql) — header line is skipped.
    @Test
    public void testImportCsvFrom_Reader_HeaderSkipped() throws SQLException {
        final java.io.Reader reader = new java.io.StringReader("id,name,amount\n1,Alice,10.5\n2,Bob,20.0\n3,Cara,30.25");

        try (Connection conn = ds.getConnection()) {
            final long n = DataTransferUtil.importCsvFrom(reader).parameterSetter((q, row) -> {
                q.setLong(1, Long.parseLong(row[0]));
                q.setString(2, row[1]);
                q.setDouble(3, Double.parseDouble(row[2]));
            }).to(conn, CSV_INSERT_SQL);

            assertEquals(3, n); // header skipped; 3 data rows
        }

        assertEquals(3, count("csv_tgt"));
        assertEquals("Alice", nameOf(1));
    }

    // importCsvFrom(File).filter(..).parameterSetter(..).to(DataSource, insertSql)
    @Test
    public void testImportCsvFrom_File_Filter() throws Exception {
        final File csv = File.createTempFile("rib_csv_", ".csv");
        csv.deleteOnExit();
        java.nio.file.Files.write(csv.toPath(), List.of("id,name,amount", "1,Alice,10.5", "2,Bob,20.0", "3,Cara,30.25"));

        final long n = DataTransferUtil.importCsvFrom(csv)
                .filter(row -> Double.parseDouble(row[2]) >= 20.0) // skip Alice
                .parameterSetter((q, row) -> {
                    q.setLong(1, Long.parseLong(row[0]));
                    q.setString(2, row[1]);
                    q.setDouble(3, Double.parseDouble(row[2]));
                })
                .to(ds, CSV_INSERT_SQL);

        assertEquals(2, n);
        assertEquals(2, count("csv_tgt"));
        assertEquals("Bob", nameOf(2));
    }

    // No value-binding strategy configured -> IllegalArgumentException at the terminal.
    @Test
    public void testRowImportBuilder_NoStrategy_Throws() {
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importFrom(List.of("a", "b").iterator()).to(ds, CSV_INSERT_SQL));
    }

    // ===== CsvExportBuilder: exportCsv(DataSource / Connection / PreparedStatement / ResultSet) =====

    // exportCsv(DataSource, sql).to(Writer) — all columns.
    @Test
    public void testExportCsv_DataSource_ToWriter() throws SQLException {
        final java.io.StringWriter w = new java.io.StringWriter();

        final long n = DataTransferUtil.exportCsvFrom(ds, "SELECT id, name, amount FROM copy_src ORDER BY id").to(w);

        assertEquals(3, n);
        final String csv = w.toString();
        assertTrue(csv.contains("Alice"));
        assertTrue(csv.contains("Bob"));
        assertTrue(csv.contains("Cara"));
    }

    // exportCsv(Connection, sql).columns(..).to(Writer) — column selection drops the 'amount' column.
    @Test
    public void testExportCsv_Connection_SelectColumns_ToWriter() throws SQLException {
        final java.io.StringWriter w = new java.io.StringWriter();

        try (Connection conn = ds.getConnection()) {
            final long n = DataTransferUtil.exportCsvFrom(conn, "SELECT id AS \"id\", name AS \"name\", amount AS \"amount\" FROM copy_src ORDER BY id")
                    .columns(List.of("id", "name"))
                    .to(w);

            assertEquals(3, n);
        }

        final String csv = w.toString();
        assertTrue(csv.contains("id")); // id/name columns present in the header
        assertTrue(csv.contains("name"));
        assertTrue(csv.contains("Alice"));
        assertFalse(csv.contains("amount")); // 'amount' label excluded from the header
        assertFalse(csv.contains("10.5")); // 'amount' values excluded
        assertFalse(csv.contains("30.25"));
    }

    // exportCsvFrom(PreparedStatement).to(File) then importCsvFrom(File) round-trips back into csv_tgt.
    @Test
    public void testExportCsv_PreparedStatement_ToFile_RoundTrip() throws Exception {
        final File out = File.createTempFile("csv_export_", ".csv");
        out.deleteOnExit();

        try (Connection conn = ds.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT id, name, amount FROM copy_src ORDER BY id")) {
            final long exported = DataTransferUtil.exportCsvFrom(st).to(out);
            assertEquals(3, exported);
        }

        assertTrue(out.length() > 0L);

        final long imported = DataTransferUtil.importCsvFrom(out).parameterSetter((q, row) -> {
            q.setLong(1, Long.parseLong(row[0]));
            q.setString(2, row[1]);
            q.setDouble(3, Double.parseDouble(row[2]));
        }).to(ds, CSV_INSERT_SQL);

        assertEquals(3, imported);
        assertEquals(3, count("csv_tgt"));
        assertEquals("Alice", nameOf(1));
    }

    // exportCsv(ResultSet).to(Writer) — a caller-supplied ResultSet is exported and left open.
    @Test
    public void testExportCsv_ResultSet_ToWriter() throws SQLException {
        final java.io.StringWriter w = new java.io.StringWriter();

        try (Connection conn = ds.getConnection();
             PreparedStatement st = conn.prepareStatement("SELECT id, name FROM copy_src ORDER BY id");
             ResultSet rs = st.executeQuery()) {
            final long n = DataTransferUtil.exportCsvFrom(rs).to(w);
            assertEquals(3, n);
        }

        assertTrue(w.toString().contains("Cara"));
    }

    // selectColumnNames naming a column absent from the result -> IllegalArgumentException.
    @Test
    public void testExportCsv_SelectColumns_UnknownColumn_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.exportCsvFrom(ds, "SELECT id, name FROM copy_src").columns(List.of("does_not_exist")).to(new java.io.StringWriter()));
    }

    // ===== Builder option setters not exercised elsewhere (batchIntervalInMillis / fetchSize / stmtSetter /
    // selectColumnNames / to(PreparedStatement)). Each test asserts real row counts and a sampled value. =====

    // DatasetImportBuilder.batchDelay(..) — the inter-batch pause setter (with batchSize=1 so a batch
    // boundary is crossed for every row).
    @Test
    public void testImportFrom_Dataset_BatchIntervalInMillis() throws SQLException {
        try (Connection conn = ds.getConnection()) {
            final int imported = DataTransferUtil.importFrom(threeRowDataset()).batchSize(1).batchDelay(Duration.ofMillis(1)).to(conn, CSV_INSERT_SQL);

            assertEquals(3, imported);
        }

        assertEquals(3, count("csv_tgt"));
        assertEquals("Alice", nameOf(1));
    }

    // RowImportBuilder.batchSize(..).batchDelay(..).parameterSetter(..).to(PreparedStatement) — covers the
    // batchSize/batchIntervalInMillis setters and the to(PreparedStatement) terminal that no other test reaches.
    @Test
    public void testImportFrom_Iterator_BatchSize_Interval_ToStatement() throws SQLException {
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(CSV_INSERT_SQL)) {
            final long n = DataTransferUtil.importFrom(threeRecs().iterator()).batchSize(1).batchDelay(Duration.ofMillis(1)).parameterSetter((q, r) -> {
                q.setLong(1, r.id);
                q.setString(2, r.name);
                q.setDouble(3, r.amount);
            }).to(stmt);

            assertEquals(3, n);
        }

        assertEquals(3, count("csv_tgt"));
        assertEquals("Cara", nameOf(3));
    }

    // CopyFromDataSource.batchDelay(..).parameterSetter(..) — the pause setter plus the custom-binding setter
    // (the existing CopyFromDataSource test uses neither).
    @Test
    public void testCopyFrom_DataSource_BatchInterval_StmtSetter() throws SQLException {
        final long copied = DataTransferUtil.copyFrom(ds, COPY_SELECT_SQL).batchSize(1).batchDelay(Duration.ofMillis(1)).parameterSetter((pq, rs) -> {
            pq.setLong(1, rs.getLong(1));
            pq.setString(2, rs.getString(2).toUpperCase());
            pq.setDouble(3, rs.getDouble(3));
        }).to(ds, COPY_INSERT_SQL);

        assertEquals(3, copied);
        assertEquals(3, count("copy_tgt"));
        assertEquals("ALICE", copyTgtName(1));
    }

    // CopyFromConnection.fetchSize(..).batchDelay(..) — the fetchSize and pause setters, exercised with
    // the default (no stmtSetter) column-by-column copy.
    @Test
    public void testCopyFrom_Connection_FetchSize_BatchInterval() throws SQLException {
        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection()) {
            final long copied = DataTransferUtil.copyFrom(src, COPY_SELECT_SQL)
                    .fetchSize(10)
                    .batchSize(2)
                    .batchDelay(Duration.ofMillis(1))
                    .to(tgt, COPY_INSERT_SQL);

            assertEquals(3, copied);
        }

        assertEquals(3, count("copy_tgt"));
        assertEquals("Alice", copyTgtName(1));
    }

    // CopyFromStatement.batchDelay(..).parameterSetter(..) — the pause setter plus the custom-binding setter
    // (the existing CopyFromStatement test uses neither).
    @Test
    public void testCopyFrom_Statement_BatchInterval_StmtSetter() throws SQLException {
        try (Connection src = ds.getConnection();
             Connection tgt = ds.getConnection();
             PreparedStatement sel = src.prepareStatement(COPY_SELECT_SQL);
             PreparedStatement ins = tgt.prepareStatement(COPY_INSERT_SQL)) {
            final long copied = DataTransferUtil.copyFrom(sel).batchSize(1).batchDelay(Duration.ofMillis(1)).parameterSetter((pq, rs) -> {
                pq.setLong(1, rs.getLong(1));
                pq.setString(2, rs.getString(2).toUpperCase());
                pq.setDouble(3, rs.getDouble(3));
            }).to(ins);

            assertEquals(3, copied);
        }

        assertEquals(3, count("copy_tgt"));
        assertEquals("BOB", copyTgtName(2));
    }

    // CopyTableFromDataSource.columns(..).batchSize(..) — the column-restriction and batchSize setters on the
    // DataSource table-copy builder (the existing DataSource table-copy test uses neither). 'amount' is dropped.
    @Test
    public void testCopyTable_DataSource_SelectColumns_BatchSize() throws SQLException {
        final long copied = DataTransferUtil.copyTable(ds, "copy_src").columns(List.of("id", "name")).batchSize(2).to(ds, "copy_tgt");

        assertEquals(3, copied);
        assertEquals(3, count("copy_tgt"));
        assertEquals("Alice", copyTgtName(1));
    }

}
