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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ResultSetProxyObjectMappingTest extends ResultSetProxyTestSupport {

    @Test
    void testGetObjectByIndex_BlobColumn_FirstCallMaterializedToByteArray() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        Blob blob = Mockito.mock(Blob.class);
        when(blob.length()).thenReturn(5L);
        when(blob.getBytes(1, 5)).thenReturn(new byte[] { 1, 2, 3, 4, 5 });
        when(delegate.getObject(1)).thenReturn(blob);

        Object first = proxy.getObject(1);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, (byte[]) first);
        // The Blob handle is materialized then freed (caller never sees the raw Blob).
        Mockito.verify(blob).free();
    }

    @Test
    void testGetObjectByLabel_BlobColumn_MaterializedToByteArray() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("data")).thenReturn(1);
        Blob blob = Mockito.mock(Blob.class);
        when(blob.length()).thenReturn(3L);
        when(blob.getBytes(1, 3)).thenReturn(new byte[] { 7, 8, 9 });
        when(delegate.getObject(1)).thenReturn(blob);

        Object first = proxy.getObject("data");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] { 7, 8, 9 }, (byte[]) first);
        Mockito.verify(blob).free();
    }

    @Test
    void testGetObjectByIndex_BlobColumn_SizeOverflow_Throws() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        Blob blob = Mockito.mock(Blob.class);
        when(blob.length()).thenReturn(Integer.MAX_VALUE + 1L);
        when(delegate.getObject(1)).thenReturn(blob);

        assertThrows(SQLException.class, () -> proxy.getObject(1));
        // The Blob is still freed in the finally block even when the size guard trips.
        Mockito.verify(blob).free();
    }

    @Test
    void testGetObjectByLabel_BlobColumn_SizeOverflow_Throws() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("b")).thenReturn(1);
        Blob blob = Mockito.mock(Blob.class);
        when(blob.length()).thenReturn(Integer.MAX_VALUE + 1L);
        when(delegate.getObject(1)).thenReturn(blob);

        assertThrows(SQLException.class, () -> proxy.getObject("b"));
        Mockito.verify(blob).free();
    }

    @Test
    void testGetObjectByIndex_BlobReadAndFreeSameFailureDoesNotSelfSuppress() throws SQLException {
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        final Blob blob = Mockito.mock(Blob.class);
        final SQLException sharedFailure = new SQLException("shared read/free failure");
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(1)).thenReturn(blob);
        when(blob.length()).thenReturn(3L);
        when(blob.getBytes(1, 3)).thenThrow(sharedFailure);
        Mockito.doThrow(sharedFailure).when(blob).free();

        final SQLException thrown = assertThrows(SQLException.class, () -> proxy.getObject(1));

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void testGetObjectByIndex_BlobReadFailureRemainsPrimaryWhenFreeFails() throws SQLException {
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        final Blob blob = Mockito.mock(Blob.class);
        final SQLException readFailure = new SQLException("read failed");
        final SQLException freeFailure = new SQLException("free failed");
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(1)).thenReturn(blob);
        when(blob.length()).thenReturn(3L);
        when(blob.getBytes(1, 3)).thenThrow(readFailure);
        Mockito.doThrow(freeFailure).when(blob).free();

        final SQLException thrown = assertThrows(SQLException.class, () -> proxy.getObject(1));

        assertSame(readFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(freeFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void testGetObjectByIndex_BlobUncheckedFreeFailureIsSuppressed() throws SQLException {
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        final Blob blob = Mockito.mock(Blob.class);
        final SQLException readFailure = new SQLException("read failed");
        final AssertionError freeFailure = new AssertionError("unchecked free failed");
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(1)).thenReturn(blob);
        when(blob.length()).thenReturn(3L);
        when(blob.getBytes(1, 3)).thenThrow(readFailure);
        Mockito.doThrow(freeFailure).when(blob).free();

        final SQLException thrown = assertThrows(SQLException.class, () -> proxy.getObject(1));

        assertSame(readFailure, thrown);
        assertArrayEquals(new Throwable[] { freeFailure }, thrown.getSuppressed());
    }

    @Test
    void testGetObjectByIndex_BooleanValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(1)).thenReturn(Boolean.TRUE);
        assertTrue((Boolean) proxy.getObject(1));
    }

    @Test
    void testGetObjectByLabel_BooleanValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("flag")).thenReturn(1);
        when(delegate.getObject(1)).thenReturn(true);
        assertTrue((Boolean) proxy.getObject("flag"));
    }

    @Test
    void testGetObjectByIndex_CachedGetter() throws SQLException {
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

    @Test
    void testGetObjectByLabel_CachedGetter() throws SQLException {
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

    @Test
    void testGetObjectByIndex_ClobColumn_MaterializedToString() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        Clob clob = Mockito.mock(Clob.class);
        when(clob.length()).thenReturn(4L);
        when(clob.getSubString(1, 4)).thenReturn("text");
        when(delegate.getObject(1)).thenReturn(clob);

        assertEquals("text", proxy.getObject(1));
        // Clob handle is materialized to String then freed (caller never sees the raw Clob).
        Mockito.verify(clob).free();
    }

    @Test
    void testGetObjectByLabel_ClobColumn_MaterializedToString() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("c")).thenReturn(1);
        Clob clob = Mockito.mock(Clob.class);
        when(clob.length()).thenReturn(3L);
        when(clob.getSubString(1, 3)).thenReturn("abc");
        when(delegate.getObject(1)).thenReturn(clob);

        assertEquals("abc", proxy.getObject("c"));
        Mockito.verify(clob).free();
    }

    @Test
    void testGetObjectByIndex_ClobColumn_SizeOverflow_Throws() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        Clob clob = Mockito.mock(Clob.class);
        when(clob.length()).thenReturn(Integer.MAX_VALUE + 1L);
        when(delegate.getObject(1)).thenReturn(clob);

        assertThrows(SQLException.class, () -> proxy.getObject(1));
        Mockito.verify(clob).free();
    }

    @Test
    void testGetObjectByLabel_ClobColumn_SizeOverflow_Throws() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("c")).thenReturn(1);
        Clob clob = Mockito.mock(Clob.class);
        when(clob.length()).thenReturn(Integer.MAX_VALUE + 1L);
        when(delegate.getObject(1)).thenReturn(clob);

        assertThrows(SQLException.class, () -> proxy.getObject("c"));
        Mockito.verify(clob).free();
    }

    @Test
    void testGetObjectByLabel_ClobReadAndFreeSameFailureDoesNotSelfSuppress() throws SQLException {
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        final Clob clob = Mockito.mock(Clob.class);
        final SQLException sharedFailure = new SQLException("shared read/free failure");
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("c")).thenReturn(1);
        when(delegate.getObject(1)).thenReturn(clob);
        when(clob.length()).thenReturn(3L);
        when(clob.getSubString(1, 3)).thenThrow(sharedFailure);
        Mockito.doThrow(sharedFailure).when(clob).free();

        final SQLException thrown = assertThrows(SQLException.class, () -> proxy.getObject("c"));

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void testGetObjectByLabel_ClobReadFailureRemainsPrimaryWhenFreeFails() throws SQLException {
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        final Clob clob = Mockito.mock(Clob.class);
        final SQLException readFailure = new SQLException("read failed");
        final SQLException freeFailure = new SQLException("free failed");
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("c")).thenReturn(1);
        when(delegate.getObject(1)).thenReturn(clob);
        when(clob.length()).thenReturn(3L);
        when(clob.getSubString(1, 3)).thenThrow(readFailure);
        Mockito.doThrow(freeFailure).when(clob).free();

        final SQLException thrown = assertThrows(SQLException.class, () -> proxy.getObject("c"));

        assertSame(readFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(freeFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void testGetObjectByLabel_ClobUncheckedFreeFailureIsSuppressed() throws SQLException {
        final Clob clob = Mockito.mock(Clob.class);
        final SQLException readFailure = new SQLException("read failed");
        final IllegalStateException freeFailure = new IllegalStateException("unchecked free failed");
        when(delegate.findColumn("c")).thenReturn(1);
        when(delegate.getObject(1)).thenReturn(clob);
        when(clob.length()).thenReturn(3L);
        when(clob.getSubString(1, 3)).thenThrow(readFailure);
        Mockito.doThrow(freeFailure).when(clob).free();

        final SQLException thrown = assertThrows(SQLException.class, () -> proxy.getObject("c"));

        assertSame(readFailure, thrown);
        assertArrayEquals(new Throwable[] { freeFailure }, thrown.getSuppressed());
    }

    @Test
    void testGetObjectByIndex_FirstCall_NonSimpleType() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(delegate.getMetaData()).thenReturn(meta);
        java.util.Date utilDate = new java.util.Date();
        when(delegate.getObject(1)).thenReturn(utilDate);
        assertEquals(utilDate, proxy.getObject(1));
    }

    @Test
    void testGetObjectByIndex_InvalidHighIndexDelegatesSQLException() throws SQLException {
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        final SQLException invalidColumn = new SQLException("Invalid column index");
        when(meta.getColumnCount()).thenReturn(1);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(2)).thenThrow(invalidColumn);

        assertSame(invalidColumn, assertThrows(SQLException.class, () -> proxy.getObject(2)));
    }

    @Test
    void testGetObjectByIndex_JavaSqlDate_OracleTimestampMetadata_UsesGetTimestamp() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnClassName(1)).thenReturn("oracle.sql.TIMESTAMP");
        when(delegate.getMetaData()).thenReturn(meta);
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        java.sql.Timestamp ts = new java.sql.Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(date);
        when(delegate.getTimestamp(1)).thenReturn(ts);

        // Pre-fix this returned `date` (time truncated). Post-fix uses getTimestamp(1).
        assertEquals(ts, proxy.getObject(1));
    }

    @Test
    void testGetObjectByLabel_JavaSqlDate_OracleTimestampMetadata_UsesGetTimestamp() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnClassName(1)).thenReturn("oracle.sql.TIMESTAMP");
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("d")).thenReturn(1);
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        java.sql.Timestamp ts = new java.sql.Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(date);
        when(delegate.getTimestamp(1)).thenReturn(ts);

        assertEquals(ts, proxy.getObject("d"));
    }

    @Test
    void testGetObjectByLabel_NonDateValueDoesNotRequireMetadata() throws SQLException {
        final Object value = new Object();
        when(delegate.findColumn("opaque")).thenReturn(1);
        when(delegate.getObject(1)).thenReturn(value);
        when(delegate.getMetaData()).thenThrow(new SQLException("metadata unavailable"));

        assertSame(value, proxy.getObject("opaque"));
        verify(delegate, never()).getMetaData();
    }

    @Test
    void testGetObjectByIndex_NullValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(1)).thenReturn(null);
        Object result = proxy.getObject(1);
        assertNull(result);
    }

    @Test
    void testGetObjectByLabel_NullValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("col")).thenReturn(1);
        when(delegate.getObject(1)).thenReturn(null);
        assertNull(proxy.getObject("col"));
    }

    @Test
    void testGetObjectByLabel_NumberValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("num")).thenReturn(1);
        when(delegate.getObject(1)).thenReturn(42);
        assertEquals(42, proxy.getObject("num"));
    }

    @Test
    void testGetObjectByIndex_OracleDate_DateMetadata() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnClassName(1)).thenReturn("java.sql.Date");
        when(delegate.getMetaData()).thenReturn(meta);
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(new oracle.sql.DATE(date));
        when(delegate.getDate(1)).thenReturn(date);
        assertEquals(date, proxy.getObject(1));
    }

    @Test
    void testGetObjectByLabel_OracleDate_DateMetadata() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnClassName(1)).thenReturn("java.sql.Date");
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("col")).thenReturn(1);
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(new oracle.sql.DATE(date));
        when(delegate.getDate(1)).thenReturn(date);
        assertEquals(date, proxy.getObject("col"));
    }

    @Test
    void testGetObjectByIndex_OracleDate_TimestampMetadata() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnClassName(1)).thenReturn("java.sql.Timestamp");
        when(delegate.getMetaData()).thenReturn(meta);
        java.sql.Timestamp ts = new java.sql.Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(new oracle.sql.DATE(ts));
        when(delegate.getTimestamp(1)).thenReturn(ts);
        assertEquals(ts, proxy.getObject(1));
    }

    @Test
    void testGetObjectByLabel_OracleDate_TimestampMetadata() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnClassName(1)).thenReturn("java.sql.Timestamp");
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("col")).thenReturn(1);
        java.sql.Timestamp ts = new java.sql.Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(new oracle.sql.DATE(ts));
        when(delegate.getTimestamp(1)).thenReturn(ts);
        assertEquals(ts, proxy.getObject("col"));
    }

    @Test
    void testGetObjectByIndex_OracleTimestamp() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(delegate.getMetaData()).thenReturn(meta);
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(new oracle.sql.TIMESTAMP(now));
        assertEquals(now, proxy.getObject(1));
    }

    @Test
    void testGetObjectByLabel_OracleTimestamp() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("col")).thenReturn(1);
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(new oracle.sql.TIMESTAMP(now));
        assertEquals(now, proxy.getObject("col"));
    }

    @Test
    void testGetObjectByIndex_OracleTimestampLTZ() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(delegate.getMetaData()).thenReturn(meta);
        oracle.sql.TIMESTAMPLTZ oracleTstz = new oracle.sql.TIMESTAMPLTZ();
        java.sql.Timestamp converted = new java.sql.Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(oracleTstz);
        when(delegate.getTimestamp(1)).thenReturn(converted);
        assertEquals(converted, proxy.getObject(1));
    }

    @Test
    void testGetObjectByLabel_OracleTimestampLTZ() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(2);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("col")).thenReturn(1);
        oracle.sql.TIMESTAMPLTZ oracleTstz = new oracle.sql.TIMESTAMPLTZ();
        java.sql.Timestamp converted = new java.sql.Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(oracleTstz);
        when(delegate.getTimestamp(1)).thenReturn(converted);
        assertEquals(converted, proxy.getObject("col"));
    }

    @Test
    void testGetObjectByIndex_OtherObject_GetObjectGetter() throws SQLException {
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

    @Test
    void testGetObjectByLabel_OtherObject_GetObjectGetter() throws SQLException {
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

    @Test
    void testGetObjectByLabel_SecondColumn_MetadataRefetched() throws SQLException {
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

    @Test
    void testGetObjectByLabel_SimpleValueDoesNotReadMetadata() throws SQLException {
        when(delegate.findColumn("name")).thenReturn(1);
        when(delegate.getObject(1)).thenReturn("Ada");

        assertEquals("Ada", proxy.getObject("name"));
        Mockito.verify(delegate, Mockito.never()).getMetaData();
    }

    @Test
    void testGetObjectByLabel_SqlDate_DateMetadata() throws SQLException {
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

    @Test
    void testGetObjectByIndex_SqlDate_MetadataRefetched_DateBranch() throws SQLException {
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

    @Test
    void testGetObjectByIndex_SqlDate_MetadataRefetched_TimestampBranch() throws SQLException {
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

    @Test
    void testGetObjectByLabel_SqlDate_TimestampMetadata() throws SQLException {
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

    @Test
    void testGetObjectByIndex_StringValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(1)).thenReturn("hello");
        assertEquals("hello", proxy.getObject(1));
    }

    @Test
    void testGetObjectByLabel_StringValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("name")).thenReturn(2);
        when(delegate.getObject(2)).thenReturn("Alice");
        assertEquals("Alice", proxy.getObject("name"));
    }

    @Test
    void testGetObjectByIndex_TimestampValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(ts);
        assertEquals(ts, proxy.getObject(1));
    }

    @Test
    void testGetObjectByLabel_TimestampValue() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.findColumn("ts")).thenReturn(1);
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(delegate.getObject(1)).thenReturn(ts);
        assertEquals(ts, proxy.getObject("ts"));
    }

    @Test
    void testGetObjectByIndex_WithMap() throws SQLException {
        Map<String, Class<?>> typeMap = new HashMap<>();
        when(delegate.getObject(1, typeMap)).thenReturn("obj");
        assertEquals("obj", proxy.getObject(1, typeMap));
        verify(delegate).getObject(1, typeMap);
    }

    @Test
    void testGetObjectByLabel_WithMap() throws SQLException {
        Map<String, Class<?>> typeMap = new HashMap<>();
        when(delegate.getObject("col", typeMap)).thenReturn("obj2");
        assertEquals("obj2", proxy.getObject("col", typeMap));
        verify(delegate).getObject("col", typeMap);
    }

    @Test
    void testGetObjectByIndex_WithType() throws SQLException {
        when(delegate.getObject(1, String.class)).thenReturn("hello");
        assertEquals("hello", proxy.getObject(1, String.class));
        verify(delegate).getObject(1, String.class);
    }

    @Test
    void testGetObjectByLabel_WithType() throws SQLException {
        when(delegate.getObject("name", String.class)).thenReturn("world");
        assertEquals("world", proxy.getObject("name", String.class));
        verify(delegate).getObject("name", String.class);
    }

    @Test
    void testGetObjectByIndex_ZeroColumnIndex() throws SQLException {
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(3);
        when(delegate.getMetaData()).thenReturn(meta);
        when(delegate.getObject(0)).thenReturn("delegated");
        assertEquals("delegated", proxy.getObject(0));
        verify(delegate).getObject(0);
    }
}
