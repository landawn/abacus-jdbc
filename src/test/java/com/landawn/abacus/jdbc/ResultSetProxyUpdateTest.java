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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ResultSetProxyUpdateTest extends ResultSetProxyTestSupport {

    @Test
    void testCancelRowUpdates() throws SQLException {
        proxy.cancelRowUpdates();
        verify(delegate).cancelRowUpdates();
    }

    @Test
    void testDeleteRow() throws SQLException {
        proxy.deleteRow();
        verify(delegate).deleteRow();
    }

    @Test
    void testInsertRow() throws SQLException {
        proxy.insertRow();
        verify(delegate).insertRow();
    }

    @Test
    void testMoveToCurrentRow() throws SQLException {
        proxy.moveToCurrentRow();
        verify(delegate).moveToCurrentRow();
    }

    @Test
    void testMoveToInsertRow() throws SQLException {
        proxy.moveToInsertRow();
        verify(delegate).moveToInsertRow();
    }

    @Test
    void testRefreshRow() throws SQLException {
        proxy.refreshRow();
        verify(delegate).refreshRow();
    }

    @Test
    void testRowDeleted() throws SQLException {
        when(delegate.rowDeleted()).thenReturn(true);
        assertTrue(proxy.rowDeleted());
        verify(delegate).rowDeleted();
    }

    @Test
    void testRowInserted() throws SQLException {
        when(delegate.rowInserted()).thenReturn(false);
        assertFalse(proxy.rowInserted());
        verify(delegate).rowInserted();
    }

    @Test
    void testRowUpdated() throws SQLException {
        when(delegate.rowUpdated()).thenReturn(true);
        assertTrue(proxy.rowUpdated());
        verify(delegate).rowUpdated();
    }

    @Test
    void testUpdateArray_ByIndex() throws SQLException {
        Array arr = Mockito.mock(Array.class);
        proxy.updateArray(1, arr);
        verify(delegate).updateArray(1, arr);
    }

    @Test
    void testUpdateArray_ByLabel() throws SQLException {
        Array arr = Mockito.mock(Array.class);
        proxy.updateArray("col", arr);
        verify(delegate).updateArray("col", arr);
    }

    @Test
    void testUpdateAsciiStream_ByIndex_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 7 });
        proxy.updateAsciiStream(1, is);
        verify(delegate).updateAsciiStream(1, is);
    }

    @Test
    void testUpdateAsciiStream_ByLabel_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 9 });
        proxy.updateAsciiStream("col", is);
        verify(delegate).updateAsciiStream("col", is);
    }

    @Test
    void testUpdateAsciiStream_ByIndex_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1 });
        proxy.updateAsciiStream(1, is, 1L);
        verify(delegate).updateAsciiStream(1, is, 1L);
    }

    @Test
    void testUpdateAsciiStream_ByLabel_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 3 });
        proxy.updateAsciiStream("col", is, 1L);
        verify(delegate).updateAsciiStream("col", is, 1L);
    }

    @Test
    void testUpdateAsciiStream_ByIndex() throws SQLException {
        InputStream is = new ByteArrayInputStream("data".getBytes());
        proxy.updateAsciiStream(1, is, 4);
        verify(delegate).updateAsciiStream(1, is, 4);
    }

    @Test
    void testUpdateAsciiStream_ByLabel() throws SQLException {
        InputStream is = new ByteArrayInputStream("x".getBytes());
        proxy.updateAsciiStream("col", is, 1);
        verify(delegate).updateAsciiStream("col", is, 1);
    }

    @Test
    void testUpdateBigDecimal_ByIndex() throws SQLException {
        BigDecimal bd = new BigDecimal("99.99");
        proxy.updateBigDecimal(1, bd);
        verify(delegate).updateBigDecimal(1, bd);
    }

    @Test
    void testUpdateBigDecimal_ByLabel() throws SQLException {
        BigDecimal bd = new BigDecimal("1.5");
        proxy.updateBigDecimal("col", bd);
        verify(delegate).updateBigDecimal("col", bd);
    }

    @Test
    void testUpdateBinaryStream_ByIndex_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 8 });
        proxy.updateBinaryStream(1, is);
        verify(delegate).updateBinaryStream(1, is);
    }

    @Test
    void testUpdateBinaryStream_ByLabel_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 10 });
        proxy.updateBinaryStream("col", is);
        verify(delegate).updateBinaryStream("col", is);
    }

    @Test
    void testUpdateBinaryStream_ByIndex_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 2 });
        proxy.updateBinaryStream(1, is, 1L);
        verify(delegate).updateBinaryStream(1, is, 1L);
    }

    @Test
    void testUpdateBinaryStream_ByLabel_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 4 });
        proxy.updateBinaryStream("col", is, 1L);
        verify(delegate).updateBinaryStream("col", is, 1L);
    }

    @Test
    void testUpdateBinaryStream_ByIndex() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2 });
        proxy.updateBinaryStream(1, is, 2);
        verify(delegate).updateBinaryStream(1, is, 2);
    }

    @Test
    void testUpdateBinaryStream_ByLabel() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 5 });
        proxy.updateBinaryStream("col", is, 1);
        verify(delegate).updateBinaryStream("col", is, 1);
    }

    @Test
    void testUpdateBlob_ByIndex_Blob() throws SQLException {
        Blob blob = Mockito.mock(Blob.class);
        proxy.updateBlob(1, blob);
        verify(delegate).updateBlob(1, blob);
    }

    @Test
    void testUpdateBlob_ByLabel_Blob() throws SQLException {
        Blob blob = Mockito.mock(Blob.class);
        proxy.updateBlob("col", blob);
        verify(delegate).updateBlob("col", blob);
    }

    @Test
    void testUpdateBlob_ByIndex_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 11 });
        proxy.updateBlob(1, is);
        verify(delegate).updateBlob(1, is);
    }

    @Test
    void testUpdateBlob_ByLabel_NoLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 12 });
        proxy.updateBlob("col", is);
        verify(delegate).updateBlob("col", is);
    }

    @Test
    void testUpdateBlob_ByIndex_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 5 });
        proxy.updateBlob(1, is, 1L);
        verify(delegate).updateBlob(1, is, 1L);
    }

    @Test
    void testUpdateBlob_ByLabel_WithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 6 });
        proxy.updateBlob("col", is, 1L);
        verify(delegate).updateBlob("col", is, 1L);
    }

    @Test
    void testUpdateBoolean_ByIndex() throws SQLException {
        proxy.updateBoolean(1, true);
        verify(delegate).updateBoolean(1, true);
    }

    @Test
    void testUpdateBoolean_ByLabel() throws SQLException {
        proxy.updateBoolean("col", false);
        verify(delegate).updateBoolean("col", false);
    }

    @Test
    void testUpdateByte_ByIndex() throws SQLException {
        proxy.updateByte(1, (byte) 7);
        verify(delegate).updateByte(1, (byte) 7);
    }

    @Test
    void testUpdateByte_ByLabel() throws SQLException {
        proxy.updateByte("col", (byte) 3);
        verify(delegate).updateByte("col", (byte) 3);
    }

    @Test
    void testUpdateBytes_ByIndex() throws SQLException {
        byte[] data = { 1, 2, 3 };
        proxy.updateBytes(1, data);
        verify(delegate).updateBytes(1, data);
    }

    @Test
    void testUpdateBytes_ByLabel() throws SQLException {
        byte[] data = { 9, 8 };
        proxy.updateBytes("col", data);
        verify(delegate).updateBytes("col", data);
    }

    @Test
    void testUpdateCharacterStream_ByIndex_NoLength() throws SQLException {
        Reader reader = new StringReader("cs");
        proxy.updateCharacterStream(1, reader);
        verify(delegate).updateCharacterStream(1, reader);
    }

    @Test
    void testUpdateCharacterStream_ByLabel_NoLength() throws SQLException {
        Reader reader = new StringReader("cs");
        proxy.updateCharacterStream("col", reader);
        verify(delegate).updateCharacterStream("col", reader);
    }

    @Test
    void testUpdateCharacterStream_ByIndex_WithLength() throws SQLException {
        Reader reader = new StringReader("x");
        proxy.updateCharacterStream(1, reader, 1L);
        verify(delegate).updateCharacterStream(1, reader, 1L);
    }

    @Test
    void testUpdateCharacterStream_ByLabel_WithLength() throws SQLException {
        Reader reader = new StringReader("y");
        proxy.updateCharacterStream("col", reader, 1L);
        verify(delegate).updateCharacterStream("col", reader, 1L);
    }

    @Test
    void testUpdateCharacterStream_ByIndex() throws SQLException {
        Reader reader = new StringReader("data");
        proxy.updateCharacterStream(1, reader, 4);
        verify(delegate).updateCharacterStream(1, reader, 4);
    }

    @Test
    void testUpdateCharacterStream_ByLabel() throws SQLException {
        Reader reader = new StringReader("z");
        proxy.updateCharacterStream("col", reader, 1);
        verify(delegate).updateCharacterStream("col", reader, 1);
    }

    @Test
    void testUpdateClob_ByIndex_Clob() throws SQLException {
        Clob clob = Mockito.mock(Clob.class);
        proxy.updateClob(1, clob);
        verify(delegate).updateClob(1, clob);
    }

    @Test
    void testUpdateClob_ByLabel_Clob() throws SQLException {
        Clob clob = Mockito.mock(Clob.class);
        proxy.updateClob("col", clob);
        verify(delegate).updateClob("col", clob);
    }

    @Test
    void testUpdateClob_ByIndex_NoLength() throws SQLException {
        Reader reader = new StringReader("cl");
        proxy.updateClob(1, reader);
        verify(delegate).updateClob(1, reader);
    }

    @Test
    void testUpdateClob_ByLabel_NoLength() throws SQLException {
        Reader reader = new StringReader("cl");
        proxy.updateClob("col", reader);
        verify(delegate).updateClob("col", reader);
    }

    @Test
    void testUpdateClob_ByIndex_WithLength() throws SQLException {
        Reader reader = new StringReader("clob");
        proxy.updateClob(1, reader, 4L);
        verify(delegate).updateClob(1, reader, 4L);
    }

    @Test
    void testUpdateClob_ByLabel_WithLength() throws SQLException {
        Reader reader = new StringReader("clob");
        proxy.updateClob("col", reader, 4L);
        verify(delegate).updateClob("col", reader, 4L);
    }

    @Test
    void testUpdateDate_ByIndex() throws SQLException {
        Date date = new Date(System.currentTimeMillis());
        proxy.updateDate(1, date);
        verify(delegate).updateDate(1, date);
    }

    @Test
    void testUpdateDate_ByLabel() throws SQLException {
        Date date = new Date(System.currentTimeMillis());
        proxy.updateDate("col", date);
        verify(delegate).updateDate("col", date);
    }

    @Test
    void testUpdateDouble_ByIndex() throws SQLException {
        proxy.updateDouble(1, 3.14);
        verify(delegate).updateDouble(1, 3.14);
    }

    @Test
    void testUpdateDouble_ByLabel() throws SQLException {
        proxy.updateDouble("col", 4.0);
        verify(delegate).updateDouble("col", 4.0);
    }

    @Test
    void testUpdateFloat_ByIndex() throws SQLException {
        proxy.updateFloat(1, 1.5f);
        verify(delegate).updateFloat(1, 1.5f);
    }

    @Test
    void testUpdateFloat_ByLabel() throws SQLException {
        proxy.updateFloat("col", 2.0f);
        verify(delegate).updateFloat("col", 2.0f);
    }

    @Test
    void testUpdateInt_ByIndex() throws SQLException {
        proxy.updateInt(1, 42);
        verify(delegate).updateInt(1, 42);
    }

    @Test
    void testUpdateInt_ByLabel() throws SQLException {
        proxy.updateInt("col", 10);
        verify(delegate).updateInt("col", 10);
    }

    @Test
    void testUpdateLong_ByIndex() throws SQLException {
        proxy.updateLong(1, 999L);
        verify(delegate).updateLong(1, 999L);
    }

    @Test
    void testUpdateLong_ByLabel() throws SQLException {
        proxy.updateLong("col", 100L);
        verify(delegate).updateLong("col", 100L);
    }

    @Test
    void testUpdateNCharacterStream_ByIndex_NoLength() throws SQLException {
        Reader reader = new StringReader("nch");
        proxy.updateNCharacterStream(1, reader);
        verify(delegate).updateNCharacterStream(1, reader);
    }

    @Test
    void testUpdateNCharacterStream_ByLabel_NoLength() throws SQLException {
        Reader reader = new StringReader("nch");
        proxy.updateNCharacterStream("col", reader);
        verify(delegate).updateNCharacterStream("col", reader);
    }

    @Test
    void testUpdateNCharacterStream_ByIndex_WithLength() throws SQLException {
        Reader reader = new StringReader("nchar");
        proxy.updateNCharacterStream(1, reader, 5L);
        verify(delegate).updateNCharacterStream(1, reader, 5L);
    }

    @Test
    void testUpdateNCharacterStream_ByLabel_WithLength() throws SQLException {
        Reader reader = new StringReader("nchar");
        proxy.updateNCharacterStream("col", reader, 5L);
        verify(delegate).updateNCharacterStream("col", reader, 5L);
    }

    @Test
    void testUpdateNClob_ByIndex_NClob() throws SQLException {
        NClob nclob = Mockito.mock(NClob.class);
        proxy.updateNClob(1, nclob);
        verify(delegate).updateNClob(1, nclob);
    }

    @Test
    void testUpdateNClob_ByLabel_NClob() throws SQLException {
        NClob nclob = Mockito.mock(NClob.class);
        proxy.updateNClob("col", nclob);
        verify(delegate).updateNClob("col", nclob);
    }

    @Test
    void testUpdateNClob_ByIndex_NoLength() throws SQLException {
        Reader reader = new StringReader("ncl");
        proxy.updateNClob(1, reader);
        verify(delegate).updateNClob(1, reader);
    }

    @Test
    void testUpdateNClob_ByLabel_NoLength() throws SQLException {
        Reader reader = new StringReader("ncl");
        proxy.updateNClob("col", reader);
        verify(delegate).updateNClob("col", reader);
    }

    @Test
    void testUpdateNClob_ByIndex_WithLength() throws SQLException {
        Reader reader = new StringReader("nclob");
        proxy.updateNClob(1, reader, 5L);
        verify(delegate).updateNClob(1, reader, 5L);
    }

    @Test
    void testUpdateNClob_ByLabel_WithLength() throws SQLException {
        Reader reader = new StringReader("nclob");
        proxy.updateNClob("col", reader, 5L);
        verify(delegate).updateNClob("col", reader, 5L);
    }

    @Test
    void testUpdateNString_ByIndex() throws SQLException {
        proxy.updateNString(1, "nstring");
        verify(delegate).updateNString(1, "nstring");
    }

    @Test
    void testUpdateNString_ByLabel() throws SQLException {
        proxy.updateNString("col", "nstring");
        verify(delegate).updateNString("col", "nstring");
    }

    @Test
    void testUpdateNull_ByIndex() throws SQLException {
        proxy.updateNull(1);
        verify(delegate).updateNull(1);
    }

    @Test
    void testUpdateNull_ByLabel() throws SQLException {
        proxy.updateNull("col");
        verify(delegate).updateNull("col");
    }

    @Test
    void testUpdateObject_ByIndex_WithScale() throws SQLException {
        proxy.updateObject(1, "val", 2);
        verify(delegate).updateObject(1, "val", 2);
    }

    @Test
    void testUpdateObject_ByLabel_WithScale() throws SQLException {
        proxy.updateObject("col", "v", 2);
        verify(delegate).updateObject("col", "v", 2);
    }

    @Test
    void testUpdateObject_ByIndex() throws SQLException {
        proxy.updateObject(1, "val");
        verify(delegate).updateObject(1, "val");
    }

    @Test
    void testUpdateObject_ByLabel() throws SQLException {
        proxy.updateObject("col", "v");
        verify(delegate).updateObject("col", "v");
    }

    @Test
    void testUpdateRef_ByIndex() throws SQLException {
        Ref ref = Mockito.mock(Ref.class);
        proxy.updateRef(1, ref);
        verify(delegate).updateRef(1, ref);
    }

    @Test
    void testUpdateRef_ByLabel() throws SQLException {
        Ref ref = Mockito.mock(Ref.class);
        proxy.updateRef("col", ref);
        verify(delegate).updateRef("col", ref);
    }

    @Test
    void testUpdateRow() throws SQLException {
        proxy.updateRow();
        verify(delegate).updateRow();
    }

    @Test
    void testUpdateRowId_ByIndex() throws SQLException {
        RowId rowId = Mockito.mock(RowId.class);
        proxy.updateRowId(1, rowId);
        verify(delegate).updateRowId(1, rowId);
    }

    @Test
    void testUpdateRowId_ByLabel() throws SQLException {
        RowId rowId = Mockito.mock(RowId.class);
        proxy.updateRowId("col", rowId);
        verify(delegate).updateRowId("col", rowId);
    }

    @Test
    void testUpdateShort_ByIndex() throws SQLException {
        proxy.updateShort(1, (short) 100);
        verify(delegate).updateShort(1, (short) 100);
    }

    @Test
    void testUpdateShort_ByLabel() throws SQLException {
        proxy.updateShort("col", (short) 5);
        verify(delegate).updateShort("col", (short) 5);
    }

    @Test
    void testUpdateSQLXML_ByIndex() throws SQLException {
        SQLXML xml = Mockito.mock(SQLXML.class);
        proxy.updateSQLXML(1, xml);
        verify(delegate).updateSQLXML(1, xml);
    }

    @Test
    void testUpdateSQLXML_ByLabel() throws SQLException {
        SQLXML xml = Mockito.mock(SQLXML.class);
        proxy.updateSQLXML("col", xml);
        verify(delegate).updateSQLXML("col", xml);
    }

    @Test
    void testUpdateString_ByIndex() throws SQLException {
        proxy.updateString(1, "hello");
        verify(delegate).updateString(1, "hello");
    }

    @Test
    void testUpdateString_ByLabel() throws SQLException {
        proxy.updateString("col", "world");
        verify(delegate).updateString("col", "world");
    }

    @Test
    void testUpdateTime_ByIndex() throws SQLException {
        Time time = new Time(System.currentTimeMillis());
        proxy.updateTime(1, time);
        verify(delegate).updateTime(1, time);
    }

    @Test
    void testUpdateTime_ByLabel() throws SQLException {
        Time time = new Time(System.currentTimeMillis());
        proxy.updateTime("col", time);
        verify(delegate).updateTime("col", time);
    }

    @Test
    void testUpdateTimestamp_ByIndex() throws SQLException {
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        proxy.updateTimestamp(1, ts);
        verify(delegate).updateTimestamp(1, ts);
    }

    @Test
    void testUpdateTimestamp_ByLabel() throws SQLException {
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        proxy.updateTimestamp("col", ts);
        verify(delegate).updateTimestamp("col", ts);
    }
}
