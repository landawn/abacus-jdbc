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
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;

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
}
