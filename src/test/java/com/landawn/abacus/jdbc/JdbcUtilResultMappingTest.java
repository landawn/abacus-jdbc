package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.annotation.Transient;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.RowFilter;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.NamingPolicy;

class JdbcUtilResultMappingTest extends JdbcUtilTestSupport {

    @Test
    public void testSkipInt() throws SQLException {
        when(mockResultSet.getRow()).thenReturn(0, 2);
        when(mockResultSet.next()).thenReturn(true, true, false);

        long skipped = JdbcUtil.skip(mockResultSet, 2);
        assertEquals(2, skipped);
    }

    @Test
    public void testSkipLong() throws SQLException {
        when(mockResultSet.getRow()).thenReturn(0, 1);
        when(mockResultSet.next()).thenReturn(true, false);

        long skipped = JdbcUtil.skip(mockResultSet, 1L);
        assertEquals(1, skipped);
    }

    @Test
    public void testSkipDoesNotRewindAfterLastCursor() throws SQLException {
        final ResultSet exhausted = mock(ResultSet.class);
        when(exhausted.isAfterLast()).thenReturn(true);
        when(exhausted.getRow()).thenReturn(0, 2);

        assertEquals(0, JdbcUtil.skip(exhausted, 2L));
        verify(exhausted, never()).absolute(anyInt());
    }

    @Test
    public void testSkip_ManualIteration_WhenCurrentRowNearMax() throws SQLException {
        // rowsToSkip=2, currentRow=Integer.MAX_VALUE-1 → 2 > (MAX - (MAX-1)) = 2 > 1 → manual loop
        ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getRow()).thenReturn(Integer.MAX_VALUE - 1);
        when(freshRs.next()).thenReturn(true, false);

