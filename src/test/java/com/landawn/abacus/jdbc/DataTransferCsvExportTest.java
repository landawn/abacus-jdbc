package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DataTransferCsvExportTest extends DataTransferUtilTestSupport {

    // Tests for exportCsv methods

    @Test
    public void testExportCSVToFileWithDataSource() throws SQLException, IOException {
        // Setup
        File tempFile = File.createTempFile("export", ".csv");
        tempFile.deleteOnExit();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("col2");
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val3");
        when(mockResultSet.getObject(2)).thenReturn("val2", "val4");

        String querySql = "SELECT * FROM test_table";

        // Execute
        long result = DataTransferUtil.exportCsv(mockDataSource, querySql, tempFile);

        // Verify
        assertEquals(2, result);
        assertTrue(tempFile.exists());
        verify(mockDataSource).getConnection();
    }

    @Test
    public void testExportCSVToFileWithSelectedColumns() throws SQLException, IOException {
        // Setup
        File tempFile = File.createTempFile("export", ".csv");
        tempFile.deleteOnExit();
        Collection<String> selectColumnNames = Arrays.asList("col1");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("col2");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1");

        String querySql = "SELECT * FROM test_table";

        // Execute
        long result = DataTransferUtil.exportCsv(mockConnection, querySql, selectColumnNames, tempFile);

        // Verify
        assertEquals(1, result);
        assertTrue(tempFile.exists());
    }

    @Test
    public void testExportCSVToWriter() throws SQLException, IOException {
        // Setup
        Writer writer = new StringWriter();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");

        // Execute
        long result = DataTransferUtil.exportCsv(mockResultSet, writer);

        // Verify
        assertEquals(2, result);
        String csvContent = writer.toString();
        assertTrue(csvContent.contains("col1"));
        assertTrue(csvContent.contains("val1"));
        assertTrue(csvContent.contains("val2"));
    }

    @Test
    public void testExportCsv_HeterogeneousColumnValuesKeepTheirRuntimeTypes() throws Exception {
        final Writer writer = new StringWriter();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("value");
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(42, "forty-two");

        final long result = DataTransferUtil.exportCsv(mockResultSet, writer);

        assertEquals(2, result);
        assertTrue(writer.toString().contains("42"));
        assertTrue(writer.toString().contains("forty-two"));
        // The old implementation cached IntegerType from row one and called getInt for row two,
        // coercing a later String (or any other runtime type) through the first row's getter.
        verify(mockResultSet, never()).getInt(1);
    }

    @Test
    public void testExportCsv_FileOpenDoesNotUseRacyExistsThenCreateCheck() throws Exception {
        final File actualOutput = File.createTempFile("data-transfer-race", ".csv");
        actualOutput.deleteOnExit();
        final File output = new File(actualOutput.getPath()) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean exists() {
                throw new AssertionError("export must not perform a separate existence check");
            }

            @Override
            public boolean createNewFile() throws IOException {
                throw new AssertionError("opening the writer must create the file atomically");
            }
        };

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("value");
        when(mockResultSet.next()).thenReturn(false);

        assertEquals(0, DataTransferUtil.exportCsv(mockResultSet, output));
        assertTrue(actualOutput.length() > 0, "the CSV header should have been written");
    }

    @Test
    public void testExportCSVWithNullValues() throws SQLException, IOException {
        // Setup
        Writer writer = new StringWriter();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("col2");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1");
        when(mockResultSet.getObject(2)).thenReturn(null);

        // Execute
        long result = DataTransferUtil.exportCsv(mockResultSet, writer);

        // Verify
        assertEquals(1, result);
        String csvContent = writer.toString();
        assertTrue(csvContent.contains("null"));
    }

    @Test
    public void testExportCSVWithEmptySelectedColumnsExportsAllColumns() throws SQLException, IOException {
        Writer writer = new StringWriter();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("col2");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1");
        when(mockResultSet.getObject(2)).thenReturn("val2");

        long result = DataTransferUtil.exportCsv(mockResultSet, List.of(), writer);

        assertEquals(1, result);
        String csvContent = writer.toString();
        assertTrue(csvContent.contains("col1"));
        assertTrue(csvContent.contains("col2"));
        assertTrue(csvContent.contains("val1"));
        assertTrue(csvContent.contains("val2"));
    }

    @Test
    public void testExportCSVWithInvalidColumn() throws SQLException, IOException {
        // Setup
        Writer writer = new StringWriter();
        Collection<String> selectColumnNames = Arrays.asList("invalid_column");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");

        // Execute & Verify
        assertThrows(IllegalArgumentException.class, () -> {
            DataTransferUtil.exportCsv(mockResultSet, selectColumnNames, writer);
        });
    }

    // BUG FIX: the deprecated exportCsv(stmt, columnNames, File) overload executed the SELECT before
    // validating the output file, so a null output wasted a full query execution and failed late.
    // The output must be validated before any database work.
    @Test
    @Tag("2025")
    public void testExportCsvToNullFile_FailsFastWithoutExecutingQuery() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.exportCsv(mockPreparedStatement, null, (File) null));

        verify(mockPreparedStatement, never()).executeQuery();
    }

    // Same fail-fast contract for exportCsv(conn, selectSql, Writer): a null Writer must be rejected
    // before the SELECT is prepared/executed, matching the File-target overloads.
    @Test
    @Tag("2025")
    public void testExportCsvToNullWriter_FailsFastWithoutPreparingStatement() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.exportCsv(mockConnection, "SELECT col1 FROM t", (Writer) null));

        verify(mockConnection, never()).prepareStatement(anyString(), anyInt(), anyInt());
    }

    // Recycling the internal CSV writer flushes its buffer to the destination again; the second write failure used to
    // escape from the finally block as abacus-common's UncheckedIOException (not a java.io.UncheckedIOException),
    // replacing the declared java.io.UncheckedIOException.
    @Test
    public void testExportCsv_WriterFailureIsReportedAsDeclaredJavaIoUncheckedIOException() throws Exception {
        final IOException writeFailure = new IOException("disk full");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("value");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("x");

        final java.io.UncheckedIOException e = assertThrows(java.io.UncheckedIOException.class,
                () -> DataTransferUtil.exportCsv(mockResultSet, newAlwaysFailingWriter(writeFailure)));

        assertTrue(e.getCause() == writeFailure);
    }

    // A failure reading the ResultSet must not be replaced by the writer failure raised while recycling the CSV writer.
    @Test
    public void testExportCsv_ResultSetFailureIsNotMaskedByWriterFailureWhileRecycling() throws Exception {
        final SQLException readFailure = new SQLException("read failed");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("value");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenThrow(readFailure);

        final SQLException e = assertThrows(SQLException.class,
                () -> DataTransferUtil.exportCsv(mockResultSet, newAlwaysFailingWriter(new IOException("disk full"))));

        assertTrue(e == readFailure);
        assertEquals(1, e.getSuppressed().length);
    }

    // Guard (passes before and after the fix): a destination that throws the same unchecked exception instance on every
    // write makes recycling fail with the primary failure itself; it must be rethrown as is, not turned into the
    // IllegalArgumentException of a self-suppression.
    @Test
    public void testExportCsv_WriterRethrowingSameRuntimeExceptionWhileRecyclingIsNotSelfSuppressed() throws Exception {
        final IllegalStateException writeFailure = new IllegalStateException("closed");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("value");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("x");

        final Writer writer = new Writer() {
            @Override
            public void write(final char[] cbuf, final int off, final int len) {
                throw writeFailure;
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> DataTransferUtil.exportCsv(mockResultSet, writer));

        assertTrue(e == writeFailure);
        assertEquals(0, e.getSuppressed().length);
    }

    // exportCsv(PreparedStatement, File) - delegates to column-filtering version (line 2012-2013)
    @Test
    public void testExportCsvFromPreparedStatementToFile() throws SQLException, IOException {
        File tempFile = File.createTempFile("export_ps", ".csv");
        tempFile.deleteOnExit();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1");

        long result = DataTransferUtil.exportCsv(mockPreparedStatement, tempFile);

        assertEquals(1, result);
        assertTrue(tempFile.exists());
        verify(mockPreparedStatement).executeQuery();
    }

    // exportCsv(ResultSet, File) - delegates to column-filtering version (line 2087-2088)
    @Test
    public void testExportCsvFromResultSetToFile() throws SQLException, IOException {
        File tempFile = File.createTempFile("export_rs", ".csv");
        tempFile.deleteOnExit();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(42);

        long result = DataTransferUtil.exportCsv(mockResultSet, tempFile);

        assertEquals(1, result);
        assertTrue(tempFile.exists());
    }

    // exportCsv(DataSource, String, Writer) - delegates to Connection version (line 2164-2172)
    @Test
    public void testExportCsvFromDataSourceToWriter() throws SQLException, IOException {
        Writer writer = new StringWriter();

        when(mockConnection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(mockPreparedStatement);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("data");

        long result = DataTransferUtil.exportCsv(mockDataSource, "SELECT col1 FROM t", writer);

        assertEquals(1, result);
        assertTrue(writer.toString().contains("col1"));
        verify(mockDataSource).getConnection();
    }

    // exportCsv(Connection, String, Writer) - parses SQL, creates stmt, exports (line 2207-2221)
    @Test
    public void testExportCsvFromConnectionToWriter() throws SQLException, IOException {
        Writer writer = new StringWriter();

        when(mockConnection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(mockPreparedStatement);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("name");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("age");
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("Alice", "Bob");
        when(mockResultSet.getObject(2)).thenReturn(30, 25);

        long result = DataTransferUtil.exportCsv(mockConnection, "SELECT name, age FROM users", writer);

        assertEquals(2, result);
        String csv = writer.toString();
        assertTrue(csv.contains("name"));
        assertTrue(csv.contains("age"));
    }

    // Statements and result sets opened by an export are closed as by try-with-resources, as the File overloads already
    // did: a close failure after an otherwise successful export propagates instead of being logged and dropped.
    @Test
    public void testExportCsv_OwnedStatementCloseFailurePropagatesForWriterTargets() throws Exception {
        final SQLException closeFailure = new SQLException("statement close failed");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(false);
        Mockito.doThrow(closeFailure).when(mockPreparedStatement).close();

        assertSame(closeFailure, assertThrows(SQLException.class, () -> DataTransferUtil.exportCsv(mockConnection, "SELECT id FROM t", new StringWriter())));
        assertSame(closeFailure,
                assertThrows(SQLException.class, () -> DataTransferUtil.exportCsvFrom(mockConnection, "SELECT id FROM t").to(new StringWriter())));
        assertSame(closeFailure,
                assertThrows(SQLException.class, () -> DataTransferUtil.exportCsvFrom(mockDataSource, "SELECT id FROM t").to(new StringWriter())));
    }

    @Test
    public void testExportCsv_OwnedStatementCloseFailureIsSuppressedOnPrimaryFailure() throws Exception {
        final SQLException readFailure = new SQLException("read failed");
        final SQLException closeFailure = new SQLException("statement close failed");

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenThrow(readFailure);
        Mockito.doThrow(closeFailure).when(mockPreparedStatement).close();

        final SQLException e = assertThrows(SQLException.class, () -> DataTransferUtil.exportCsv(mockConnection, "SELECT id FROM t", new StringWriter()));

        assertSame(readFailure, e);
        assertTrue(Arrays.asList(e.getSuppressed()).contains(closeFailure));
    }

    @Test
    public void testExportCsv_ResultSetOpenedFromCallerStatementCloseFailurePropagates() throws Exception {
        final SQLException closeFailure = new SQLException("result set close failed");
        final File tempFile = File.createTempFile("export_rs_close", ".csv");
        tempFile.deleteOnExit();

        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(false);
        Mockito.doThrow(closeFailure).when(mockResultSet).close();

        assertSame(closeFailure, assertThrows(SQLException.class, () -> DataTransferUtil.exportCsv(mockPreparedStatement, tempFile)));
        assertSame(closeFailure, assertThrows(SQLException.class, () -> DataTransferUtil.exportCsvFrom(mockPreparedStatement).to(new StringWriter())));
        verify(mockPreparedStatement, never()).close();
        // The File path through a Connection closes its own statement normally; the result-set failure still surfaces.
        assertSame(closeFailure, assertThrows(SQLException.class, () -> DataTransferUtil.exportCsv(mockConnection, "SELECT id FROM t", tempFile)));
    }

    @Test
    @Tag("2025")
    public void testExportCsvFromResultSet_NullOutputIsRejectedBeforeReadingMetadata() throws SQLException {
        // The output check is free; resolveExportColumnNames() is a driver metadata round-trip, so the cheap
        // argument check must come first.
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.exportCsv(mockResultSet, Arrays.asList("col1"), (File) null));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.exportCsv(mockResultSet, Arrays.asList("col1"), (Writer) null));

        verify(mockResultSet, never()).getMetaData();
    }

    // Blank select SQL is rejected before a connection is checked out of the data source.
    @Test
    public void testExportCsvFromDataSource_BlankSqlRejectedBeforeConnectionAcquired() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.exportCsv(mockDataSource, "   ", new StringWriter()));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.exportCsvFrom(mockDataSource, "   ").to(new StringWriter()));

        verify(mockDataSource, never()).getConnection();
    }

    // Class doc: a caller-supplied Writer is never closed, and is flushed before a successful export returns (the export
    // writes through a pooled buffer, so without that flush the tail of the output would only reach the Writer later).
    @Test
    public void testExportCsv_CallerSuppliedWriterIsFlushedBeforeReturnAndNotClosed() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2", "val1", "val2", "val1", "val2");

        when(mockResultSet.next()).thenReturn(true, true, false);
        final RecordingWriter fromResultSet = new RecordingWriter();
        assertEquals(2, DataTransferUtil.exportCsv(mockResultSet, fromResultSet));
        fromResultSet.assertFlushedCompletelyAndOpen();

        when(mockResultSet.next()).thenReturn(true, true, false);
        final RecordingWriter fromConnection = new RecordingWriter();
        assertEquals(2, DataTransferUtil.exportCsv(mockConnection, "SELECT col1 FROM t", fromConnection));
        fromConnection.assertFlushedCompletelyAndOpen();

        when(mockResultSet.next()).thenReturn(true, true, false);
        final RecordingWriter fromBuilder = new RecordingWriter();
        assertEquals(2, DataTransferUtil.exportCsvFrom(mockDataSource, "SELECT col1 FROM t").to(fromBuilder));
        fromBuilder.assertFlushedCompletelyAndOpen();
    }

    /**
     * A Writer that records what had been written to it when it was last flushed, and whether it was closed.
     */
    private static final class RecordingWriter extends Writer {
        private final StringBuilder content = new StringBuilder();
        private String contentAtLastFlush = null;
        private boolean closed = false;

        @Override
        public void write(final char[] cbuf, final int off, final int len) {
            content.append(cbuf, off, len);
        }

        @Override
        public void flush() {
            contentAtLastFlush = content.toString();
        }

        @Override
        public void close() {
            closed = true;
        }

        void assertFlushedCompletelyAndOpen() {
            final String csv = content.toString();
            assertTrue(csv.contains("col1") && csv.contains("val1") && csv.contains("val2"), csv);
            assertEquals(csv, contentAtLastFlush, "the Writer must have been flushed after the last write");
            assertFalse(closed, "the caller-supplied Writer must not be closed");
        }
    }

    /**
     * A Writer whose every write fails, as a full disk or a closed socket would.
     */
    private static Writer newAlwaysFailingWriter(final IOException writeFailure) {
        return new Writer() {
            @Override
            public void write(final char[] cbuf, final int off, final int len) throws IOException {
                throw writeFailure;
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }
}
