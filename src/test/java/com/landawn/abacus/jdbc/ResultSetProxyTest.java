/*
 * Copyright (c) 2025, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLXML;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;

@Tag("2025")
public class ResultSetProxyTest extends TestBase {

    private ResultSet delegate;
    private ResultSetProxy proxy;

    @BeforeEach
    public void setUp() {
        delegate = Mockito.mock(ResultSet.class);
        proxy = ResultSetProxy.wrap(delegate);
    }

    @Test
    public void testWrapNull() {
        assertNull(ResultSetProxy.wrap(null));
    }

    @Test
    public void testWrap_ReturnsProxy() throws SQLException {
        ResultSet delegate = Mockito.mock(ResultSet.class);
        when(delegate.unwrap(ResultSet.class)).thenReturn(delegate);

        ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertSame(delegate, proxy.unwrap(ResultSet.class));
    }

    @Test
    public void testDelegatesRowAccessMethods() throws SQLException {
        ResultSet delegate = Mockito.mock(ResultSet.class);
        when(delegate.next()).thenReturn(true);
        when(delegate.getString(1)).thenReturn("value");

        ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertTrue(proxy.next());
        assertEquals("value", proxy.getString(1));
        verify(delegate).next();
        verify(delegate).getString(1);
    }

    @Test
    public void testCloseAndWrapperChecksDelegate() throws SQLException {
        ResultSet delegate = Mockito.mock(ResultSet.class);
        when(delegate.isWrapperFor(ResultSet.class)).thenReturn(true);

        ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertTrue(proxy.isWrapperFor(ResultSet.class));
        proxy.close();

        verify(delegate).close();
        verify(delegate).isWrapperFor(ResultSet.class);
    }

    // Tests for delegation methods by column name (uncovered)
    @Test
    public void testWasNull() throws SQLException {
        when(delegate.wasNull()).thenReturn(true);
        assertTrue(proxy.wasNull());
        verify(delegate).wasNull();
    }

    @Test
    public void testGetStringByLabel() throws SQLException {
        when(delegate.getString("col")).thenReturn("value");
        assertEquals("value", proxy.getString("col"));
    }

    @Test
    public void testGetBooleanByLabel() throws SQLException {
        when(delegate.getBoolean("flag")).thenReturn(true);
        assertTrue(proxy.getBoolean("flag"));
    }

    @Test
    public void testGetByteByLabel() throws SQLException {
        when(delegate.getByte("b")).thenReturn((byte) 7);
        assertEquals((byte) 7, proxy.getByte("b"));
    }

    @Test
    public void testGetShortByLabel() throws SQLException {
        when(delegate.getShort("s")).thenReturn((short) 100);
        assertEquals((short) 100, proxy.getShort("s"));
    }

    @Test
    public void testGetIntByLabel() throws SQLException {
        when(delegate.getInt("id")).thenReturn(42);
        assertEquals(42, proxy.getInt("id"));
    }

    @Test
    public void testGetLongByLabel() throws SQLException {
        when(delegate.getLong("lng")).thenReturn(1000L);
        assertEquals(1000L, proxy.getLong("lng"));
    }

    @Test
    public void testGetFloatByLabel() throws SQLException {
        when(delegate.getFloat("f")).thenReturn(1.5f);
        assertEquals(1.5f, proxy.getFloat("f"), 0.001f);
    }

    @Test
    public void testGetDoubleByLabel() throws SQLException {
        when(delegate.getDouble("d")).thenReturn(3.14);
        assertEquals(3.14, proxy.getDouble("d"), 0.001);
    }

    @Test
    public void testGetBytesByLabel() throws SQLException {
        byte[] data = { 1, 2, 3 };
        when(delegate.getBytes("bytes")).thenReturn(data);
        assertArrayEquals(data, proxy.getBytes("bytes"));
    }

    @Test
    public void testGetDateByLabel() throws SQLException {
        Date date = new Date(System.currentTimeMillis());
        when(delegate.getDate("dt")).thenReturn(date);
        assertEquals(date, proxy.getDate("dt"));
    }

    @Test
    public void testGetTimeByLabel() throws SQLException {
        Time time = new Time(System.currentTimeMillis());
        when(delegate.getTime("t")).thenReturn(time);
        assertEquals(time, proxy.getTime("t"));
    }

    @Test
    public void testGetTimestampByLabel() throws SQLException {
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(delegate.getTimestamp("ts")).thenReturn(ts);
        assertEquals(ts, proxy.getTimestamp("ts"));
    }

    @Test
    public void testGetAsciiStreamByIndex() throws SQLException {
        InputStream is = new ByteArrayInputStream("data".getBytes());
        when(delegate.getAsciiStream(1)).thenReturn(is);
        assertSame(is, proxy.getAsciiStream(1));
    }

    @Test
    public void testGetBinaryStreamByIndex() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2 });
        when(delegate.getBinaryStream(1)).thenReturn(is);
        assertSame(is, proxy.getBinaryStream(1));
    }

    @Test
    public void testGetBigDecimalByLabel() throws SQLException {
        BigDecimal bd = new BigDecimal("123.45");
        when(delegate.getBigDecimal("bd")).thenReturn(bd);
        assertEquals(bd, proxy.getBigDecimal("bd"));
    }

    @Test
    public void testGetMetaData() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(delegate.getMetaData()).thenReturn(meta);
        assertSame(meta, proxy.getMetaData());
    }

    @Test
    public void testGetStatement() throws SQLException {
        Statement stmt = Mockito.mock(Statement.class);
        when(delegate.getStatement()).thenReturn(stmt);
        assertSame(stmt, proxy.getStatement());
    }

    @Test
    public void testFindColumn() throws SQLException {
        when(delegate.findColumn("col")).thenReturn(3);
        assertEquals(3, proxy.findColumn("col"));
    }

    @Test
    public void testGetRow() throws SQLException {
        when(delegate.getRow()).thenReturn(5);
        assertEquals(5, proxy.getRow());
    }

    @Test
    public void testIsFirst() throws SQLException {
        when(delegate.isFirst()).thenReturn(true);
        assertTrue(proxy.isFirst());
    }

    @Test
    public void testIsLast() throws SQLException {
        when(delegate.isLast()).thenReturn(false);
        assertFalse(proxy.isLast());
    }

    @Test
    public void testIsBeforeFirst() throws SQLException {
        when(delegate.isBeforeFirst()).thenReturn(true);
        assertTrue(proxy.isBeforeFirst());
    }

    @Test
    public void testIsAfterLast() throws SQLException {
        when(delegate.isAfterLast()).thenReturn(false);
        assertFalse(proxy.isAfterLast());
    }

    @Test
    public void testGetCursorName() throws SQLException {
        when(delegate.getCursorName()).thenReturn("cursor1");
        assertEquals("cursor1", proxy.getCursorName());
    }

    @Test
    public void testGetType() throws SQLException {
        when(delegate.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, proxy.getType());
    }

    @Test
    public void testGetConcurrency() throws SQLException {
        when(delegate.getConcurrency()).thenReturn(ResultSet.CONCUR_READ_ONLY);
        assertEquals(ResultSet.CONCUR_READ_ONLY, proxy.getConcurrency());
    }

    @Test
    public void testGetFetchSize() throws SQLException {
        when(delegate.getFetchSize()).thenReturn(100);
        assertEquals(100, proxy.getFetchSize());
    }

    @Test
    public void testGetFetchDirection() throws SQLException {
        when(delegate.getFetchDirection()).thenReturn(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, proxy.getFetchDirection());
    }

    @Test
    public void testGetHoldability() throws SQLException {
        when(delegate.getHoldability()).thenReturn(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, proxy.getHoldability());
    }

    @Test
    public void testIsClosed() throws SQLException {
        when(delegate.isClosed()).thenReturn(false);
        assertFalse(proxy.isClosed());
    }

    // Test getObject(columnIndex) delegation path - null value
    @Test
    public void testGetObjectByIndex_NullValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(1)).thenReturn(null);
        Object result = proxy.getObject(1);
        assertNull(result);
    }

    // Test getObject(columnIndex) delegation path - String value (caches GET_OBJECT getter)
    @Test
    public void testGetObjectByIndex_StringValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(1)).thenReturn("hello");
        assertEquals("hello", proxy.getObject(1));
    }

    // Test getObject(columnLabel) delegation path - null value
    @Test
    public void testGetObjectByLabel_NullValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("col")).thenReturn(1);
        when(delegate.getObject(1)).thenReturn(null);
        assertNull(proxy.getObject("col"));
    }

    // Test getObject(columnLabel) delegation path - String value
    @Test
    public void testGetObjectByLabel_StringValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("name")).thenReturn(2);
        when(delegate.getObject(2)).thenReturn("Alice");
        assertEquals("Alice", proxy.getObject("name"));
    }

    // Test getCharacterStream by column label
    @Test
    public void testGetCharacterStreamByLabel() throws SQLException {
        Reader reader = new StringReader("data");
        when(delegate.getCharacterStream("col")).thenReturn(reader);
        assertSame(reader, proxy.getCharacterStream("col"));
    }

    @Test
    public void testGetCharacterStreamByIndex() throws SQLException {
        Reader reader = new StringReader("data");
        when(delegate.getCharacterStream(1)).thenReturn(reader);
        assertSame(reader, proxy.getCharacterStream(1));
    }

    // Test navigation methods
    @Test
    public void testPrevious() throws SQLException {
        when(delegate.previous()).thenReturn(true);
        assertTrue(proxy.previous());
    }

    @Test
    public void testAbsolute() throws SQLException {
        when(delegate.absolute(5)).thenReturn(true);
        assertTrue(proxy.absolute(5));
    }

    @Test
    public void testRelative() throws SQLException {
        when(delegate.relative(2)).thenReturn(true);
        assertTrue(proxy.relative(2));
    }

    @Test
    public void testBeforeFirst() throws SQLException {
        proxy.beforeFirst();
        verify(delegate).beforeFirst();
    }

    @Test
    public void testAfterLast() throws SQLException {
        proxy.afterLast();
        verify(delegate).afterLast();
    }

    @Test
    public void testFirst() throws SQLException {
        when(delegate.first()).thenReturn(true);
        assertTrue(proxy.first());
    }

    @Test
    public void testLast() throws SQLException {
        when(delegate.last()).thenReturn(true);
        assertTrue(proxy.last());
    }

    @Test
    public void testGetWarnings() throws SQLException {
        assertNull(proxy.getWarnings());
    }

    @Test
    public void testClearWarnings() throws SQLException {
        proxy.clearWarnings();
        verify(delegate).clearWarnings();
    }

    // Test getObject(columnIndex) delegation - second call uses cached getter
    @Test
    public void testGetObjectByIndex_CachedGetter() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(2)).thenReturn(42);
        // First call caches getter
        assertEquals(42, proxy.getObject(2));
        // Second call uses cached getter
        when(delegate.getObject(2)).thenReturn(99);
        assertEquals(99, proxy.getObject(2));
    }

    // deprecated getBigDecimal(int, int) - line 263
    @Test
    public void testGetBigDecimal_WithScale() throws SQLException {
        BigDecimal bd = new BigDecimal("123.45");
        when(delegate.getBigDecimal(1, 2)).thenReturn(bd);
        assertEquals(bd, proxy.getBigDecimal(1, 2));
        verify(delegate).getBigDecimal(1, 2);
    }

    // deprecated getUnicodeStream(int) - line 304
    @Test
    public void testGetUnicodeStreamByIndex() throws SQLException {
        InputStream is = new ByteArrayInputStream("data".getBytes());
        when(delegate.getUnicodeStream(1)).thenReturn(is);
        assertSame(is, proxy.getUnicodeStream(1));
        verify(delegate).getUnicodeStream(1);
    }

    // getAsciiStream(String) - line 405
    @Test
    public void testGetAsciiStreamByLabel() throws SQLException {
        InputStream is = new ByteArrayInputStream("text".getBytes());
        when(delegate.getAsciiStream("col")).thenReturn(is);
        assertSame(is, proxy.getAsciiStream("col"));
        verify(delegate).getAsciiStream("col");
    }

    // deprecated getUnicodeStream(String) - line 412
    @Test
    public void testGetUnicodeStreamByLabel() throws SQLException {
        InputStream is = new ByteArrayInputStream("unicode".getBytes());
        when(delegate.getUnicodeStream("col")).thenReturn(is);
        assertSame(is, proxy.getUnicodeStream("col"));
        verify(delegate).getUnicodeStream("col");
    }

    // getBinaryStream(String) - line 418
    @Test
    public void testGetBinaryStreamByLabel() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        when(delegate.getBinaryStream("col")).thenReturn(is);
        assertSame(is, proxy.getBinaryStream("col"));
        verify(delegate).getBinaryStream("col");
    }

    // getObject(int, Class<T>) - line 611
    @Test
    public void testGetObjectByIndex_WithType() throws SQLException {
        when(delegate.getObject(1, String.class)).thenReturn("hello");
        assertEquals("hello", proxy.getObject(1, String.class));
        verify(delegate).getObject(1, String.class);
    }

    // getObject(String, Class<T>) - line 619
    @Test
    public void testGetObjectByLabel_WithType() throws SQLException {
        when(delegate.getObject("name", String.class)).thenReturn("world");
        assertEquals("world", proxy.getObject("name", String.class));
        verify(delegate).getObject("name", String.class);
    }

    // setFetchDirection(int) - line 739
    @Test
    public void testSetFetchDirection() throws SQLException {
        proxy.setFetchDirection(ResultSet.FETCH_FORWARD);
        verify(delegate).setFetchDirection(ResultSet.FETCH_FORWARD);
    }

    // setFetchSize(int) - line 753
    @Test
    public void testSetFetchSize() throws SQLException {
        proxy.setFetchSize(50);
        verify(delegate).setFetchSize(50);
    }

    // rowUpdated() - line 781
    @Test
    public void testRowUpdated() throws SQLException {
        when(delegate.rowUpdated()).thenReturn(true);
        assertTrue(proxy.rowUpdated());
        verify(delegate).rowUpdated();
    }

    // rowInserted() - line 787
    @Test
    public void testRowInserted() throws SQLException {
        when(delegate.rowInserted()).thenReturn(false);
        assertFalse(proxy.rowInserted());
        verify(delegate).rowInserted();
    }

    // rowDeleted() - line 793
    @Test
    public void testRowDeleted() throws SQLException {
        when(delegate.rowDeleted()).thenReturn(true);
        assertTrue(proxy.rowDeleted());
        verify(delegate).rowDeleted();
    }

    // updateNull(int) - line 799
    @Test
    public void testUpdateNull_ByIndex() throws SQLException {
        proxy.updateNull(1);
        verify(delegate).updateNull(1);
    }

    // updateBoolean(int, boolean) - line 805
    @Test
    public void testUpdateBoolean_ByIndex() throws SQLException {
        proxy.updateBoolean(1, true);
        verify(delegate).updateBoolean(1, true);
    }

    // updateByte(int, byte) - line 811
    @Test
    public void testUpdateByte_ByIndex() throws SQLException {
        proxy.updateByte(1, (byte) 7);
        verify(delegate).updateByte(1, (byte) 7);
    }

    // updateShort(int, short) - line 817
    @Test
    public void testUpdateShort_ByIndex() throws SQLException {
        proxy.updateShort(1, (short) 100);
        verify(delegate).updateShort(1, (short) 100);
    }

    // updateInt(int, int) - line 823
    @Test
    public void testUpdateInt_ByIndex() throws SQLException {
        proxy.updateInt(1, 42);
        verify(delegate).updateInt(1, 42);
    }

    // updateLong(int, long) - line 829
    @Test
    public void testUpdateLong_ByIndex() throws SQLException {
        proxy.updateLong(1, 999L);
        verify(delegate).updateLong(1, 999L);
    }

    @Test
    public void testUpdateFloat_ByIndex() throws SQLException {
        proxy.updateFloat(1, 1.5f);
        verify(delegate).updateFloat(1, 1.5f);
    }

    @Test
    public void testUpdateDouble_ByIndex() throws SQLException {
        proxy.updateDouble(1, 3.14);
        verify(delegate).updateDouble(1, 3.14);
    }

    @Test
    public void testUpdateBigDecimal_ByIndex() throws SQLException {
        BigDecimal bd = new BigDecimal("99.99");
        proxy.updateBigDecimal(1, bd);
        verify(delegate).updateBigDecimal(1, bd);
    }

    @Test
    public void testUpdateString_ByIndex() throws SQLException {
        proxy.updateString(1, "hello");
        verify(delegate).updateString(1, "hello");
    }

    @Test
    public void testUpdateBytes_ByIndex() throws SQLException {
        byte[] data = { 1, 2, 3 };
        proxy.updateBytes(1, data);
        verify(delegate).updateBytes(1, data);
    }

    @Test
    public void testUpdateDate_ByIndex() throws SQLException {
        Date date = new Date(System.currentTimeMillis());
        proxy.updateDate(1, date);
        verify(delegate).updateDate(1, date);
    }

    @Test
    public void testUpdateTime_ByIndex() throws SQLException {
        Time time = new Time(System.currentTimeMillis());
        proxy.updateTime(1, time);
        verify(delegate).updateTime(1, time);
    }

    @Test
    public void testUpdateTimestamp_ByIndex() throws SQLException {
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        proxy.updateTimestamp(1, ts);
        verify(delegate).updateTimestamp(1, ts);
    }

    @Test
    public void testUpdateAsciiStream_ByIndex() throws SQLException {
        InputStream is = new ByteArrayInputStream("data".getBytes());
        proxy.updateAsciiStream(1, is, 4);
        verify(delegate).updateAsciiStream(1, is, 4);
    }

    @Test
    public void testUpdateBinaryStream_ByIndex() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2 });
        proxy.updateBinaryStream(1, is, 2);
        verify(delegate).updateBinaryStream(1, is, 2);
    }

    @Test
    public void testUpdateCharacterStream_ByIndex() throws SQLException {
        Reader reader = new StringReader("data");
        proxy.updateCharacterStream(1, reader, 4);
        verify(delegate).updateCharacterStream(1, reader, 4);
    }

    @Test
    public void testUpdateObject_ByIndex_WithScale() throws SQLException {
        proxy.updateObject(1, "val", 2);
        verify(delegate).updateObject(1, "val", 2);
    }

    @Test
    public void testUpdateObject_ByIndex() throws SQLException {
        proxy.updateObject(1, "val");
        verify(delegate).updateObject(1, "val");
    }

    // Update methods by column label
    @Test
    public void testUpdateNull_ByLabel() throws SQLException {
        proxy.updateNull("col");
        verify(delegate).updateNull("col");
    }

    @Test
    public void testUpdateBoolean_ByLabel() throws SQLException {
        proxy.updateBoolean("col", false);
        verify(delegate).updateBoolean("col", false);
    }

    @Test
    public void testUpdateByte_ByLabel() throws SQLException {
        proxy.updateByte("col", (byte) 3);
        verify(delegate).updateByte("col", (byte) 3);
    }

    @Test
    public void testUpdateShort_ByLabel() throws SQLException {
        proxy.updateShort("col", (short) 5);
        verify(delegate).updateShort("col", (short) 5);
    }

    @Test
    public void testUpdateInt_ByLabel() throws SQLException {
        proxy.updateInt("col", 10);
        verify(delegate).updateInt("col", 10);
    }

    @Test
    public void testUpdateLong_ByLabel() throws SQLException {
        proxy.updateLong("col", 100L);
        verify(delegate).updateLong("col", 100L);
    }

    @Test
    public void testUpdateFloat_ByLabel() throws SQLException {
        proxy.updateFloat("col", 2.0f);
        verify(delegate).updateFloat("col", 2.0f);
    }

    @Test
    public void testUpdateDouble_ByLabel() throws SQLException {
        proxy.updateDouble("col", 4.0);
        verify(delegate).updateDouble("col", 4.0);
    }

    @Test
    public void testUpdateBigDecimal_ByLabel() throws SQLException {
        BigDecimal bd = new BigDecimal("1.5");
        proxy.updateBigDecimal("col", bd);
        verify(delegate).updateBigDecimal("col", bd);
    }

    @Test
    public void testUpdateString_ByLabel() throws SQLException {
        proxy.updateString("col", "world");
        verify(delegate).updateString("col", "world");
    }

    @Test
    public void testUpdateBytes_ByLabel() throws SQLException {
        byte[] data = { 9, 8 };
        proxy.updateBytes("col", data);
        verify(delegate).updateBytes("col", data);
    }

    @Test
    public void testUpdateDate_ByLabel() throws SQLException {
        Date date = new Date(System.currentTimeMillis());
        proxy.updateDate("col", date);
        verify(delegate).updateDate("col", date);
    }

    @Test
    public void testUpdateTime_ByLabel() throws SQLException {
        Time time = new Time(System.currentTimeMillis());
        proxy.updateTime("col", time);
        verify(delegate).updateTime("col", time);
    }

    @Test
    public void testUpdateTimestamp_ByLabel() throws SQLException {
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        proxy.updateTimestamp("col", ts);
        verify(delegate).updateTimestamp("col", ts);
    }

    @Test
    public void testUpdateAsciiStream_ByLabel() throws SQLException {
        InputStream is = new ByteArrayInputStream("x".getBytes());
        proxy.updateAsciiStream("col", is, 1);
        verify(delegate).updateAsciiStream("col", is, 1);
    }

    @Test
    public void testUpdateBinaryStream_ByLabel() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 5 });
        proxy.updateBinaryStream("col", is, 1);
        verify(delegate).updateBinaryStream("col", is, 1);
    }

    @Test
    public void testUpdateCharacterStream_ByLabel() throws SQLException {
        Reader reader = new StringReader("z");
        proxy.updateCharacterStream("col", reader, 1);
        verify(delegate).updateCharacterStream("col", reader, 1);
    }

    @Test
    public void testUpdateObject_ByLabel_WithScale() throws SQLException {
        proxy.updateObject("col", "v", 2);
        verify(delegate).updateObject("col", "v", 2);
    }

    @Test
    public void testUpdateObject_ByLabel() throws SQLException {
        proxy.updateObject("col", "v");
        verify(delegate).updateObject("col", "v");
    }

    // Row manipulation methods
    @Test
    public void testInsertRow() throws SQLException {
        proxy.insertRow();
        verify(delegate).insertRow();
    }

    @Test
    public void testUpdateRow() throws SQLException {
        proxy.updateRow();
        verify(delegate).updateRow();
    }

    @Test
    public void testDeleteRow() throws SQLException {
        proxy.deleteRow();
        verify(delegate).deleteRow();
    }

    @Test
    public void testRefreshRow() throws SQLException {
        proxy.refreshRow();
        verify(delegate).refreshRow();
    }

    @Test
    public void testCancelRowUpdates() throws SQLException {
        proxy.cancelRowUpdates();
        verify(delegate).cancelRowUpdates();
    }

    @Test
    public void testMoveToInsertRow() throws SQLException {
        proxy.moveToInsertRow();
        verify(delegate).moveToInsertRow();
    }

    @Test
    public void testMoveToCurrentRow() throws SQLException {
        proxy.moveToCurrentRow();
        verify(delegate).moveToCurrentRow();
    }

    // getObject with Map
    @Test
    public void testGetObjectByIndex_WithMap() throws SQLException {
        Map<String, Class<?>> typeMap = new HashMap<>();
        when(delegate.getObject(1, typeMap)).thenReturn("obj");
        assertEquals("obj", proxy.getObject(1, typeMap));
        verify(delegate).getObject(1, typeMap);
    }

    @Test
    public void testGetObjectByLabel_WithMap() throws SQLException {
        Map<String, Class<?>> typeMap = new HashMap<>();
        when(delegate.getObject("col", typeMap)).thenReturn("obj2");
        assertEquals("obj2", proxy.getObject("col", typeMap));
        verify(delegate).getObject("col", typeMap);
    }

    // Complex type getters
    @Test
    public void testGetBlob_ByIndex() throws SQLException {
        Blob blob = Mockito.mock(Blob.class);
        when(delegate.getBlob(1)).thenReturn(blob);
        assertSame(blob, proxy.getBlob(1));
    }

    @Test
    public void testGetClob_ByIndex() throws SQLException {
        Clob clob = Mockito.mock(Clob.class);
        when(delegate.getClob(1)).thenReturn(clob);
        assertSame(clob, proxy.getClob(1));
    }

    @Test
    public void testGetArray_ByIndex() throws SQLException {
        Array array = Mockito.mock(Array.class);
        when(delegate.getArray(1)).thenReturn(array);
        assertSame(array, proxy.getArray(1));
    }

    @Test
    public void testGetRef_ByIndex() throws SQLException {
        Ref ref = Mockito.mock(Ref.class);
        when(delegate.getRef(1)).thenReturn(ref);
        assertSame(ref, proxy.getRef(1));
    }

    @Test
    public void testGetBlob_ByLabel() throws SQLException {
        Blob blob = Mockito.mock(Blob.class);
        when(delegate.getBlob("col")).thenReturn(blob);
        assertSame(blob, proxy.getBlob("col"));
    }

    @Test
    public void testGetClob_ByLabel() throws SQLException {
        Clob clob = Mockito.mock(Clob.class);
        when(delegate.getClob("col")).thenReturn(clob);
        assertSame(clob, proxy.getClob("col"));
    }

    @Test
    public void testGetArray_ByLabel() throws SQLException {
        Array array = Mockito.mock(Array.class);
        when(delegate.getArray("col")).thenReturn(array);
        assertSame(array, proxy.getArray("col"));
    }

    @Test
    public void testGetRef_ByLabel() throws SQLException {
        Ref ref = Mockito.mock(Ref.class);
        when(delegate.getRef("col")).thenReturn(ref);
        assertSame(ref, proxy.getRef("col"));
    }

    // Date/Time with Calendar
    @Test
    public void testGetDate_ByIndex_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Date date = new Date(System.currentTimeMillis());
        when(delegate.getDate(1, cal)).thenReturn(date);
        assertEquals(date, proxy.getDate(1, cal));
    }

    @Test
    public void testGetDate_ByLabel_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Date date = new Date(System.currentTimeMillis());
        when(delegate.getDate("col", cal)).thenReturn(date);
        assertEquals(date, proxy.getDate("col", cal));
    }

    @Test
    public void testGetTime_ByIndex_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Time time = new Time(System.currentTimeMillis());
        when(delegate.getTime(1, cal)).thenReturn(time);
        assertEquals(time, proxy.getTime(1, cal));
    }

    @Test
    public void testGetTime_ByLabel_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Time time = new Time(System.currentTimeMillis());
        when(delegate.getTime("col", cal)).thenReturn(time);
        assertEquals(time, proxy.getTime("col", cal));
    }

    @Test
    public void testGetTimestamp_ByIndex_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(delegate.getTimestamp(1, cal)).thenReturn(ts);
        assertEquals(ts, proxy.getTimestamp(1, cal));
    }

    @Test
    public void testGetTimestamp_ByLabel_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(delegate.getTimestamp("col", cal)).thenReturn(ts);
        assertEquals(ts, proxy.getTimestamp("col", cal));
    }

    @Test
    public void testGetURL_ByIndex() throws Exception {
        URL url = new URL("https://example.com");
        when(delegate.getURL(1)).thenReturn(url);
        assertEquals(url, proxy.getURL(1));
    }

    @Test
    public void testGetURL_ByLabel() throws Exception {
        URL url = new URL("https://example.com");
        when(delegate.getURL("col")).thenReturn(url);
        assertEquals(url, proxy.getURL("col"));
    }

    @Test
    public void testUpdateRef_ByIndex() throws SQLException {
        Ref ref = Mockito.mock(Ref.class);
        proxy.updateRef(1, ref);
        verify(delegate).updateRef(1, ref);
    }

    @Test
    public void testUpdateRef_ByLabel() throws SQLException {
        Ref ref = Mockito.mock(Ref.class);
        proxy.updateRef("col", ref);
        verify(delegate).updateRef("col", ref);
    }

    @Test
    public void testUpdateBlob_ByIndex_Blob() throws SQLException {
        Blob blob = Mockito.mock(Blob.class);
        proxy.updateBlob(1, blob);
        verify(delegate).updateBlob(1, blob);
    }

    @Test
    public void testUpdateBlob_ByLabel_Blob() throws SQLException {
        Blob blob = Mockito.mock(Blob.class);
        proxy.updateBlob("col", blob);
        verify(delegate).updateBlob("col", blob);
    }

    @Test
    public void testUpdateClob_ByIndex_Clob() throws SQLException {
        Clob clob = Mockito.mock(Clob.class);
        proxy.updateClob(1, clob);
        verify(delegate).updateClob(1, clob);
    }

    @Test
    public void testUpdateClob_ByLabel_Clob() throws SQLException {
        Clob clob = Mockito.mock(Clob.class);
        proxy.updateClob("col", clob);
        verify(delegate).updateClob("col", clob);
    }

    @Test
    public void testUpdateArray_ByIndex() throws SQLException {
        Array arr = Mockito.mock(Array.class);
        proxy.updateArray(1, arr);
        verify(delegate).updateArray(1, arr);
    }

    @Test
    public void testUpdateArray_ByLabel() throws SQLException {
        Array arr = Mockito.mock(Array.class);
        proxy.updateArray("col", arr);
        verify(delegate).updateArray("col", arr);
    }

    @Test
    public void testGetRowId_ByIndex() throws SQLException {
        RowId rowId = Mockito.mock(RowId.class);
        when(delegate.getRowId(1)).thenReturn(rowId);
        assertSame(rowId, proxy.getRowId(1));
    }

    @Test
    public void testGetRowId_ByLabel() throws SQLException {
        RowId rowId = Mockito.mock(RowId.class);
        when(delegate.getRowId("col")).thenReturn(rowId);
        assertSame(rowId, proxy.getRowId("col"));
    }

    @Test
    public void testUpdateRowId_ByIndex() throws SQLException {
        RowId rowId = Mockito.mock(RowId.class);
        proxy.updateRowId(1, rowId);
        verify(delegate).updateRowId(1, rowId);
    }

    @Test
    public void testUpdateRowId_ByLabel() throws SQLException {
        RowId rowId = Mockito.mock(RowId.class);
        proxy.updateRowId("col", rowId);
        verify(delegate).updateRowId("col", rowId);
    }

    @Test
    public void testUpdateNString_ByIndex() throws SQLException {
        proxy.updateNString(1, "nstring");
        verify(delegate).updateNString(1, "nstring");
    }

    @Test
    public void testUpdateNString_ByLabel() throws SQLException {
        proxy.updateNString("col", "nstring");
        verify(delegate).updateNString("col", "nstring");
    }

    @Test
    public void testUpdateNClob_ByIndex_NClob() throws SQLException {
        NClob nclob = Mockito.mock(NClob.class);
        proxy.updateNClob(1, nclob);
        verify(delegate).updateNClob(1, nclob);
    }

    @Test
    public void testUpdateNClob_ByLabel_NClob() throws SQLException {
        NClob nclob = Mockito.mock(NClob.class);
        proxy.updateNClob("col", nclob);
        verify(delegate).updateNClob("col", nclob);
    }

    @Test
    public void testGetNClob_ByIndex() throws SQLException {
        NClob nclob = Mockito.mock(NClob.class);
        when(delegate.getNClob(1)).thenReturn(nclob);
        assertSame(nclob, proxy.getNClob(1));
    }

    @Test
    public void testGetNClob_ByLabel() throws SQLException {
        NClob nclob = Mockito.mock(NClob.class);
        when(delegate.getNClob("col")).thenReturn(nclob);
        assertSame(nclob, proxy.getNClob("col"));
    }

    @Test
    public void testGetSQLXML_ByIndex() throws SQLException {
        SQLXML xml = Mockito.mock(SQLXML.class);
        when(delegate.getSQLXML(1)).thenReturn(xml);
        assertSame(xml, proxy.getSQLXML(1));
    }

    @Test
    public void testGetSQLXML_ByLabel() throws SQLException {
        SQLXML xml = Mockito.mock(SQLXML.class);
        when(delegate.getSQLXML("col")).thenReturn(xml);
        assertSame(xml, proxy.getSQLXML("col"));
    }

    @Test
    public void testUpdateSQLXML_ByIndex() throws SQLException {
        SQLXML xml = Mockito.mock(SQLXML.class);
        proxy.updateSQLXML(1, xml);
        verify(delegate).updateSQLXML(1, xml);
    }

    @Test
    public void testUpdateSQLXML_ByLabel() throws SQLException {
        SQLXML xml = Mockito.mock(SQLXML.class);
        proxy.updateSQLXML("col", xml);
        verify(delegate).updateSQLXML("col", xml);
    }

    @Test
    public void testGetNString_ByIndex() throws SQLException {
        when(delegate.getNString(1)).thenReturn("ns");
        assertEquals("ns", proxy.getNString(1));
    }

    @Test
    public void testGetNString_ByLabel() throws SQLException {
        when(delegate.getNString("col")).thenReturn("ns2");
        assertEquals("ns2", proxy.getNString("col"));
    }

    @Test
    public void testGetNCharacterStream_ByIndex() throws SQLException {
        Reader reader = new StringReader("text");
        when(delegate.getNCharacterStream(1)).thenReturn(reader);
        assertSame(reader, proxy.getNCharacterStream(1));
    }

    @Test
    public void testGetNCharacterStream_ByLabel() throws SQLException {
        Reader reader = new StringReader("text");
        when(delegate.getNCharacterStream("col")).thenReturn(reader);
        assertSame(reader, proxy.getNCharacterStream("col"));
    }

    @Test
    public void testUpdateNCharacterStream_ByIndex_WithLength() throws SQLException {
        Reader reader = new StringReader("nchar");
        proxy.updateNCharacterStream(1, reader, 5L);
        verify(delegate).updateNCharacterStream(1, reader, 5L);
    }

    @Test
    public void testUpdateNCharacterStream_ByLabel_WithLength() throws SQLException {
        Reader reader = new StringReader("nchar");
        proxy.updateNCharacterStream("col", reader, 5L);
        verify(delegate).updateNCharacterStream("col", reader, 5L);
    }

    @Test
    public void testUpdateAsciiStream_ByIndex_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1 });
        proxy.updateAsciiStream(1, is, 1L);
        verify(delegate).updateAsciiStream(1, is, 1L);
    }

    @Test
    public void testUpdateBinaryStream_ByIndex_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 2 });
        proxy.updateBinaryStream(1, is, 1L);
        verify(delegate).updateBinaryStream(1, is, 1L);
    }

    @Test
    public void testUpdateCharacterStream_ByIndex_WithLength() throws SQLException {
        Reader reader = new StringReader("x");
        proxy.updateCharacterStream(1, reader, 1L);
        verify(delegate).updateCharacterStream(1, reader, 1L);
    }

    @Test
    public void testUpdateAsciiStream_ByLabel_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 3 });
        proxy.updateAsciiStream("col", is, 1L);
        verify(delegate).updateAsciiStream("col", is, 1L);
    }

    @Test
    public void testUpdateBinaryStream_ByLabel_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 4 });
        proxy.updateBinaryStream("col", is, 1L);
        verify(delegate).updateBinaryStream("col", is, 1L);
    }

    @Test
    public void testUpdateCharacterStream_ByLabel_WithLength() throws SQLException {
        Reader reader = new StringReader("y");
        proxy.updateCharacterStream("col", reader, 1L);
        verify(delegate).updateCharacterStream("col", reader, 1L);
    }

    @Test
    public void testUpdateBlob_ByIndex_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 5 });
        proxy.updateBlob(1, is, 1L);
        verify(delegate).updateBlob(1, is, 1L);
    }

    @Test
    public void testUpdateBlob_ByLabel_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 6 });
        proxy.updateBlob("col", is, 1L);
        verify(delegate).updateBlob("col", is, 1L);
    }

    @Test
    public void testUpdateClob_ByIndex_WithLength() throws SQLException {
        Reader reader = new StringReader("clob");
        proxy.updateClob(1, reader, 4L);
        verify(delegate).updateClob(1, reader, 4L);
    }

    @Test
    public void testUpdateClob_ByLabel_WithLength() throws SQLException {
        Reader reader = new StringReader("clob");
        proxy.updateClob("col", reader, 4L);
        verify(delegate).updateClob("col", reader, 4L);
    }

    @Test
    public void testUpdateNClob_ByIndex_WithLength() throws SQLException {
        Reader reader = new StringReader("nclob");
        proxy.updateNClob(1, reader, 5L);
        verify(delegate).updateNClob(1, reader, 5L);
    }

    @Test
    public void testUpdateNClob_ByLabel_WithLength() throws SQLException {
        Reader reader = new StringReader("nclob");
        proxy.updateNClob("col", reader, 5L);
        verify(delegate).updateNClob("col", reader, 5L);
    }

    @Test
    public void testUpdateNCharacterStream_ByIndex_NoLength() throws SQLException {
        Reader reader = new StringReader("nch");
        proxy.updateNCharacterStream(1, reader);
        verify(delegate).updateNCharacterStream(1, reader);
    }

    @Test
    public void testUpdateNCharacterStream_ByLabel_NoLength() throws SQLException {
        Reader reader = new StringReader("nch");
        proxy.updateNCharacterStream("col", reader);
        verify(delegate).updateNCharacterStream("col", reader);
    }

    @Test
    public void testUpdateAsciiStream_ByIndex_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 7 });
        proxy.updateAsciiStream(1, is);
        verify(delegate).updateAsciiStream(1, is);
    }

    @Test
    public void testUpdateBinaryStream_ByIndex_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 8 });
        proxy.updateBinaryStream(1, is);
        verify(delegate).updateBinaryStream(1, is);
    }

    @Test
    public void testUpdateCharacterStream_ByIndex_NoLength() throws SQLException {
        Reader reader = new StringReader("cs");
        proxy.updateCharacterStream(1, reader);
        verify(delegate).updateCharacterStream(1, reader);
    }

    @Test
    public void testUpdateAsciiStream_ByLabel_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 9 });
        proxy.updateAsciiStream("col", is);
        verify(delegate).updateAsciiStream("col", is);
    }

    @Test
    public void testUpdateBinaryStream_ByLabel_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 10 });
        proxy.updateBinaryStream("col", is);
        verify(delegate).updateBinaryStream("col", is);
    }

    @Test
    public void testUpdateCharacterStream_ByLabel_NoLength() throws SQLException {
        Reader reader = new StringReader("cs");
        proxy.updateCharacterStream("col", reader);
        verify(delegate).updateCharacterStream("col", reader);
    }

    @Test
    public void testUpdateBlob_ByIndex_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 11 });
        proxy.updateBlob(1, is);
        verify(delegate).updateBlob(1, is);
    }

    @Test
    public void testUpdateBlob_ByLabel_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 12 });
        proxy.updateBlob("col", is);
        verify(delegate).updateBlob("col", is);
    }

    @Test
    public void testUpdateClob_ByIndex_NoLength() throws SQLException {
        Reader reader = new StringReader("cl");
        proxy.updateClob(1, reader);
        verify(delegate).updateClob(1, reader);
    }

    @Test
    public void testUpdateClob_ByLabel_NoLength() throws SQLException {
        Reader reader = new StringReader("cl");
        proxy.updateClob("col", reader);
        verify(delegate).updateClob("col", reader);
    }

    @Test
    public void testUpdateNClob_ByIndex_NoLength() throws SQLException {
        Reader reader = new StringReader("ncl");
        proxy.updateNClob(1, reader);
        verify(delegate).updateNClob(1, reader);
    }

    @Test
    public void testUpdateNClob_ByLabel_NoLength() throws SQLException {
        Reader reader = new StringReader("ncl");
        proxy.updateNClob("col", reader);
        verify(delegate).updateNClob("col", reader);
    }

    // deprecated getBigDecimal(String, int) - line 371
    @Test
    @SuppressWarnings("deprecation")
    public void testGetBigDecimalByLabel_WithScale() throws SQLException {
        final BigDecimal bd = new BigDecimal("99.12");
        when(delegate.getBigDecimal("price", 2)).thenReturn(bd);
        assertEquals(bd, proxy.getBigDecimal("price", 2));
        verify(delegate).getBigDecimal("price", 2);
    }

    // getObject(String) - second call uses cached getter (L547, L602)
    @Test
    public void testGetObjectByLabel_CachedGetter() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("name")).thenReturn(1);
        when(delegate.getObject(1)).thenReturn("Alice");
        // First call: populates columnGettersByLabel
        assertEquals("Alice", proxy.getObject("name"));
        // Second call: hits L547 (getter = columnGettersByLabel.get(columnLabel)) and L602 (return getter.apply(delegate))
        when(delegate.getObject(1)).thenReturn("Bob");
        assertEquals("Bob", proxy.getObject("name"));
    }

    // getObject(String) - ret instanceof java.sql.Date, metadata class is "java.sql.Timestamp" (L587-589)
    @Test
    public void testGetObjectByLabel_SqlDate_TimestampMetadata() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnClassName(1)).thenReturn("java.sql.Timestamp");
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("created")).thenReturn(1);
        Date sqlDate = new Date(System.currentTimeMillis());
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(sqlDate);
        when(delegate.getTimestamp(1)).thenReturn(ts);
        Object result = proxy.getObject("created");
        assertEquals(ts, result);
    }

    // getObject(String) - ret instanceof java.sql.Date, metadata class is not "java.sql.Timestamp" (L590-592)
    @Test
    public void testGetObjectByLabel_SqlDate_DateMetadata() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnClassName(1)).thenReturn("java.sql.Date");
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("dob")).thenReturn(1);
        Date sqlDate = new Date(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(sqlDate);
        when(delegate.getDate(1)).thenReturn(sqlDate);
        Object result = proxy.getObject("dob");
        assertEquals(sqlDate, result);
        // Second call uses cached getter (rs -> rs.getDate(columnIndex))
        when(delegate.getDate(1)).thenReturn(sqlDate);
        assertEquals(sqlDate, proxy.getObject("dob"));
    }

    // getObject(int) - java.sql.Date, metadata re-fetched (L482), Timestamp metadata branch (L507-509)
    @Test
    public void testGetObjectByIndex_SqlDate_MetadataRefetched_TimestampBranch() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(delegate.getMetaData()).thenReturn(meta);
        // First call with column 1 (String) - initializes columnGetters, sets metadata in L466
        when(delegate.getObject(1)).thenReturn("init");
        assertEquals("init", proxy.getObject(1));
        // Second call with column 2 (java.sql.Date): columnGetters != null, metadata local var is null → L482
        java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
        java.sql.Timestamp ts = new java.sql.Timestamp(System.currentTimeMillis());
        when(delegate.getObject(2)).thenReturn(sqlDate);
        when(delegate.getTimestamp(2)).thenReturn(ts);
        Object result = proxy.getObject(2);
        assertEquals(ts, result);
    }

    // getObject(int) - java.sql.Date, metadata re-fetched (L482), non-Timestamp metadata → L511
    @Test
    public void testGetObjectByIndex_SqlDate_MetadataRefetched_DateBranch() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnClassName(2)).thenReturn("java.sql.Date");
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(1)).thenReturn("init");
        assertEquals("init", proxy.getObject(1));
        java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
        when(delegate.getObject(2)).thenReturn(sqlDate);
        Object result = proxy.getObject(2);
        assertEquals(sqlDate, result);
    }

    // getObject(int) - Other object type → L513-514 (else: ColumnGetter.GET_OBJECT)
    @Test
    public void testGetObjectByIndex_OtherObject_GetObjectGetter() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(1)).thenReturn("init");
        assertEquals("init", proxy.getObject(1));
        // java.util.Date is not String/Number/Timestamp/Boolean/oracle/java.sql.Date → else branch
        java.util.Date utilDate = new java.util.Date();
        when(delegate.getObject(2)).thenReturn(utilDate);
        Object result = proxy.getObject(2);
        assertEquals(utilDate, result);
    }

    // getObject(String) - second column with java.sql.Date, metadata re-fetched (L563)
    @Test
    public void testGetObjectByLabel_SecondColumn_MetadataRefetched() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("col1")).thenReturn(1);
        when(delegate.findColumn("col2")).thenReturn(2);
        when(delegate.getObject(1)).thenReturn("init");
        proxy.getObject("col1"); // initializes columnGettersByLabel
        // second column: col2 returns java.sql.Date; metadata is null locally → L563
        java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
        java.sql.Timestamp ts = new java.sql.Timestamp(System.currentTimeMillis());
        when(delegate.getObject(2)).thenReturn(sqlDate);
        when(delegate.getTimestamp(2)).thenReturn(ts);
        Object result = proxy.getObject("col2");
        assertEquals(ts, result);
    }

    // getObject(String) - Other object type → L594
    @Test
    public void testGetObjectByLabel_OtherObject_GetObjectGetter() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("col1")).thenReturn(1);
        when(delegate.findColumn("col2")).thenReturn(2);
        when(delegate.getObject(1)).thenReturn("init");
        proxy.getObject("col1");
        // java.util.Date is not String/Number/Timestamp/Boolean/oracle/java.sql.Date → L594
        java.util.Date utilDate = new java.util.Date();
        when(delegate.getObject(2)).thenReturn(utilDate);
        Object result = proxy.getObject("col2");
        assertEquals(utilDate, result);
    }
}
