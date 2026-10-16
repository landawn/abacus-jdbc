package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.Throwables;

/**
 * Pinning tests for {@link DataTransferUtil} contracts verified during a deep review: caller-supplied
 * Reader/Writer ownership, pending-batch cleanup on callback failure, CSV record framing (quoted line
 * breaks), and builder validation. The CSV framing tests also guard the {@code CsvRecordReader} record
 * buffer reuse (the buffer must be reset per record, {@code toString()} must copy, and a buffer grown by an
 * unusually long record must not be retained).
 */
class DataTransferUtilReviewFixTest extends DataTransferUtilTestSupport {

    private static final class CloseTrackingReader extends StringReader {
        boolean closed;

        CloseTrackingReader(final String s) {
            super(s);
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }

    private static final class CloseTrackingWriter extends StringWriter {
        boolean closed;
        boolean flushed;

        @Override
        public void flush() {
            flushed = true;
            super.flush();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static Throwables.BiConsumer<PreparedQuery, String[], SQLException> capturingSetter(final List<String[]> sink) {
        return (q, row) -> sink.add(row.clone());
    }

    @Test
    public void importCsvLeavesCallerSuppliedReaderOpen() throws Exception {
        final CloseTrackingReader reader = new CloseTrackingReader("id,name\n1,Alice\n2,Bob\n");
        final List<String[]> rows = new ArrayList<>();

        final long imported = DataTransferUtil.importCsv(reader, mockPreparedStatement, capturingSetter(rows));

        assertEquals(2, imported);
        assertEquals(List.of("1", "Alice"), List.of(rows.get(0)));
        assertEquals(List.of("2", "Bob"), List.of(rows.get(1)));
        assertFalse(reader.closed, "caller-supplied reader must not be closed by importCsv");
    }

    @Test
    public void importCsvLeavesCallerSuppliedBufferedReaderOpen() throws Exception {
        final CloseTrackingReader raw = new CloseTrackingReader("id\n9\n");
        final BufferedReader reader = new BufferedReader(raw);
        final List<String[]> rows = new ArrayList<>();

        final long imported = DataTransferUtil.importCsv(reader, mockPreparedStatement, capturingSetter(rows));

        assertEquals(1, imported);
        assertEquals("9", rows.get(0)[0]);
        assertFalse(raw.closed, "caller-supplied buffered reader must not be closed or detached by importCsv");
    }

    @Test
    public void importCsvRejectsRowWiderThanHeader() {
        final StringReader reader = new StringReader("id,name\n1,Alice,extra\n");

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.importCsv(reader, mockPreparedStatement, capturingSetter(new ArrayList<>())));
        assertTrue(failure.getMessage().contains("more fields than the header"), failure.getMessage());
    }

    @Test
    public void importCsvClearsPendingBatchWhenSetterFails() throws Exception {
        final StringReader reader = new StringReader("id\n1\n2\n3\n");
        final AtomicInteger calls = new AtomicInteger();
        final IllegalStateException boom = new IllegalStateException("setter failed");

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> DataTransferUtil.importCsv(reader, mockPreparedStatement, (q, row) -> {
                    if (calls.incrementAndGet() == 2) {
                        throw boom;
                    }
                }));

        assertSame(boom, thrown);
        verify(mockPreparedStatement).addBatch();
        verify(mockPreparedStatement).clearBatch();
        verify(mockPreparedStatement, never()).executeBatch();
    }

    @Test
    public void importCsvSuppressesClearBatchFailureOnOriginalFailure() throws Exception {
        final StringReader reader = new StringReader("id\n1\n2\n");
        final IllegalStateException boom = new IllegalStateException("setter failed");
        final SQLException cleanupFailure = new SQLException("clearBatch failed");
        org.mockito.Mockito.doThrow(cleanupFailure).when(mockPreparedStatement).clearBatch();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> DataTransferUtil.importCsv(reader, mockPreparedStatement, (q, row) -> {
                    if ("2".equals(row[0])) {
                        throw boom;
                    }
                }));

        assertSame(boom, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
    }

    @Test
    public void importCsvKeepsQuotedLineBreaksInsideOneRecord() throws Exception {
        final List<String[]> rows = new ArrayList<>();

        final long imported = DataTransferUtil.importCsv(new StringReader("id,note\n1,\"a\nb\"\n2,\"c\r\nd\"\n"), mockPreparedStatement, capturingSetter(rows));

        assertEquals(2, imported);
        assertEquals("a\nb", rows.get(0)[1]);
        assertEquals("c\r\nd", rows.get(1)[1]);
    }

