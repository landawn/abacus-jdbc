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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ResultSetProxyColumnAccessTest extends ResultSetProxyTestSupport {

    @Test
    void proxyPreservesDriverNullArgumentExceptions() throws Exception {
        final ResultSet delegate = mock(ResultSet.class);
        final NullPointerException failure = new NullPointerException("driver requires a column label");
        when(delegate.getString((String) null)).thenThrow(failure);
        final ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertSame(failure, assertThrows(NullPointerException.class, () -> proxy.getString((String) null)));
        verify(delegate).getString((String) null);
    }

    @Test
    void testFindColumn() throws SQLException {
        when(delegate.findColumn("col")).thenReturn(3);
        assertEquals(3, proxy.findColumn("col"));
    }

    @Test
    void testGetArray_ByIndex() throws SQLException {
        Array array = Mockito.mock(Array.class);
        when(delegate.getArray(1)).thenReturn(array);
        assertSame(array, proxy.getArray(1));
    }

    @Test
    void testGetArray_ByLabel() throws SQLException {
        Array array = Mockito.mock(Array.class);
        when(delegate.getArray("col")).thenReturn(array);
        assertSame(array, proxy.getArray("col"));
    }

    @Test
    void testGetAsciiStreamByIndex() throws SQLException {
        InputStream is = new ByteArrayInputStream("data".getBytes());
        when(delegate.getAsciiStream(1)).thenReturn(is);
        assertSame(is, proxy.getAsciiStream(1));
    }

    @Test
    void testGetAsciiStreamByLabel() throws SQLException {
        InputStream is = new ByteArrayInputStream("text".getBytes());
        when(delegate.getAsciiStream("col")).thenReturn(is);
        assertSame(is, proxy.getAsciiStream("col"));
        verify(delegate).getAsciiStream("col");
    }

    @Test
    @SuppressWarnings("deprecation")
    void testGetBigDecimalByLabel_WithScale() throws SQLException {
        final BigDecimal bd = new BigDecimal("99.12");
        when(delegate.getBigDecimal("price", 2)).thenReturn(bd);
        assertEquals(bd, proxy.getBigDecimal("price", 2));
        verify(delegate).getBigDecimal("price", 2);
    }

    @Test
    void testGetBigDecimal_WithScale() throws SQLException {
        BigDecimal bd = new BigDecimal("123.45");
        when(delegate.getBigDecimal(1, 2)).thenReturn(bd);
        assertEquals(bd, proxy.getBigDecimal(1, 2));
        verify(delegate).getBigDecimal(1, 2);
    }

    @Test
    void testGetBigDecimalByLabel() throws SQLException {
        BigDecimal bd = new BigDecimal("123.45");
        when(delegate.getBigDecimal("bd")).thenReturn(bd);
        assertEquals(bd, proxy.getBigDecimal("bd"));
    }

    @Test
    void testGetBinaryStreamByIndex() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2 });
        when(delegate.getBinaryStream(1)).thenReturn(is);
        assertSame(is, proxy.getBinaryStream(1));
    }

    @Test
    void testGetBinaryStreamByLabel() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        when(delegate.getBinaryStream("col")).thenReturn(is);
        assertSame(is, proxy.getBinaryStream("col"));
        verify(delegate).getBinaryStream("col");
    }

    @Test
    void testGetBlob_ByIndex() throws SQLException {
        Blob blob = Mockito.mock(Blob.class);
        when(delegate.getBlob(1)).thenReturn(blob);
        assertSame(blob, proxy.getBlob(1));
    }

    @Test
    void testGetBlob_ByLabel() throws SQLException {
        Blob blob = Mockito.mock(Blob.class);
        when(delegate.getBlob("col")).thenReturn(blob);
        assertSame(blob, proxy.getBlob("col"));
    }

    @Test
    void testGetBooleanByLabel() throws SQLException {
        when(delegate.getBoolean("flag")).thenReturn(true);
        assertTrue(proxy.getBoolean("flag"));
    }

    @Test
    void testGetByteByLabel() throws SQLException {
        when(delegate.getByte("b")).thenReturn((byte) 7);
        assertEquals((byte) 7, proxy.getByte("b"));
    }

    @Test
    void testGetBytesByLabel() throws SQLException {
        byte[] data = { 1, 2, 3 };
        when(delegate.getBytes("bytes")).thenReturn(data);
        assertArrayEquals(data, proxy.getBytes("bytes"));
    }

    @Test
    void testGetCharacterStreamByIndex() throws SQLException {
        Reader reader = new StringReader("data");
        when(delegate.getCharacterStream(1)).thenReturn(reader);
        assertSame(reader, proxy.getCharacterStream(1));
    }

    @Test
    void testGetCharacterStreamByLabel() throws SQLException {
        Reader reader = new StringReader("data");
        when(delegate.getCharacterStream("col")).thenReturn(reader);
        assertSame(reader, proxy.getCharacterStream("col"));
    }

    @Test
    void testGetClob_ByIndex() throws SQLException {
        Clob clob = Mockito.mock(Clob.class);
        when(delegate.getClob(1)).thenReturn(clob);
        assertSame(clob, proxy.getClob(1));
    }

    @Test
    void testGetClob_ByLabel() throws SQLException {
        Clob clob = Mockito.mock(Clob.class);
        when(delegate.getClob("col")).thenReturn(clob);
        assertSame(clob, proxy.getClob("col"));
    }

    @Test
    void testGetDate_ByIndex_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Date date = new Date(System.currentTimeMillis());
        when(delegate.getDate(1, cal)).thenReturn(date);
        assertEquals(date, proxy.getDate(1, cal));
    }

    @Test
    void testGetDate_ByLabel_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Date date = new Date(System.currentTimeMillis());
        when(delegate.getDate("col", cal)).thenReturn(date);
        assertEquals(date, proxy.getDate("col", cal));
    }

    @Test
    void testGetDateByLabel() throws SQLException {
        Date date = new Date(System.currentTimeMillis());
        when(delegate.getDate("dt")).thenReturn(date);
        assertEquals(date, proxy.getDate("dt"));
    }

    @Test
    void testGetDoubleByLabel() throws SQLException {
        when(delegate.getDouble("d")).thenReturn(3.14);
        assertEquals(3.14, proxy.getDouble("d"), 0.001);
    }

    @Test
    void testGetFloatByLabel() throws SQLException {
        when(delegate.getFloat("f")).thenReturn(1.5f);
        assertEquals(1.5f, proxy.getFloat("f"), 0.001f);
    }

    @Test
    void testGetIntByLabel() throws SQLException {
        when(delegate.getInt("id")).thenReturn(42);
        assertEquals(42, proxy.getInt("id"));
    }

    @Test
    void testGetLongByLabel() throws SQLException {
        when(delegate.getLong("lng")).thenReturn(1000L);
        assertEquals(1000L, proxy.getLong("lng"));
    }

    @Test
    void testGetNCharacterStream_ByIndex() throws SQLException {
        Reader reader = new StringReader("text");
        when(delegate.getNCharacterStream(1)).thenReturn(reader);
        assertSame(reader, proxy.getNCharacterStream(1));
    }

    @Test
    void testGetNCharacterStream_ByLabel() throws SQLException {
        Reader reader = new StringReader("text");
        when(delegate.getNCharacterStream("col")).thenReturn(reader);
        assertSame(reader, proxy.getNCharacterStream("col"));
    }

    @Test
    void testGetNClob_ByIndex() throws SQLException {
        NClob nclob = Mockito.mock(NClob.class);
        when(delegate.getNClob(1)).thenReturn(nclob);
        assertSame(nclob, proxy.getNClob(1));
    }

    @Test
    void testGetNClob_ByLabel() throws SQLException {
        NClob nclob = Mockito.mock(NClob.class);
        when(delegate.getNClob("col")).thenReturn(nclob);
        assertSame(nclob, proxy.getNClob("col"));
    }

    @Test
    void testGetNString_ByIndex() throws SQLException {
        when(delegate.getNString(1)).thenReturn("ns");
        assertEquals("ns", proxy.getNString(1));
    }

    @Test
    void testGetNString_ByLabel() throws SQLException {
        when(delegate.getNString("col")).thenReturn("ns2");
        assertEquals("ns2", proxy.getNString("col"));
    }

    @Test
    void testGetRef_ByIndex() throws SQLException {
        Ref ref = Mockito.mock(Ref.class);
        when(delegate.getRef(1)).thenReturn(ref);
        assertSame(ref, proxy.getRef(1));
    }

    @Test
    void testGetRef_ByLabel() throws SQLException {
        Ref ref = Mockito.mock(Ref.class);
        when(delegate.getRef("col")).thenReturn(ref);
        assertSame(ref, proxy.getRef("col"));
    }

    @Test
    void testGetRowId_ByIndex() throws SQLException {
        RowId rowId = Mockito.mock(RowId.class);
        when(delegate.getRowId(1)).thenReturn(rowId);
        assertSame(rowId, proxy.getRowId(1));
    }

    @Test
    void testGetRowId_ByLabel() throws SQLException {
        RowId rowId = Mockito.mock(RowId.class);
        when(delegate.getRowId("col")).thenReturn(rowId);
        assertSame(rowId, proxy.getRowId("col"));
    }

    @Test
    void testGetShortByLabel() throws SQLException {
        when(delegate.getShort("s")).thenReturn((short) 100);
        assertEquals((short) 100, proxy.getShort("s"));
    }

    @Test
    void testGetSQLXML_ByIndex() throws SQLException {
        SQLXML xml = Mockito.mock(SQLXML.class);
        when(delegate.getSQLXML(1)).thenReturn(xml);
        assertSame(xml, proxy.getSQLXML(1));
    }

    @Test
    void testGetSQLXML_ByLabel() throws SQLException {
        SQLXML xml = Mockito.mock(SQLXML.class);
        when(delegate.getSQLXML("col")).thenReturn(xml);
        assertSame(xml, proxy.getSQLXML("col"));
    }

    @Test
    void testGetStringByLabel() throws SQLException {
        when(delegate.getString("col")).thenReturn("value");
        assertEquals("value", proxy.getString("col"));
    }

    @Test
    void testGetTime_ByIndex_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Time time = new Time(System.currentTimeMillis());
        when(delegate.getTime(1, cal)).thenReturn(time);
        assertEquals(time, proxy.getTime(1, cal));
    }

    @Test
    void testGetTime_ByLabel_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Time time = new Time(System.currentTimeMillis());
        when(delegate.getTime("col", cal)).thenReturn(time);
        assertEquals(time, proxy.getTime("col", cal));
    }

    @Test
    void testGetTimeByLabel() throws SQLException {
        Time time = new Time(System.currentTimeMillis());
        when(delegate.getTime("t")).thenReturn(time);
        assertEquals(time, proxy.getTime("t"));
    }

    @Test
    void testGetTimestamp_ByIndex_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(delegate.getTimestamp(1, cal)).thenReturn(ts);
        assertEquals(ts, proxy.getTimestamp(1, cal));
    }

    @Test
    void testGetTimestamp_ByLabel_WithCalendar() throws SQLException {
        Calendar cal = Calendar.getInstance();
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(delegate.getTimestamp("col", cal)).thenReturn(ts);
        assertEquals(ts, proxy.getTimestamp("col", cal));
    }

    @Test
    void testGetTimestampByLabel() throws SQLException {
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(delegate.getTimestamp("ts")).thenReturn(ts);
        assertEquals(ts, proxy.getTimestamp("ts"));
    }

    @Test
    void testGetUnicodeStreamByIndex() throws SQLException {
        InputStream is = new ByteArrayInputStream("data".getBytes());
        when(delegate.getUnicodeStream(1)).thenReturn(is);
        assertSame(is, proxy.getUnicodeStream(1));
        verify(delegate).getUnicodeStream(1);
    }

    @Test
    void testGetUnicodeStreamByLabel() throws SQLException {
        InputStream is = new ByteArrayInputStream("unicode".getBytes());
        when(delegate.getUnicodeStream("col")).thenReturn(is);
        assertSame(is, proxy.getUnicodeStream("col"));
        verify(delegate).getUnicodeStream("col");
    }

    @Test
    void testGetURL_ByIndex() throws Exception {
        URL url = new URL("https://example.com");
        when(delegate.getURL(1)).thenReturn(url);
        assertEquals(url, proxy.getURL(1));
    }

    @Test
    void testGetURL_ByLabel() throws Exception {
        URL url = new URL("https://example.com");
        when(delegate.getURL("col")).thenReturn(url);
        assertEquals(url, proxy.getURL("col"));
    }

    @Test
    void testWasNull() throws SQLException {
        when(delegate.wasNull()).thenReturn(true);
        assertTrue(proxy.wasNull());
        verify(delegate).wasNull();
    }
}
