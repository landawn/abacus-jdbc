package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.Throwables;

class DataTransferCsvImportTest extends DataTransferUtilTestSupport {

    @Test
    public void testImportCSVWithSkippedRows() throws SQLException, IOException, Exception {
        // Setup
        Reader reader = new StringReader("col1,col2\nval1,val2\nval3,val4");
        Predicate<String[]> filter = row -> row[0].equals("val1");
        Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> {
            stmt.setString(1, row[0]);
            stmt.setString(2, row[1]);
        };

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = DataTransferUtil.importCsv(reader, filter, mockPreparedStatement, 1, 0, stmtSetter);

        // Verify
        assertEquals(1, result); // Only 1 row matches filter
        verify(mockPreparedStatement, times(1)).addBatch();
    }

    // importCsv with batchSize=1 and a positive interval exercises the pause between batch executions
    // (DataTransferUtil L1918).
    @Test
    public void testImportCsv_BatchInterval() throws Exception {
        final Reader reader = new StringReader("col1\nv1\nv2");
        final Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> stmt.setString(1, row[0]);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        final long result = DataTransferUtil.importCsv(reader, row -> true, mockPreparedStatement, 1, 1L, stmtSetter);

        assertEquals(2, result);
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    // BUG FIX: importCsv read one physical line per row, so a quoted field containing a line break (which exportCsv
    // writes for such a value) was split and failed with ParsingException. With the default parsers it must be read
    // as one logical record, keeping LF and CRLF exactly, like CsvUtil's own loaders.
    @Test
    public void testImportCsv_QuotedFieldWithLineBreakIsOneRecord() throws SQLException {
        final List<String[]> rows = new ArrayList<>();

        final long result = DataTransferUtil.importCsv(new StringReader("id,note\r\n1,\"line1\nline2\"\r\n2,\"a\r\nb\"\n3,plain"), row -> true,
                mockPreparedStatement, 10, 0, (query, row) -> rows.add(row.clone()));

        assertEquals(3, result);
        assertEquals(3, rows.size());
        assertEquals(List.of("1", "line1\nline2"), Arrays.asList(rows.get(0)));
        assertEquals(List.of("2", "a\r\nb"), Arrays.asList(rows.get(1)));
        assertEquals(List.of("3", "plain"), Arrays.asList(rows.get(2)));
        verify(mockPreparedStatement, times(3)).addBatch();
    }

    // BUG FIX: a UTF-8 byte-order mark before a quoted first header field hid its opening quote, so the header
    // "a,b",c was split into three columns and every row array was one slot too wide. The mark is stripped from the
    // header like CsvUtil's own loaders do.
    @Test
    public void testImportCsv_ByteOrderMarkDoesNotHideQuotedHeaderField() throws SQLException {
        final List<String[]> rows = new ArrayList<>();

        final long result = DataTransferUtil.importCsv(new StringReader("\uFEFF\"a,b\",c\n1,2"), row -> true, mockPreparedStatement, 10, 0,
                (query, row) -> rows.add(row.clone()));

        assertEquals(1, result);
        assertEquals(List.of("1", "2"), Arrays.asList(rows.get(0)));
    }

    /**
     * Regression test for stale-data leakage in
     * {@code importCsv(Reader, Predicate, PreparedStatement, int, long, BiConsumer)}.
     *
     * <p>Before the fix the {@code output} array was nulled only when a row passed the
     * filter. When the filter rejected a row that had values in every column and the
     * next row had fewer columns, the trailing positions of the output array still held
     * the rejected row's values, so they leaked into the accepted row's parameters.
     * The fix nulls the array on the rejection path too.</p>
     */
    @Test
    public void testImportCsv_FilterRejection_DoesNotLeakStaleColumnValues() throws SQLException, IOException, Exception {
        // Header has 3 columns; rejected row fills all 3; accepted row has only 2 trailing nulls if parser leaves them
        final String csv = "c1,c2,c3\nreject,REJ_B,REJ_C\nkeep,K_B,K_C";

        final Reader reader = new StringReader(csv);
        final Predicate<String[]> filter = row -> "keep".equals(row[0]);

        final java.util.concurrent.atomic.AtomicReference<String[]> capturedRow = new java.util.concurrent.atomic.AtomicReference<>();
        final Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (pq, row) -> {
            capturedRow.set(row.clone());
            pq.setString(1, row[0]);
            pq.setString(2, row[1]);
            pq.setString(3, row[2]);
        };

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        final long result = DataTransferUtil.importCsv(reader, filter, mockPreparedStatement, 10, 0L, stmtSetter);

        assertEquals(1, result);
        // Accepted row's captured values must be exactly the keep row's values - no leakage from the rejected row.
        final String[] captured = capturedRow.get();
        assertEquals("keep", captured[0]);
        assertEquals("K_B", captured[1]);
        assertEquals("K_C", captured[2]);
    }

    // importCsv with an empty reader returns 0 without touching the statement (DataTransferUtil L1892-1893).
    @Test
    public void testImportCsv_EmptyReader_ReturnsZero() throws Exception {
        final Reader reader = new StringReader("");
        final Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> stmt.setString(1, row[0]);

        final long result = DataTransferUtil.importCsv(reader, row -> true, mockPreparedStatement, 1, 0L, stmtSetter);

        assertEquals(0, result);
        verify(mockPreparedStatement, never()).addBatch();
    }

    /**
     * Contract test for CSV short-row padding in
     * {@code importCsv(Reader, Predicate, PreparedStatement, int, long, BiConsumer)}.
     *
     * <p>The header determines the reusable row-array width. A data row with FEWER fields than the
     * header must be accepted and its missing trailing positions padded with {@code null} (class doc,
     * "CSV row width"; {@code parseCsvRow} only rejects rows WIDER than the header). This pins the
     * padding contract: the row array handed to the parameter setter is header-width with {@code null}
     * in every position the short row did not supply, and those {@code null}s are what get bound to
     * the statement parameters.</p>
     */
    @Test
    @Tag("2025")
    public void testImportCsv_RowShorterThanHeader_PadsMissingTrailingColumnsWithNull() throws Exception {
        // Header has 3 columns; the single accepted data row supplies only the first field.
        final String csv = "c1,c2,c3\nonly1";
        final Reader reader = new StringReader(csv);

        final java.util.concurrent.atomic.AtomicReference<String[]> capturedRow = new java.util.concurrent.atomic.AtomicReference<>();
        final Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (pq, row) -> {
            capturedRow.set(row.clone());
            pq.setString(1, row[0]);
            pq.setString(2, row[1]);
            pq.setString(3, row[2]);
        };

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        final long result = DataTransferUtil.importCsv(reader, row -> true, mockPreparedStatement, 10, 0L, stmtSetter);

        assertEquals(1, result);
        // The setter received a header-width row: field 0 from the CSV, trailing positions padded with null.
        final String[] captured = capturedRow.get();
        assertEquals(3, captured.length);
        assertEquals("only1", captured[0]);
        assertNull(captured[1]);
        assertNull(captured[2]);
        // And the padded nulls are exactly what was bound to the trailing statement parameters.
        verify(mockPreparedStatement).setString(1, "only1");
        verify(mockPreparedStatement).setString(2, null);
        verify(mockPreparedStatement).setString(3, null);
    }

    @Test
    public void testImportCsv_RowWiderThanHeaderHasDescriptiveFailure() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.importCsv(new StringReader("c1,c2\n1,2,3"), row -> true, mockPreparedStatement, 10, 0, (query, row) -> {
                    // Parsing must fail before the setter is invoked.
                }));