        long skipped = JdbcUtil.skip(freshRs, 2L);
        assertEquals(1, skipped);
    }

    @Test
    public void testSkip_Absolute_PastEnd_LastReturnsTrue() throws SQLException {
        // Use a fresh mock to avoid static resultSetClassNotSupportAbsolute pollution
        // rs.absolute() ignored; newRow=0 → else → rs.last() returns true → lastRow=3
        ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getRow()).thenReturn(0, 0, 3);
        when(freshRs.last()).thenReturn(true);

        long skipped = JdbcUtil.skip(freshRs, 5L);
        assertEquals(3, skipped);
        verify(freshRs).afterLast();
    }

    @Test
    public void testSkip_Absolute_PastEnd_LastReturnsFalse() throws SQLException {
        // Use a fresh mock; newRow=0 → else → rs.last() returns false → skipped=0
        ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getRow()).thenReturn(0, 0);
        when(freshRs.last()).thenReturn(false);

        long skipped = JdbcUtil.skip(freshRs, 5L);
        assertEquals(0, skipped);
    }

    @Test
    public void testSkip_GetRowUnsupported_FallsBackToManualIteration() throws SQLException {
        ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getRow()).thenThrow(new SQLException("getRow not supported"));
        when(freshRs.next()).thenReturn(true, true, false);

        long skipped = JdbcUtil.skip(freshRs, 2L);

        assertEquals(2, skipped);
    }

    @Test
    @DisplayName("skip(): a forward-only result set is iterated with next() and reports the exact number of consumed rows")
    public void testSkip_ForwardOnly_IteratesManuallyAndReportsConsumedRows() throws SQLException {
        // Some drivers (e.g. H2) let absolute() overshoot a forward-only cursor to after-last and only fail on
        // the subsequent last(), which used to report 0 rows skipped even though every remaining row was consumed.
        final ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        when(freshRs.getRow()).thenReturn(3);
        when(freshRs.next()).thenReturn(true, true, true, false);

        assertEquals(3, JdbcUtil.skip(freshRs, 100L));

        verify(freshRs, never()).absolute(anyInt());
        verify(freshRs, never()).last();
        verify(freshRs, org.mockito.Mockito.times(4)).next();
    }

    @Test
    public void testSkip_ZeroRows_ReturnsZero() throws SQLException {
        assertEquals(0, JdbcUtil.skip(mockResultSet, 0));
        assertEquals(0, JdbcUtil.skip(mockResultSet, 0L));
    }

    @Test
    public void testSkip_AbsoluteThrows_FallsBackToManualIteration() throws SQLException {
        // rs.absolute() throws → catch block → remaining=1 after getRow adjustment → rs.next()==true → skipped=2
        ResultSet freshRs = mock(ResultSet.class);
        when(freshRs.getRow()).thenReturn(0, 1);
        doThrow(new SQLException("absolute not supported")).when(freshRs).absolute(anyInt());
        when(freshRs.next()).thenReturn(true, false);

        long skipped = JdbcUtil.skip(freshRs, 2L);
        assertEquals(2, skipped);
    }

    @Test
    public void testGetColumnValue() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn("test");
        Object value = JdbcUtil.getColumnValue(mockResultSet, 1);
        assertEquals("test", value);

        when(mockResultSet.getObject(2)).thenReturn(123);
        Object numValue = JdbcUtil.getColumnValue(mockResultSet, 2);
        assertEquals(123, numValue);
    }

    @Test
    public void testGetColumnValueWithBlob() throws SQLException {
        byte[] data = "blob data".getBytes();
        when(mockBlob.length()).thenReturn((long) data.length);
        when(mockBlob.getBytes(1, data.length)).thenReturn(data);
        when(mockResultSet.getObject(1)).thenReturn(mockBlob);

        Object value = JdbcUtil.getColumnValue(mockResultSet, 1);
        assertArrayEquals(data, (byte[]) value);
    }

    @Test
    public void testGetColumnValueWithClob() throws SQLException {
        String data = "clob data";
        when(mockClob.length()).thenReturn((long) data.length());
        when(mockClob.getSubString(1, data.length())).thenReturn(data);
        when(mockResultSet.getObject(1)).thenReturn(mockClob);

        Object value = JdbcUtil.getColumnValue(mockResultSet, 1);
        assertEquals(data, value);
    }

    // A non-String/Number/Timestamp/Boolean/Blob/Clob value routes through the column converter
    // (JdbcUtil L2279); the default converter returns the value unchanged.
    @Test
    public void testGetColumnValue_NonLobObject_AppliesConverter() throws SQLException {
        final java.util.Date date = new java.util.Date();
        when(mockResultSet.getObject(1)).thenReturn(date);

        assertEquals(date, JdbcUtil.getColumnValue(mockResultSet, 1));
    }

    @Test
    public void testGetColumnValue_SqlDateUsesTimestampWhenMetadataSaysTimestamp() throws SQLException {
        final java.sql.Date date = java.sql.Date.valueOf("2026-06-29");
        final java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2026-06-29 10:15:30");

        when(mockResultSet.getObject(1)).thenReturn(date);
        when(mockResultSetMetaData.getColumnClassName(1)).thenReturn("java.sql.Timestamp");
        when(mockResultSet.getTimestamp(1)).thenReturn(timestamp);

        assertEquals(timestamp, JdbcUtil.getColumnValue(mockResultSet, 1));
    }

    // getColumnValue(rs, columnLabel) label-path: simple value short-circuits (JdbcUtil L2321/L2330).
    @Test
    public void testGetColumnValueByLabel() throws SQLException {
        when(mockResultSet.getObject("name")).thenReturn("Alice");
        assertEquals("Alice", JdbcUtil.getColumnValue(mockResultSet, "name"));
    }

    // getColumnValue(rs, columnLabel) label-path Blob materialization to byte[] (JdbcUtil L2334-2342).
    @Test
    public void testGetColumnValueByLabel_Blob() throws SQLException {
        final byte[] data = "blob".getBytes();
        when(mockBlob.length()).thenReturn((long) data.length);
        when(mockBlob.getBytes(1, data.length)).thenReturn(data);
        when(mockResultSet.getObject("data")).thenReturn(mockBlob);

        assertArrayEquals(data, (byte[]) JdbcUtil.getColumnValue(mockResultSet, "data"));
        verify(mockBlob).free();
    }

    // getColumnValue(rs, columnLabel) label-path Clob materialization to String (JdbcUtil L2344-2352).
    @Test
    public void testGetColumnValueByLabel_Clob() throws SQLException {
        final String data = "clob";
        when(mockClob.length()).thenReturn((long) data.length());
        when(mockClob.getSubString(1, data.length())).thenReturn(data);
        when(mockResultSet.getObject("doc")).thenReturn(mockClob);

        assertEquals(data, JdbcUtil.getColumnValue(mockResultSet, "doc"));
        verify(mockClob).free();
    }

    // getColumnValue(rs, columnLabel) label-path converter fallback for a non-LOB object (JdbcUtil L2355).
    @Test
    public void testGetColumnValueByLabel_NonLobObject_AppliesConverter() throws SQLException {
        final java.util.Date date = new java.util.Date();
        when(mockResultSet.getObject("created")).thenReturn(date);

        assertEquals(date, JdbcUtil.getColumnValue(mockResultSet, "created"));
    }

    @Test
    public void testGetColumnValueByLabel_SqlDateUsesTimestampWhenMetadataSaysTimestamp() throws SQLException {
        final java.sql.Date date = java.sql.Date.valueOf("2026-06-29");
        final java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2026-06-29 10:15:30");

        when(mockResultSet.getObject("created")).thenReturn(date);
        // JDBC returns a 1-based index; Mockito's unstubbed zero would skip the date conversion.
        when(mockResultSet.findColumn("created")).thenReturn(1);
        when(mockResultSetMetaData.getColumnClassName(1)).thenReturn("java.sql.Timestamp");
        when(mockResultSet.getTimestamp(1)).thenReturn(timestamp);

        assertEquals(timestamp, JdbcUtil.getColumnValue(mockResultSet, "created"));
    }

    @Test
    public void testGetColumnValueByLabel_SqlDateReturnsOriginalWhenLabelNotFoundInMetadata() throws SQLException {
        final java.sql.Date date = java.sql.Date.valueOf("2026-06-29");

        when(mockResultSet.getObject("created")).thenReturn(date);
        // Exercise the metadata fallback instead of returning early on Mockito's default index of zero.
        when(mockResultSet.findColumn("created")).thenThrow(new SQLException("Column not found: created"));
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("other");

        assertEquals(date, JdbcUtil.getColumnValue(mockResultSet, "created"));
        verify(mockResultSet, never()).getTimestamp(anyInt());
    }

    @Test
    public void testGetColumnValueWithTargetClass() throws SQLException {
        when(mockResultSet.getString(1)).thenReturn("test");
        String value = JdbcUtil.getColumnValue(mockResultSet, 1, String.class);
        assertEquals("test", value);

        when(mockResultSet.getInt(2)).thenReturn(123);
        when(mockResultSet.getObject(2)).thenReturn(123);
        Integer intValue = JdbcUtil.getColumnValue(mockResultSet, 2, Integer.class);
        assertEquals(123, intValue);
    }

    // Blob whose length exceeds Integer.MAX_VALUE trips the size guard (JdbcUtil L2262); the Blob is
    // still freed in the finally block.
    @Test
    public void testGetColumnValue_BlobSizeOverflow_Throws() throws SQLException {
        when(mockBlob.length()).thenReturn(Integer.MAX_VALUE + 1L);
        when(mockResultSet.getObject(1)).thenReturn(mockBlob);

        assertThrows(SQLException.class, () -> JdbcUtil.getColumnValue(mockResultSet, 1));
        verify(mockBlob).free();
    }

    // Clob whose length exceeds Integer.MAX_VALUE trips the size guard (JdbcUtil L2272).
    @Test
    public void testGetColumnValue_ClobSizeOverflow_Throws() throws SQLException {
        when(mockClob.length()).thenReturn(Integer.MAX_VALUE + 1L);
        when(mockResultSet.getObject(1)).thenReturn(mockClob);

        assertThrows(SQLException.class, () -> JdbcUtil.getColumnValue(mockResultSet, 1));
        verify(mockClob).free();
    }

    // BUG FIX: an empty LOB was read with getBytes(1, 0) / getSubString(1, 0), which the JDK's SerialBlob/SerialClob (also
    // what CachedRowSet returns) reject with SerialException because position 1 is past the end; it now reads as empty.
    @Test
    public void testGetColumnValue_EmptySerialLobs_ReadAsEmpty() throws SQLException {
        when(mockResultSet.getObject(1)).thenReturn(new javax.sql.rowset.serial.SerialBlob(new byte[0]));
        when(mockResultSet.getObject(2)).thenReturn(new javax.sql.rowset.serial.SerialClob(new char[0]));

        assertArrayEquals(new byte[0], (byte[]) JdbcUtil.getColumnValue(mockResultSet, 1));
        assertEquals("", JdbcUtil.getColumnValue(mockResultSet, 2));
        assertEquals("", JdbcUtil.blobToString(new javax.sql.rowset.serial.SerialBlob(new byte[0])));
        assertEquals("", JdbcUtil.clobToString(new javax.sql.rowset.serial.SerialClob(new char[0])));
        // Guards (pass before and after the fix): a non-empty LOB is still read whole.
        assertEquals("ab", JdbcUtil.clobToString(new javax.sql.rowset.serial.SerialClob("ab".toCharArray())));
        assertEquals("ab", JdbcUtil.blobToString(new javax.sql.rowset.serial.SerialBlob(new byte[] { 'a', 'b' })));
    }

    @Test
    public void testGetAllColumnValues() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");

        List<String> values = JdbcUtil.getAllColumnValues(mockResultSet, 1);

        assertEquals(2, values.size());
        assertEquals("val1", values.get(0));
        assertEquals("val2", values.get(1));
    }

    @Test
    public void testGetAllColumnValuesByName() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("name");

        List<String> values = JdbcUtil.getAllColumnValues(mockResultSet, "name");

        assertEquals(2, values.size());
    }

    // Test getAllColumnValues with Blob values
    @Test
    public void testGetAllColumnValues_BlobColumn() throws SQLException {
        byte[] data = "blobdata".getBytes();
        Blob blob1 = mock(Blob.class);
        Blob blob2 = mock(Blob.class);
        when(blob1.length()).thenReturn((long) data.length);
        when(blob1.getBytes(1, data.length)).thenReturn(data);
        when(blob2.length()).thenReturn((long) data.length);
        when(blob2.getBytes(1, data.length)).thenReturn(data);

        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(blob1, blob2);

        List<?> values = JdbcUtil.getAllColumnValues(mockResultSet, 1);
        assertEquals(2, values.size());
        assertArrayEquals(data, (byte[]) values.get(0));
        assertArrayEquals(data, (byte[]) values.get(1));
        verify(blob1).free();
        verify(blob2).free();
    }

    @Test
    public void testGetAllColumnValues_ConvertsEachRowsRuntimeTypeIndependently() throws SQLException {
        final byte[] data = "blobdata".getBytes(StandardCharsets.UTF_8);
        final Blob blob = mock(Blob.class);
        when(blob.length()).thenReturn((long) data.length);
        when(blob.getBytes(1, data.length)).thenReturn(data);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("plain", blob);

        final List<?> values = JdbcUtil.getAllColumnValues(mockResultSet, 1);

        assertEquals("plain", values.get(0));
        assertArrayEquals(data, (byte[]) values.get(1));
        verify(blob).free();
    }

    @Test
    public void testGetColumn2FieldNameMap() {
        // This would require a test entity class
        class TestEntity {
            private Long id;
            private String name;

            public Long getId() {
                return id;
            }

            public void setId(Long id) {
                this.id = id;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }
        }

        ImmutableMap<String, String> map = JdbcUtil.getColumnToPropNameMap(TestEntity.class);
        assertNotNull(map);
        // The map is keyed by non-standard column names that differ from Java field names;
        // for a simple entity whose column names match field names, it may be empty.
        assertTrue(map instanceof ImmutableMap, "should return an ImmutableMap");

        // Calling again should return the same cached instance (identity check)
        ImmutableMap<String, String> map2 = JdbcUtil.getColumnToPropNameMap(TestEntity.class);
        assertTrue(map == map2, "repeated calls should return the same cached ImmutableMap instance");
    }

    @Test
    public void testExtractionOverloadsValidateParametersInSignatureOrder() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.extractData(null, (Jdbc.RowFilter) null)).getMessage().contains("rs"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.extractData(null, (Jdbc.RowExtractor) null)).getMessage().contains("rs"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.extractData(null, null, null)).getMessage().contains("rs"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.extractData(mockResultSet, -1, -1, (Jdbc.RowFilter) null, false)).getMessage()
                .contains("offset"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.extractData(mockResultSet, 0, -1, (Jdbc.RowExtractor) null, false)).getMessage()
                .contains("count"));
        verifyNoInteractions(mockResultSet);
    }

    @Test
    public void testExtractData() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("name");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("John");

        Dataset data = JdbcUtil.extractData(mockResultSet);
        assertNotNull(data);
        assertEquals(1, data.size());
    }

    // A Dataset rejects duplicate/empty column names, so every extraction used to fail with IllegalArgumentException for
    // e.g. SELECT * over a join where both tables have ID, or an unlabeled expression column. Clashing labels are renamed.
    @Test
    public void testExtractData_DuplicateAndEmptyLabelsAreRenamed() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(5);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("ID");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("NAME");
        when(mockResultSetMetaData.getColumnLabel(3)).thenReturn("ID");
        when(mockResultSetMetaData.getColumnLabel(4)).thenReturn("");
        when(mockResultSetMetaData.getColumnName(4)).thenReturn("");
        when(mockResultSetMetaData.getColumnLabel(5)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L);
        when(mockResultSet.getObject(2)).thenReturn("John");
        when(mockResultSet.getObject(3)).thenReturn(10L);
        when(mockResultSet.getObject(4)).thenReturn(3L);
        when(mockResultSet.getObject(5)).thenReturn(100L);

        final Dataset data = JdbcUtil.extractData(mockResultSet);

        assertEquals(List.of("ID", "NAME", "ID_2", "column4", "id"), data.columnNames());
        assertEquals(10L, (Long) data.moveToRow(0).get("ID_2"));
        assertEquals(3L, (Long) data.moveToRow(0).get("column4"));
        // The row-extractor/filter overloads share the same naming.
        when(mockResultSet.next()).thenReturn(true, false);
        assertEquals(List.of("ID", "NAME", "ID_2", "column4", "id"),
                JdbcUtil.extractData(mockResultSet, 0, 10, rs -> true, (rs, row) -> Arrays.fill(row, 0L), false).columnNames());
    }

    @Test
    public void testMakeColumnNamesUnique() {
        assertEquals(List.of("ID", "NAME", "ID_2"), uniqueColumnNames("ID", "NAME", "ID"));
        assertEquals(List.of("ID", "ID_2", "ID_3"), uniqueColumnNames("ID", "ID", "ID"));
        // A generated name never takes a label used by another column.
        assertEquals(List.of("ID", "ID_3", "ID_2", "ID_4"), uniqueColumnNames("ID", "ID", "ID_2", "ID"));
        assertEquals(List.of("column1", "column2_2", "column2"), uniqueColumnNames("", null, "column2"));
        assertEquals(List.of("a_2", "a", "a_3"), uniqueColumnNames("a_2", "a", "a"));
        // Case-sensitive, as Dataset compares names: no rename.
        assertEquals(List.of("id", "ID"), uniqueColumnNames("id", "ID"));
        assertEquals(List.of("column1"), uniqueColumnNames(""));
        assertEquals(List.of("column1"), uniqueColumnNames((String) null));
        assertEquals(List.of("x"), uniqueColumnNames("x"));
        assertEquals(List.of(), uniqueColumnNames());
    }

    private static List<String> uniqueColumnNames(final String... labels) {
        final List<String> names = new java.util.ArrayList<>(Arrays.asList(labels));
        JdbcUtil.makeColumnNamesUnique(names);
        return names;
    }

    @Test
    public void testExtractDataWithOffsetAndCount() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L, 2L, 3L);

        Dataset data = JdbcUtil.extractData(mockResultSet, 1, 2);
        assertNotNull(data);
        assertEquals(2, data.size());
    }

    @Test
    public void testExtractDataWithFilter() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(1L, 2L);
        when(mockResultSet.getLong(1)).thenReturn(1L, 2L);

        RowFilter filter = rs -> {
            long id = rs.getLong(1);
            return id > 1;
        };

        Dataset data = JdbcUtil.extractData(mockResultSet, filter);
        assertNotNull(data);
        // Only the row with id > 1 (i.e. id=2) should pass the filter
        assertEquals(1, data.size(), "filter should keep only the row where id > 1");
    }

    @Test
    public void testExtractionHonorsOwnershipWhenDateTypeDetectionThrowsError() throws SQLException {
        final AssertionError failure = new AssertionError("driver linkage failed");
        final ResultSet owned = mock(ResultSet.class);
        final ResultSet borrowed = mock(ResultSet.class);
        when(owned.getStatement()).thenThrow(failure);
        when(borrowed.getStatement()).thenThrow(failure);

        assertSame(failure, assertThrows(AssertionError.class, () -> JdbcUtil.extractData(owned, true)));
        verify(owned).close();
        assertSame(failure, assertThrows(AssertionError.class, () -> JdbcUtil.extractData(borrowed, false)));
        verify(borrowed, never()).close();
    }

    @Test
    public void testExtractionPreservesDateDetectionFailureWhenClosingAlsoFails() throws SQLException {
        for (final boolean sameFailure : List.of(false, true)) {
            final AssertionError failure = new AssertionError("date-type detection failed");
            final AssertionError closeFailure = sameFailure ? failure : new AssertionError("result set close failed");
            final ResultSet rs = mock(ResultSet.class);
            when(rs.getStatement()).thenThrow(failure);
            doThrow(closeFailure).when(rs).close();

            assertSame(failure, assertThrows(AssertionError.class, () -> JdbcUtil.extractData(rs, true)));
            assertArrayEquals(sameFailure ? new Throwable[0] : new Throwable[] { closeFailure }, failure.getSuppressed());
            verify(rs).close();
        }
    }

    @Test
    public void testExtractionPreservesSqlFailureWhenClosingThrowsError() throws SQLException {
        final SQLException failure = new SQLException("column metadata failed");
        final AssertionError closeFailure = new AssertionError("result set close failed");
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenThrow(failure);
        doThrow(closeFailure).when(rs).close();

        assertSame(failure, assertThrows(SQLException.class, () -> JdbcUtil.extractData(rs, true)));
        assertArrayEquals(new Throwable[] { closeFailure }, failure.getSuppressed());
        verify(rs).close();
    }

    @Test
    public void testSuccessfulExtractionRetainsQuietCloseSemantics() throws SQLException {
        for (final Exception closeFailure : List.of(new SQLException("SQL close failure"), new IllegalStateException("runtime close failure"))) {
            final ResultSet rs = mock(ResultSet.class);
            when(rs.getMetaData()).thenReturn(mock(ResultSetMetaData.class));
            doThrow(closeFailure).when(rs).close();

            assertEquals(0, assertDoesNotThrow(() -> JdbcUtil.extractData(rs, true)).size());
            verify(rs).close();
        }

        final ResultSet rs = mock(ResultSet.class);
        final AssertionError closeFailure = new AssertionError("close error");
        when(rs.getMetaData()).thenReturn(mock(ResultSetMetaData.class));
        doThrow(closeFailure).when(rs).close();

        assertSame(closeFailure, assertThrows(AssertionError.class, () -> JdbcUtil.extractData(rs, true)));
        verify(rs).close();
    }

    // isDefaultIdPropValue: plain numeric zero vs non-zero (doubleValue path).
    @Test
    public void testIsDefaultIdPropValue_PlainNumbers() {
        assertTrue(JdbcUtil.isDefaultIdPropValue(0));
        assertTrue(JdbcUtil.isDefaultIdPropValue(0L));
        assertTrue(JdbcUtil.isDefaultIdPropValue(0.0d));
        assertFalse(JdbcUtil.isDefaultIdPropValue(5));
        assertFalse(JdbcUtil.isDefaultIdPropValue(3.5d));
        // a non-number, non-bean object is not a default id value.
        assertFalse(JdbcUtil.isDefaultIdPropValue("x"));
    }

    // isDefaultIdPropValue: EntityId entries are all-default or not.
    @Test
    public void testIsDefaultIdPropValue_EntityId() {
        assertTrue(JdbcUtil.isDefaultIdPropValue(com.landawn.abacus.util.EntityId.of("id", 0)));
        assertFalse(JdbcUtil.isDefaultIdPropValue(com.landawn.abacus.util.EntityId.of("id", 7)));
    }

    // isDefaultIdPropValue: bean with an @Id whose value is default vs set.
    @Test
    public void testIsDefaultIdPropValue_Bean() {
        final IdedEntity withDefault = new IdedEntity();
        assertTrue(JdbcUtil.isDefaultIdPropValue(withDefault));

        final IdedEntity withId = new IdedEntity();
        withId.setId(42L);
        assertFalse(JdbcUtil.isDefaultIdPropValue(withId));
    }

    // isDefaultIdPropValue: a non-null bean/record with no id property is a real value (e.g. a key built by a custom
    // generated-key extractor), not a default id. It used to count as default, so insert(RowMapper) returned empty.
    @Test
    public void testIsDefaultIdPropValue_Bean_NoIdProp() {
        assertFalse(JdbcUtil.isDefaultIdPropValue(new SubAddress()));
        assertFalse(JdbcUtil.isDefaultIdPropValue(new NoIdKey(0L, null)));
        assertFalse(JdbcUtil.isAllNullIds(List.of(new NoIdKey(1L, "a"), new NoIdKey(2L, "b"))));
        assertTrue(JdbcUtil.isAllNullIds(Arrays.asList(null, 0L)));
    }

    public record NoIdKey(long seq, String code) {
    }

    @Test
    public void testIsDefaultIdPropValue_Record() {
        assertTrue(JdbcUtil.isDefaultIdPropValue(new RecordId(0, 0)));
        assertFalse(JdbcUtil.isDefaultIdPropValue(new RecordId(0, 7)));
    }

    @Test
    public void testIsDefaultIdPropValue_BigNumberZeroDetection() {
        assertTrue(JdbcUtil.isDefaultIdPropValue(BigDecimal.ZERO));
        assertTrue(JdbcUtil.isDefaultIdPropValue(BigInteger.ZERO));
        assertFalse(JdbcUtil.isDefaultIdPropValue(new BigDecimal("1E-400")));
        assertFalse(JdbcUtil.isDefaultIdPropValue(BigInteger.ONE));
    }

    @ParameterizedTest(name = "{0} -> {3}, aliases={1}, labels={2}")
    @MethodSource("columnPrefixes")
    void columnPrefixesResolveAliasesAndPluralPropertiesWithoutCollisions(final String label, final Map<String, String> aliases, final List<String> labels,
            final String expected) {
        assertEquals(expected, JdbcUtil.checkPrefix(ParserUtil.getBeanInfo(PrefixContainer.class), label, aliases, labels));
    }

    private static java.util.stream.Stream<Arguments> columnPrefixes() {
        return java.util.stream.Stream.of(Arguments.of("id", null, List.of("id"), "id"), Arguments.of(".city", null, List.of(".city"), ".city"),
                Arguments.of("addresses.city", Map.of("addresses", "orders"), List.of("addresses.city"), "addresses.city"),
                Arguments.of("home.city", Map.of("home", "addresses"), List.of("home.city"), "addresses.city"),
                Arguments.of("order.total", Map.of("order", "missing"), List.of("order.total"), "orders.total"),
                Arguments.of("order.total", Map.of(), List.of("order.total"), "orders.total"),
                Arguments.of("order.total", null, List.of("order.total", "ORDERS.total"), "order.total"),
                Arguments.of("order.total", null, List.of("order.total", "orders", "ordersExtra.total"), "orders.total"),
                Arguments.of("address.city", null, List.of("address.city"), "addresses.city"),
                Arguments.of("address.city", null, List.of("address.city", "ADDRESSES.city"), "address.city"),
                Arguments.of("address.city", null, List.of("address.city", "addresses", "addressesExtra.city"), "addresses.city"),
                Arguments.of("box.city", null, List.of("box.city"), "boxes.city"),
                Arguments.of("box.city", null, List.of("box.city", "BOXES.city"), "box.city"),
                Arguments.of("tag.value", null, List.of("tag.value"), "tag.value"), Arguments.of("stat.value", null, List.of("stat.value"), "stat.value"),
                Arguments.of("missing.city", Map.of("missing", "absent"), List.of("missing.city"), "missing.city"));
    }

    @Test
    void generatedCompositeEntityIdMapsKnownColumnsAndRetainsUnknownDriverColumns() throws SQLException {
        final var accessors = JdbcUtil.<EntityId> getIdGeneratorGetterSetter(CrudDao.class, ColumnMappedCompositeIdEntity.class, NamingPolicy.SNAKE_CASE,
                EntityId.class);
        when(mockResultSet.getLong(1)).thenReturn(3L);
        when(mockResultSet.getLong(2)).thenReturn(42L);
        when(mockResultSet.getObject(3)).thenReturn("driver-token");

        final EntityId generatedId = accessors._1.apply(mockResultSet, List.of("TENANT_NO", "seq_no", "driver_extra"));

        assertEquals(3L, (Long) generatedId.get("tenantId"));
        assertEquals(42L, (Long) generatedId.get("entityId"));
        assertEquals("driver-token", generatedId.get("driver_extra"));
        final ColumnMappedCompositeIdEntity entity = new ColumnMappedCompositeIdEntity();
        accessors._3.accept(generatedId, entity);
        assertEquals(3L, entity.getTenantId());
        assertEquals(42L, entity.getEntityId());
        verify(mockResultSet).getLong(1);
        verify(mockResultSet).getLong(2);
        verify(mockResultSet).getObject(3);
    }

    @Test
    @SuppressWarnings("deprecation")
    void typedColumnLabelLookupUsesRequestedJdbcTypeAndPreservesSqlFailure() throws SQLException {
        when(mockResultSet.getString("display_name")).thenReturn("Ada");
        assertEquals("Ada", JdbcUtil.getColumnValue(mockResultSet, "display_name", String.class));
        final SQLException failure = new SQLException("unknown label");
        when(mockResultSet.getString("missing")).thenThrow(failure);
        assertSame(failure, assertThrows(SQLException.class, () -> JdbcUtil.getColumnValue(mockResultSet, "missing", String.class)));
        verify(mockResultSet, never()).close();
    }

    @lombok.Data
    public static class PrefixContainer {
        private long id;
        private List<SubOrder> orders;
        private SubAddress addresses;
        private List<SubAddress> boxes;
        private List<String> tags;
        private Integer stats;
    }

    // getSubPropInfo resolves a dotted path through a nested bean property.
    @Test
    public void testGetSubPropInfo_NestedBean() {
        final com.landawn.abacus.parser.ParserUtil.PropInfo pi = JdbcUtil.getSubPropInfo(SubPerson.class, "address.city");
        assertNotNull(pi);
        assertEquals("city", pi.name);
    }

    // getSubPropInfo follows a collection property to its element-type prop.
    @Test
    public void testGetSubPropInfo_CollectionElementProp() {
        final com.landawn.abacus.parser.ParserUtil.PropInfo pi = JdbcUtil.getSubPropInfo(SubPerson.class, "orders.total");
        assertNotNull(pi);
        assertEquals("total", pi.name);
    }

    // Second lookup of the same path hits the cached-Optional branch and returns the same prop.
    @Test
    public void testGetSubPropInfo_CachedLookup() {
        final com.landawn.abacus.parser.ParserUtil.PropInfo first = JdbcUtil.getSubPropInfo(SubPerson.class, "address.zip");
        final com.landawn.abacus.parser.ParserUtil.PropInfo second = JdbcUtil.getSubPropInfo(SubPerson.class, "address.zip");
        assertNotNull(first);
        assertEquals(first.name, second.name);
        // cached-empty branch: an unresolved path stays null on the second call too.
        assertNull(JdbcUtil.getSubPropInfo(SubPerson.class, "address.nope"));
        assertNull(JdbcUtil.getSubPropInfo(SubPerson.class, "address.nope"));
    }

    // A single-segment (non-dotted) name is not a sub-property -> null.
    @Test
    public void testGetSubPropInfo_SingleSegment_ReturnsNull() {
        assertNull(JdbcUtil.getSubPropInfo(SubPerson.class, "name"));
    }

    // First segment not a valid property -> null (short-circuit at i==0).
    @Test
    public void testGetSubPropInfo_InvalidFirstSegment_ReturnsNull() {
        assertNull(JdbcUtil.getSubPropInfo(SubPerson.class, "bogus.city"));
    }

    // Valid first segment but invalid leaf -> null.
    @Test
    public void testGetSubPropInfo_InvalidLeafSegment_ReturnsNull() {
        assertNull(JdbcUtil.getSubPropInfo(SubPerson.class, "address.bogus"));
    }

    @Test
    public void testGetInsertPropNames() {
        class TestEntity {
            private Long id;
            private String name;
            @ReadOnly
            private String readOnlyField;

            public Long getId() {
                return id;
            }

            public void setId(Long id) {
                this.id = id;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getReadOnlyField() {
                return readOnlyField;
            }

            public void setReadOnlyField(String readOnlyField) {
                this.readOnlyField = readOnlyField;
            }
        }

        TestEntity entity = new TestEntity();
        Collection<String> propNames = JdbcUtil.getInsertPropNames(entity.getClass());

        assertNotNull(propNames);
        assertFalse(propNames.contains("readOnlyField"));
    }

    // getInsertPropNames overloads
    @Test
    public void testGetInsertPropNames_WithExclusions() {
        class TestInsertEntity {
            private String firstName;
            private String lastName;
            @ReadOnly
            private String readOnlyField;

            public String getFirstName() {
                return firstName;
            }

            public void setFirstName(String firstName) {
                this.firstName = firstName;
            }

            public String getLastName() {
                return lastName;
            }

            public void setLastName(String lastName) {
                this.lastName = lastName;
            }

            public String getReadOnlyField() {
                return readOnlyField;
            }

            public void setReadOnlyField(String readOnlyField) {
                this.readOnlyField = readOnlyField;
            }
        }

        TestInsertEntity entity = new TestInsertEntity();
        Set<String> excluded = Set.of("lastName");
        Collection<String> propNames = JdbcUtil.getInsertPropNames(entity.getClass(), excluded);

        assertNotNull(propNames);
        assertFalse(propNames.contains("readOnlyField"));
        assertFalse(propNames.contains("lastName"));
        assertTrue(propNames.contains("firstName"));
    }

    @Test
    public void testGetInsertPropNames_FromClass() {
        class TestInsertClass {
            private String name;
            @ReadOnly
            private String readOnlyField;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getReadOnlyField() {
                return readOnlyField;
            }

            public void setReadOnlyField(String readOnlyField) {
                this.readOnlyField = readOnlyField;
            }
        }

        Collection<String> propNames = JdbcUtil.getInsertPropNames(TestInsertClass.class);

        assertNotNull(propNames);
        assertFalse(propNames.contains("readOnlyField"));
        assertTrue(propNames.contains("name"));
    }

    @Test
    public void testGetInsertPropNames_FromClassWithExclusions() {
        class TestInsertExClass {
            private String name;
            private String desc;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getDesc() {
                return desc;
            }

            public void setDesc(String desc) {
                this.desc = desc;
            }
        }

        Set<String> excluded = Set.of("desc");
        Collection<String> propNames = JdbcUtil.getInsertPropNames(TestInsertExClass.class, excluded);

        assertNotNull(propNames);
        assertFalse(propNames.contains("desc"));
        assertTrue(propNames.contains("name"));
    }

    @Test
    public void testCrudPropNamesExcludeJoinedByMapProperties() {
        class EntityWithMapJoin {
            private String name;
            @JoinedBy("id=RelatedEntity.entityId")
            private Map<Long, Object> relatedById;

            public String getName() {
                return name;
            }

            public void setName(final String name) {
                this.name = name;
            }

            public Map<Long, Object> getRelatedById() {
                return relatedById;
            }

            public void setRelatedById(final Map<Long, Object> relatedById) {
                this.relatedById = relatedById;
            }
        }

        assertFalse(JdbcUtil.getInsertPropNames(EntityWithMapJoin.class).contains("relatedById"));
        assertFalse(JdbcUtil.getSelectPropNames(EntityWithMapJoin.class).contains("relatedById"));
        assertFalse(JdbcUtil.getSelectPropNames(EntityWithMapJoin.class, true, null).contains("relatedById"));
        assertFalse(JdbcUtil.getUpdatePropNames(EntityWithMapJoin.class).contains("relatedById"));
        assertTrue(JdbcUtil.getInsertPropNames(EntityWithMapJoin.class).contains("name"));

        final Set<String> callerExclusions = Set.of("name");
        assertFalse(JdbcUtil.getInsertPropNames(EntityWithMapJoin.class, callerExclusions).contains("relatedById"));
        assertEquals(Set.of("name"), callerExclusions);
    }

    @Test
    public void testGetSelectPropNames() {
        class TestEntity {
            private Long id;
            private String name;
            @Transient
            private String transientField;

            public Long getId() {
                return id;
            }

            public void setId(Long id) {
                this.id = id;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getTransientField() {
                return transientField;
            }

            public void setTransientField(String transientField) {
                this.transientField = transientField;
            }
        }

        Collection<String> propNames = JdbcUtil.getSelectPropNames(TestEntity.class);

        assertNotNull(propNames);
        assertTrue(propNames.contains("id"));
        assertTrue(propNames.contains("name"));
        assertFalse(propNames.contains("transientField"));
    }

    // getSelectPropNames overloads
    @Test
    public void testGetSelectPropNames_WithExclusions() {
        class TestSelectClass {
            private Long id;
            private String name;
            @Transient
            private String transField;

            public Long getId() {
                return id;
            }

            public void setId(Long id) {
                this.id = id;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getTransField() {
                return transField;
            }

            public void setTransField(String transField) {
                this.transField = transField;
            }
        }

        Set<String> excluded = Set.of("id");
        Collection<String> propNames = JdbcUtil.getSelectPropNames(TestSelectClass.class, excluded);

        assertNotNull(propNames);
        assertFalse(propNames.contains("transField"));
        assertFalse(propNames.contains("id"));
        assertTrue(propNames.contains("name"));
    }

    @Test
    public void testGetSelectPropNames_WithSubEntities() {
        class TestSubEntityClass {
            private Long id;
            private String name;

            public Long getId() {
                return id;
            }

            public void setId(Long id) {
                this.id = id;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }
        }

        Collection<String> propNames = JdbcUtil.getSelectPropNames(TestSubEntityClass.class, true, null);

        assertNotNull(propNames);
        assertTrue(propNames.contains("id"));
        assertTrue(propNames.contains("name"));
    }

    // getUpdatePropNames overloads
    @Test
    public void testGetUpdatePropNames_FromClass() {
        class TestUpdateClass {
            private String name;
            @ReadOnly
            private String readOnlyField;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getReadOnlyField() {
                return readOnlyField;
            }

            public void setReadOnlyField(String readOnlyField) {
                this.readOnlyField = readOnlyField;
            }
        }

        Collection<String> propNames = JdbcUtil.getUpdatePropNames(TestUpdateClass.class);

        assertNotNull(propNames);
        assertTrue(propNames.contains("name"));
        assertFalse(propNames.contains("readOnlyField"));
    }

    @Test
    public void testGetUpdatePropNames_FromClassWithExclusions() {
        class TestUpdateExClass {
            private String name;
            private String desc;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getDesc() {
                return desc;
            }

            public void setDesc(String desc) {
                this.desc = desc;
            }
        }

        Set<String> excluded = Set.of("desc");
        Collection<String> propNames = JdbcUtil.getUpdatePropNames(TestUpdateExClass.class, excluded);

        assertNotNull(propNames);
        assertFalse(propNames.contains("desc"));
        assertTrue(propNames.contains("name"));
    }

    @Test
    public void testGeneratedIdCallbacksValidateOnlyEntitiesThatTheyUse() {
        final var withId = JdbcUtil.<Long> getIdGeneratorGetterSetter(CrudDao.class, IdedEntity.class, com.landawn.abacus.util.NamingPolicy.values()[0],
                Long.class);
        assertThrows(IllegalArgumentException.class, () -> withId._2.apply(null));
        assertThrows(IllegalArgumentException.class, () -> withId._3.accept(1L, null));
        final var withoutId = JdbcUtil.<Long> getIdGeneratorGetterSetter(CrudDao.class, SubAddress.class, com.landawn.abacus.util.NamingPolicy.values()[0],
                Long.class);
        assertNull(withoutId._2.apply(null));
        assertDoesNotThrow(() -> withoutId._3.accept(null, null));
    }

    @Test
    public void testGeneratedIdGetterBuildsImmutableRecordCompositeId() {
        final var accessors = JdbcUtil.<RecordId> getIdGeneratorGetterSetter(CrudDao.class, CompositeIdEntity.class,
                com.landawn.abacus.util.NamingPolicy.SNAKE_CASE, RecordId.class);
        final CompositeIdEntity entity = new CompositeIdEntity();
        entity.setTenantId(3);
        entity.setEntityId(7);

        assertEquals(new RecordId(3, 7), accessors._2.apply(entity));
    }

    // BUG FIX: the generated-key extractor for a composite ID class matched the generated-key column labels only
    // against the ID class's property names, so an ID column whose @Column name differs from its property name was
    // skipped and its generated value lost. The labels are now resolved through the entity's column mapping first.
    @Test
    public void testGeneratedKeyExtractorResolvesColumnMappedCompositeIdClassProperties() throws SQLException {
        final var accessors = JdbcUtil.<RecordId> getIdGeneratorGetterSetter(CrudDao.class, ColumnMappedCompositeIdEntity.class,
                com.landawn.abacus.util.NamingPolicy.SNAKE_CASE, RecordId.class);
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("TENANT_NO")).thenReturn(3L);
        when(rs.getLong("SEQ_NO")).thenReturn(42L);

        final RecordId generatedId = accessors._1.apply(rs, List.of("TENANT_NO", "SEQ_NO"));
        assertEquals(new RecordId(3, 42), generatedId);

        final ColumnMappedCompositeIdEntity entity = new ColumnMappedCompositeIdEntity();
        entity.setTenantId(3);
        accessors._3.accept(generatedId, entity);
        assertEquals(3, entity.getTenantId());
        assertEquals(42, entity.getEntityId());

        // Lower-case labels (as PostgreSQL reports them) resolve too; a generated column that is no ID part is ignored.
        final ResultSet lowerCaseRs = mock(ResultSet.class);
        when(lowerCaseRs.getLong("tenant_no")).thenReturn(5L);
        when(lowerCaseRs.getLong("seq_no")).thenReturn(43L);
        assertEquals(new RecordId(5, 43), accessors._1.apply(lowerCaseRs, List.of("tenant_no", "seq_no", "created_at")));
        verify(lowerCaseRs, never()).getLong("created_at");

        // A label naming an ID-class property directly (no column mapping) still resolves, as before.
        final ResultSet propNameRs = mock(ResultSet.class);
        when(propNameRs.getLong("TENANT_NO")).thenReturn(6L);
        when(propNameRs.getLong("entityId")).thenReturn(44L);
        assertEquals(new RecordId(6, 44), accessors._1.apply(propNameRs, List.of("TENANT_NO", "entityId")));
    }

    @Test
    public void testGeneratedKeyExtractorWrapsSingleCompositeKeyColumnAsEntityId() throws SQLException {
        final var accessors = JdbcUtil.<com.landawn.abacus.util.EntityId> getIdGeneratorGetterSetter(CrudDao.class, CompositeIdEntity.class,
                com.landawn.abacus.util.NamingPolicy.SNAKE_CASE, com.landawn.abacus.util.EntityId.class);
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(42L);

        final com.landawn.abacus.util.EntityId generatedId = accessors._1.apply(rs, List.of("ENTITY_ID"));
        assertEquals(42L, (Long) generatedId.get("entityId"));
        assertFalse(JdbcUtil.isDefaultIdPropValue(generatedId));

        final CompositeIdEntity entity = new CompositeIdEntity();
        entity.setTenantId(5);
        accessors._3.accept(generatedId, entity);
        assertEquals(5, entity.getTenantId());
        assertEquals(42, entity.getEntityId());
    }

    // BUG FIX: MySQL returns the AUTO_INCREMENT part of a composite key as a single GENERATED_KEY column. It used to be
    // attributed to the FIRST id property (overwriting the caller's tenantId), and then to "the only id property still
    // holding a default value", which also corrupts a valid zero component: for (tenantId = 0, entityId = 9 supplied
    // explicitly) the driver still reports GENERATED_KEY = 9 and tenantId became 9. Without a declaration of which id
    // property the database generates, the key is now left unassigned.
    @Test
    public void testGeneratedKeyWithUnmappedLabelIsNotGuessedOntoUndeclaredIdProperty() throws SQLException {
        final var accessors = JdbcUtil.<com.landawn.abacus.util.EntityId> getIdGeneratorGetterSetter(CrudDao.class, CompositeIdEntity.class,
                com.landawn.abacus.util.NamingPolicy.SNAKE_CASE, com.landawn.abacus.util.EntityId.class);
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(java.math.BigInteger.valueOf(9));

        final com.landawn.abacus.util.EntityId generatedId = accessors._1.apply(rs, List.of("GENERATED_KEY"));
        assertEquals(1, generatedId.size());
        assertFalse(JdbcUtil.isDefaultIdPropValue(generatedId));

        // A legitimate zero tenant with an explicitly supplied auto-increment id: nothing may be overwritten.
        final CompositeIdEntity zeroTenant = new CompositeIdEntity();
        zeroTenant.setEntityId(9);
        accessors._3.accept(generatedId, zeroTenant);
        assertEquals(0, zeroTenant.getTenantId());
        assertEquals(9, zeroTenant.getEntityId());

        // Even the seemingly obvious case is not guessed: the generated column is not declared.
        final CompositeIdEntity tenantSet = new CompositeIdEntity();
        tenantSet.setTenantId(5);
        accessors._3.accept(generatedId, tenantSet);
        assertEquals(5, tenantSet.getTenantId());
        assertEquals(0, tenantSet.getEntityId());

        assertThrows(IllegalArgumentException.class, () -> accessors._3.accept(generatedId, null));

        // A multi-entry EntityId without matching properties still leaves the entity untouched.
        final CompositeIdEntity untouched = new CompositeIdEntity();
        untouched.setTenantId(5);
        accessors._3.accept(com.landawn.abacus.util.EntityId.of("K1", 1, "K2", 2), untouched);
        assertEquals(5, untouched.getTenantId());
        assertEquals(0, untouched.getEntityId());
    }

    // The unlabeled generated key is assigned to the id property declared as database-generated (@ReadOnlyId or JPA
    // @GeneratedValue), whatever the other components hold, including a legitimate zero. Two declared generated id
    // properties are ambiguous and leave the entity untouched.
    @Test
    public void testGeneratedKeyWithUnmappedLabelIsAssignedToDeclaredGeneratedIdProperty() throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(java.math.BigInteger.valueOf(42));

        final var readOnlyIdAccessors = JdbcUtil.<com.landawn.abacus.util.EntityId> getIdGeneratorGetterSetter(CrudDao.class, ReadOnlyIdCompositeEntity.class,
                com.landawn.abacus.util.NamingPolicy.SNAKE_CASE, com.landawn.abacus.util.EntityId.class);
        final com.landawn.abacus.util.EntityId generatedId = readOnlyIdAccessors._1.apply(rs, List.of("GENERATED_KEY"));

        final ReadOnlyIdCompositeEntity zeroTenant = new ReadOnlyIdCompositeEntity();
        readOnlyIdAccessors._3.accept(generatedId, zeroTenant);
        assertEquals(0, zeroTenant.getTenantId());
        assertEquals(42, zeroTenant.getEntityId());

        final ReadOnlyIdCompositeEntity tenantSet = new ReadOnlyIdCompositeEntity();
        tenantSet.setTenantId(5);
        readOnlyIdAccessors._3.accept(generatedId, tenantSet);
        assertEquals(5, tenantSet.getTenantId());
        assertEquals(42, tenantSet.getEntityId());

        final var jpaAccessors = JdbcUtil.<com.landawn.abacus.util.EntityId> getIdGeneratorGetterSetter(CrudDao.class, JpaGeneratedCompositeEntity.class,
                com.landawn.abacus.util.NamingPolicy.SNAKE_CASE, com.landawn.abacus.util.EntityId.class);
        final JpaGeneratedCompositeEntity jpaEntity = new JpaGeneratedCompositeEntity();
        jpaAccessors._3.accept(jpaAccessors._1.apply(rs, List.of("GENERATED_KEYS")), jpaEntity);
        assertEquals(0, jpaEntity.getTenantId());
        assertEquals(42, jpaEntity.getEntityId());

        final var ambiguousAccessors = JdbcUtil.<com.landawn.abacus.util.EntityId> getIdGeneratorGetterSetter(CrudDao.class, TwoGeneratedIdsEntity.class,
                com.landawn.abacus.util.NamingPolicy.SNAKE_CASE, com.landawn.abacus.util.EntityId.class);
        final TwoGeneratedIdsEntity ambiguous = new TwoGeneratedIdsEntity();
        ambiguousAccessors._3.accept(ambiguousAccessors._1.apply(rs, List.of("GENERATED_KEY")), ambiguous);
        assertEquals(0, ambiguous.getFirstId());
        assertEquals(0, ambiguous.getSecondId());
    }

    @Test
    public void testSetIdExtractorForDao() {
        interface TestDao extends CrudDao<Object, Long, TestDao> {
        }

        RowMapper<Long> extractor = rs -> rs.getLong(1);
        JdbcUtil.setIdExtractorForDao(TestDao.class, extractor);
        assertTrue(getIdExtractorPool().containsKey(TestDao.class));
        getIdExtractorPool().remove(TestDao.class);
    }

    @Test
    public void testSetIdExtractorForDaoWithBiRowMapper() {
        interface TestDao extends CrudDao<Object, Long, TestDao> {
        }

        BiRowMapper<Long> extractor = (rs, labels) -> rs.getLong(1);
        JdbcUtil.setIdExtractorForDao(TestDao.class, extractor);
        assertTrue(extractor == getIdExtractorPool().get(TestDao.class));
        getIdExtractorPool().remove(TestDao.class);
    }

    @Test
    public void testIdExtractorRegistrationCannotRaceWithCachePublication() throws Exception {
        interface CacheRaceDao extends CrudDao<IdedEntity, Long, CacheRaceDao> {
        }

        final BiRowMapper<Long> extractorA = (rs, labels) -> 1L;
        final BiRowMapper<Long> extractorB = (rs, labels) -> 2L;
        final java.util.concurrent.CountDownLatch builderPaused = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseBuilder = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch setterDone = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        final com.landawn.abacus.util.NamingPolicy namingPolicy = com.landawn.abacus.util.NamingPolicy.values()[0];

        JdbcUtil.setIdExtractorForDao(CacheRaceDao.class, extractorA);

        final Thread builder = new Thread(() -> {
            try (org.mockito.MockedStatic<com.landawn.abacus.query.QueryUtil> queryUtil = org.mockito.Mockito
                    .mockStatic(com.landawn.abacus.query.QueryUtil.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
                final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                queryUtil.when(() -> com.landawn.abacus.query.QueryUtil.propToColumnNameMap(org.mockito.ArgumentMatchers.eq(IdedEntity.class),
                        org.mockito.ArgumentMatchers.any(com.landawn.abacus.util.NamingPolicy.class))).thenAnswer(invocation -> {
                            if (calls.incrementAndGet() == 2) {
                                builderPaused.countDown();
                                releaseBuilder.await(5, TimeUnit.SECONDS);
                            }

                            return invocation.callRealMethod();
                        });

                JdbcUtil.getIdGeneratorGetterSetter(CacheRaceDao.class, IdedEntity.class, namingPolicy, Long.class);
            } catch (final Throwable e) {
                failure.compareAndSet(null, e);
            }
        }, "id-accessor-cache-builder");

        final Thread setter = new Thread(() -> {
            try {
                JdbcUtil.setIdExtractorForDao(CacheRaceDao.class, extractorB);
            } catch (final Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                setterDone.countDown();
            }
        }, "id-accessor-registrar");

        try {
            builder.start();
            assertTrue(builderPaused.await(5, TimeUnit.SECONDS));
            setter.start();

            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (setterDone.getCount() != 0 && setter.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield();
            }

            assertEquals(Thread.State.BLOCKED, setter.getState(), "registration must wait for cache construction on the same monitor");
            releaseBuilder.countDown();
            builder.join(TimeUnit.SECONDS.toMillis(5));
            setter.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(builder.isAlive());
            assertFalse(setter.isAlive());
            assertNull(failure.get());

            final com.landawn.abacus.util.Tuple.Tuple3<BiRowMapper<Long>, ?, ?> accessors = JdbcUtil.getIdGeneratorGetterSetter(CacheRaceDao.class,
                    IdedEntity.class, namingPolicy, Long.class);
            assertEquals(2L, accessors._1.apply(mockResultSet, List.of("id")));
        } finally {
            releaseBuilder.countDown();
            builder.join(TimeUnit.SECONDS.toMillis(5));
            setter.join(TimeUnit.SECONDS.toMillis(5));
            getIdExtractorPool().remove(CacheRaceDao.class);

            final Field cacheField = JdbcUtil.class.getDeclaredField("idGeneratorGetterSetterPool");
            cacheField.setAccessible(true);
            ((Map<?, ?>) cacheField.get(null)).keySet().removeIf(key -> key.toString().contains(CacheRaceDao.class.getName()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Class<? extends com.landawn.abacus.jdbc.dao.Dao>, BiRowMapper<?>> getIdExtractorPool() {
        try {
            final Field field = JdbcUtil.class.getDeclaredField("idExtractorPool");
            field.setAccessible(true);
            return (Map<Class<? extends com.landawn.abacus.jdbc.dao.Dao>, BiRowMapper<?>>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------------
    // Coverage additions (2026-06-29): package-private pure-logic helpers.
    // ------------------------------------------------------------------------

    // Nested-bean fixtures for getSubPropInfo dotted-path resolution.
    public static class SubOrder {
        private Long id;
        private double total;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public double getTotal() {
            return total;
        }

        public void setTotal(final double total) {
            this.total = total;
        }
    }

    public static class SubAddress {
        private String city;
        private String zip;

        public String getCity() {
            return city;
        }

        public void setCity(final String city) {
            this.city = city;
        }

        public String getZip() {
            return zip;
        }

        public void setZip(final String zip) {
            this.zip = zip;
        }
    }

    public static class SubPerson {
        private Long id;
        private String name;
        private SubAddress address;
        private List<SubOrder> orders;

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

        public SubAddress getAddress() {
            return address;
        }

        public void setAddress(final SubAddress address) {
            this.address = address;
        }

        public List<SubOrder> getOrders() {
            return orders;
        }

        public void setOrders(final List<SubOrder> orders) {
            this.orders = orders;
        }
    }

    public static class IdedEntity {
        @com.landawn.abacus.annotation.Id
        private Long id;
        private String name;

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
    }

    public record RecordId(@com.landawn.abacus.annotation.Id long tenantId, @com.landawn.abacus.annotation.Id long entityId) {
    }

    public static class ReadOnlyIdCompositeEntity {
        @com.landawn.abacus.annotation.Id
        private long tenantId;
        @com.landawn.abacus.annotation.ReadOnlyId
        private long entityId;

        public long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final long tenantId) {
            this.tenantId = tenantId;
        }

        public long getEntityId() {
            return entityId;
        }

        public void setEntityId(final long entityId) {
            this.entityId = entityId;
        }
    }

    public static class JpaGeneratedCompositeEntity {
        @com.landawn.abacus.annotation.Id
        private long tenantId;
        @com.landawn.abacus.annotation.Id
        @jakarta.persistence.GeneratedValue
        private long entityId;

        public long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final long tenantId) {
            this.tenantId = tenantId;
        }

        public long getEntityId() {
            return entityId;
        }

        public void setEntityId(final long entityId) {
            this.entityId = entityId;
        }
    }

    public static class TwoGeneratedIdsEntity {
        @com.landawn.abacus.annotation.ReadOnlyId
        private long firstId;
        @com.landawn.abacus.annotation.ReadOnlyId
        private long secondId;

        public long getFirstId() {
            return firstId;
        }

        public void setFirstId(final long firstId) {
            this.firstId = firstId;
        }

        public long getSecondId() {
            return secondId;
        }

        public void setSecondId(final long secondId) {
            this.secondId = secondId;
        }
    }

    public static class CompositeIdEntity {
        @com.landawn.abacus.annotation.Id
        private long tenantId;
        @com.landawn.abacus.annotation.Id
        private long entityId;
        private String name;

        public long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final long tenantId) {
            this.tenantId = tenantId;
        }

        public long getEntityId() {
            return entityId;
        }

        public void setEntityId(final long entityId) {
            this.entityId = entityId;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    public static class ColumnMappedCompositeIdEntity {
        @com.landawn.abacus.annotation.Id
        @com.landawn.abacus.annotation.Column("TENANT_NO")
        private long tenantId;
        @com.landawn.abacus.annotation.Id
        @com.landawn.abacus.annotation.Column("SEQ_NO")
        private long entityId;

        public long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final long tenantId) {
            this.tenantId = tenantId;
        }

        public long getEntityId() {
            return entityId;
        }

        public void setEntityId(final long entityId) {
            this.entityId = entityId;
        }
    }
}