    @Test
    public void importCsvParsesVaryingWidthRecordsCorrectlyWithReusedBuffer() throws Exception {
        final StringBuilder csv = new StringBuilder("id,name\n");
        final List<String[]> expected = new ArrayList<>();
        final String longValue = "x".repeat(4096);

        for (int i = 0; i < 200; i++) {
            final String value = i % 3 == 0 ? longValue + i : "v" + i;
            csv.append(i).append(',').append(value).append('\n');
            expected.add(new String[] { String.valueOf(i), value });
        }

        final List<String[]> rows = new ArrayList<>();
        final long imported = DataTransferUtil.importCsv(new StringReader(csv.toString()), mockPreparedStatement, capturingSetter(rows));

        assertEquals(expected.size(), imported);

        for (int i = 0; i < expected.size(); i++) {
            assertEquals(List.of(expected.get(i)), List.of(rows.get(i)), "row " + i);
        }
    }

    // The reused record buffer must not keep the capacity of one unusually long record for the rest of the import:
    // it is replaced before the next record once it has grown past the retained-capacity limit, while a buffer grown
    // only by ordinary records keeps being reused.
    @Test
    public void csvRecordReaderDropsBufferGrownByAnUnusuallyLongRecord() throws Exception {
        final String longRecord = "x".repeat(100_000);
        final Class<?> readerClass = Class.forName(DataTransferUtil.class.getName() + "$CsvRecordReader");
        final java.lang.reflect.Constructor<?> constructor = readerClass.getDeclaredConstructor(BufferedReader.class);
        constructor.setAccessible(true);
        final java.lang.reflect.Method readRecord = readerClass.getDeclaredMethod("readRecord", boolean.class, boolean.class);
        readRecord.setAccessible(true);
        final java.lang.reflect.Field recordBuilder = readerClass.getDeclaredField("recordBuilder");
        recordBuilder.setAccessible(true);

        final Object records = constructor.newInstance(new BufferedReader(new StringReader("a,b\n" + longRecord + "\nc,d\ne,f\n")));

        assertEquals("a,b", readRecord.invoke(records, true, false));
        final StringBuilder ordinaryBuffer = (StringBuilder) recordBuilder.get(records);

        assertEquals(longRecord, readRecord.invoke(records, true, false));
        assertSame(ordinaryBuffer, recordBuilder.get(records), "an ordinary buffer is reused for the next record");
        assertTrue(ordinaryBuffer.capacity() >= longRecord.length());

        assertEquals("c,d", readRecord.invoke(records, true, false));
        final StringBuilder replacedBuffer = (StringBuilder) recordBuilder.get(records);
        assertNotSame(ordinaryBuffer, replacedBuffer, "the oversized buffer must be replaced");
        assertTrue(replacedBuffer.capacity() < longRecord.length(), "capacity=" + replacedBuffer.capacity());

        assertEquals("e,f", readRecord.invoke(records, true, false));
        assertSame(replacedBuffer, recordBuilder.get(records), "the small replacement buffer is reused again");
        assertNull(readRecord.invoke(records, true, false));
    }

    @Test
    public void importDatasetClearsPendingBatchWhenSetterFails() throws Exception {
        when(mockDataset.columnNames()).thenReturn(com.landawn.abacus.util.ImmutableList.of("id"));
        when(mockDataset.size()).thenReturn(3);
        when(mockDataset.get(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(0))).thenReturn(1, 2, 3);
        final IllegalStateException boom = new IllegalStateException("setter failed");
        final AtomicInteger calls = new AtomicInteger();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> DataTransferUtil.importData(mockDataset, mockPreparedStatement, (q, row) -> {
                    if (calls.incrementAndGet() == 2) {
                        throw boom;
                    }
                }));

        assertSame(boom, thrown);
        verify(mockPreparedStatement).clearBatch();
        verify(mockPreparedStatement, never()).executeBatch();
    }

    @Test
    public void exportCsvWritesNullMarkerAndLeavesWriterOpen() throws Exception {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("ID");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("NAME");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(7);
        when(mockResultSet.getObject(2)).thenReturn(null);

        final CloseTrackingWriter writer = new CloseTrackingWriter();
        final long exported = DataTransferUtil.exportCsv(mockResultSet, writer);

        assertEquals(1, exported);
        assertEquals("\"ID\",\"NAME\"\n7,null", writer.toString());
        assertTrue(writer.flushed, "writer must be flushed before exportCsv returns");
        assertFalse(writer.closed, "caller-supplied writer must not be closed by exportCsv");
    }

    @Test
    public void buildersRejectNegativeBatchInterval() {
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importFrom(List.of("x").iterator()).batchInterval(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copyFrom(mockPreparedStatement).batchInterval(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.importFrom(mockDataset).batchInterval(Duration.ofNanos(-1)));
    }

    @Test
    public void rowImportBuilderRequiresParameterSetter() {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.importFrom(List.of("x").iterator()).to(mockPreparedStatement));
        assertTrue(failure.getMessage().contains("parameterSetter"), failure.getMessage());
    }
}