        assertTrue(failure.getMessage().contains("more fields than the header's 2 column(s)"));
        verify(mockPreparedStatement, never()).addBatch();
    }

    // Tests for importCsv methods

    @Test
    public void testImportCSVFromFileWithDataSource() throws SQLException, IOException {
        // Setup
        File tempFile = File.createTempFile("test", ".csv");
        tempFile.deleteOnExit();
        java.nio.file.Files.write(tempFile.toPath(), Arrays.asList("col1,col2", "val1,val2"));

        Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> {
            stmt.setString(1, row[0]);
            stmt.setString(2, row[1]);
        };
        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = DataTransferUtil.importCsv(tempFile, mockDataSource, insertSql, stmtSetter);

        // Verify
        assertEquals(1, result); // 1 data row (header skipped)
        verify(mockDataSource).getConnection();
    }

    @Test
    public void testImportCSVFromFileWithFilter() throws SQLException, IOException, Exception {
        // Setup
        File tempFile = File.createTempFile("test", ".csv");
        tempFile.deleteOnExit();
        java.nio.file.Files.write(tempFile.toPath(), Arrays.asList("col1,col2", "valid,val2", "invalid,val3", "valid,val4"));

        Predicate<String[]> filter = row -> "valid".equals(row[0]);
        Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> {
            stmt.setString(1, row[0]);
            stmt.setString(2, row[1]);
        };
        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = DataTransferUtil.importCsv(tempFile, filter, mockPreparedStatement, 1, 0, stmtSetter);

        // Verify
        assertEquals(2, result); // 2 valid rows
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    public void testImportCSVFromReaderWithDataSource() throws SQLException, IOException {
        // Setup
        Reader reader = new StringReader("col1,col2\nval1,val2\nval3,val4");
        Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> {
            stmt.setString(1, row[0]);
            stmt.setString(2, row[1]);
        };
        String insertSql = "INSERT INTO test_table (col1, col2) VALUES (?, ?)";

        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1 });

        // Execute
        long result = DataTransferUtil.importCsv(reader, mockDataSource, insertSql, stmtSetter);

        // Verify
        assertEquals(2, result); // 2 data rows
        verify(mockDataSource).getConnection();
    }

    // A directory passed as the CSV file is an illegal argument, so it is rejected before the positional overloads acquire a
    // connection or prepare a statement.
    @SuppressWarnings("deprecation")
    @Test
    public void testImportCsvFromDirectory_RejectedBeforeConnectionAcquiredOrStatementPrepared() throws IOException {
        final File probe = File.createTempFile("dir_probe", ".csv");
        final File directory = probe.getAbsoluteFile().getParentFile();
        assertTrue(probe.delete());
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> stmt.setString(1, row[0]);

        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importCsv(directory, dataSource, "INSERT INTO t VALUES (?)", stmtSetter));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importCsv(directory, connection, "INSERT INTO t VALUES (?)", 10, 0, stmtSetter));

        verifyNoInteractions(dataSource, connection);
    }

    @Test
    public void testImportCsvFromDirectoryBuilder_RejectedBeforeConnectionAcquiredOrStatementPrepared() throws IOException {
        final File probe = File.createTempFile("dir_probe", ".csv");
        final File directory = probe.getAbsoluteFile().getParentFile();
        assertTrue(probe.delete());
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> stmt.setString(1, row[0]);

        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.importCsvFrom(directory).parameterSetter(stmtSetter).to(dataSource, "INSERT INTO t VALUES (?)"));
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.importCsvFrom(directory).parameterSetter(stmtSetter).to(connection, "INSERT INTO t VALUES (?)"));

        verifyNoInteractions(dataSource, connection);
    }

    // A CSV file that cannot be opened is reported with the java.io.UncheckedIOException the importCsv methods declare (IOUtil's
    // own UncheckedIOException is not a java.io.UncheckedIOException), for both the positional overload and the builder, and
    // the caller's statement is left untouched. A directory is still rejected as an illegal argument.
    @Test
    public void testImportCsvFromMissingFile_ThrowsDeclaredUncheckedIOExceptionWithoutTouchingStatement() throws IOException {
        final File missing = File.createTempFile("missing", ".csv");
        assertTrue(missing.delete());
        final Throwables.BiConsumer<PreparedQuery, String[], SQLException> stmtSetter = (stmt, row) -> stmt.setString(1, row[0]);

        final java.io.UncheckedIOException positional = assertThrows(java.io.UncheckedIOException.class,
                () -> DataTransferUtil.importCsv(missing, mockPreparedStatement, stmtSetter));
        assertTrue(positional.getCause() instanceof java.io.FileNotFoundException, String.valueOf(positional.getCause()));

        final java.io.UncheckedIOException builder = assertThrows(java.io.UncheckedIOException.class,
                () -> DataTransferUtil.importCsvFrom(missing).parameterSetter(stmtSetter).to(mockPreparedStatement));
        assertTrue(builder.getCause() instanceof java.io.FileNotFoundException, String.valueOf(builder.getCause()));

        final File directory = missing.getParentFile();
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importCsv(directory, mockPreparedStatement, stmtSetter));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importCsvFrom(directory).parameterSetter(stmtSetter).to(mockPreparedStatement));

        verifyNoInteractions(mockPreparedStatement);
    }
}
