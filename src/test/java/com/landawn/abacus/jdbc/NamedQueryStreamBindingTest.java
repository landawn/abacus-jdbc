package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.ImmutableList;

class NamedQueryStreamBindingTest extends NamedQueryTestSupport {

    @Test
    public void testSetAsciiStream() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream("test".getBytes());

        NamedQuery result = namedQuery.setAsciiStream(paramName, value);

        verify(mockPreparedStatement).setAsciiStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetAsciiStreamWithLength() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream("test".getBytes());
        long length = 4L;

        NamedQuery result = namedQuery.setAsciiStream(paramName, value, length);

        verify(mockPreparedStatement).setAsciiStream(1, value, length);
        assertSame(namedQuery, result);
    }

    // --- setAsciiStream (no-length overload): loop not-found, map not-found, 1-occ ---

    @Test
    public void testSetAsciiStream_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setAsciiStream("unknown", new ByteArrayInputStream(new byte[] {})));
    }

    @Test
    public void testSetAsciiStream_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setAsciiStream("unknown", new ByteArrayInputStream(new byte[] {})));
    }

    // --- setAsciiStream(InputStream) map happy paths ---

    @Test
    public void testSetAsciiStream_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setAsciiStream("c", new ByteArrayInputStream(new byte[] { 1 }));
        verify(mockPreparedStatement).setAsciiStream(eq(3), any(InputStream.class));
    }

    @Test
    public void testSetAsciiStream_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setAsciiStream("p", new ByteArrayInputStream(new byte[] { 1 }));
        verify(mockPreparedStatement, times(2)).setAsciiStream(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetAsciiStream_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setAsciiStream("p", new ByteArrayInputStream(new byte[] { 1 }));
        verify(mockPreparedStatement, times(3)).setAsciiStream(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetAsciiStream_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setAsciiStream("p", new ByteArrayInputStream(new byte[] { 1 }));
        verify(mockPreparedStatement, times(4)).setAsciiStream(anyInt(), any(InputStream.class));
    }

    // --- setAsciiStream(String, InputStream, long): all uncovered paths (L1991-2016) ---

    @Test
    public void testSetAsciiStreamLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setAsciiStream("unknown", new ByteArrayInputStream(new byte[] { 1 }), 1L));
    }

    @Test
    public void testSetAsciiStreamLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setAsciiStream("unknown", new ByteArrayInputStream(new byte[] { 1 }), 1L));
    }

    @Test
    public void testSetAsciiStreamLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setAsciiStream("a", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement).setAsciiStream(eq(1), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetAsciiStreamLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setAsciiStream("p", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement, times(2)).setAsciiStream(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetAsciiStreamLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setAsciiStream("p", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement, times(3)).setAsciiStream(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetAsciiStreamLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setAsciiStream("p", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement, times(4)).setAsciiStream(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetBinaryStream() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });

        NamedQuery result = namedQuery.setBinaryStream(paramName, value);

        verify(mockPreparedStatement).setBinaryStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBinaryStreamWithLength() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        long length = 3L;

        NamedQuery result = namedQuery.setBinaryStream(paramName, value, length);

        verify(mockPreparedStatement).setBinaryStream(1, value, length);
        assertSame(namedQuery, result);
    }

    // --- setBinaryStream (no-length overload): loop not-found, map not-found ---

    @Test
    public void testSetBinaryStream_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setBinaryStream("unknown", new ByteArrayInputStream(new byte[] {})));
    }

    @Test
    public void testSetBinaryStream_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setBinaryStream("unknown", new ByteArrayInputStream(new byte[] {})));
    }

    // --- setBinaryStream(InputStream) map happy paths ---

    @Test
    public void testSetBinaryStream_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBinaryStream("d", new ByteArrayInputStream(new byte[] { 2 }));
        verify(mockPreparedStatement).setBinaryStream(eq(4), any(InputStream.class));
    }

    @Test
    public void testSetBinaryStream_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBinaryStream("p", new ByteArrayInputStream(new byte[] { 2 }));
        verify(mockPreparedStatement, times(2)).setBinaryStream(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetBinaryStream_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBinaryStream("p", new ByteArrayInputStream(new byte[] { 2 }));
        verify(mockPreparedStatement, times(3)).setBinaryStream(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetBinaryStream_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBinaryStream("p", new ByteArrayInputStream(new byte[] { 2 }));
        verify(mockPreparedStatement, times(4)).setBinaryStream(anyInt(), any(InputStream.class));
    }

    // --- setBinaryStream(String, InputStream, long): all uncovered paths (L2137-2162) ---

    @Test
    public void testSetBinaryStreamLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setBinaryStream("unknown", new ByteArrayInputStream(new byte[] { 1 }), 1L));
    }

    @Test
    public void testSetBinaryStreamLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setBinaryStream("unknown", new ByteArrayInputStream(new byte[] { 1 }), 1L));
    }

    @Test
    public void testSetBinaryStreamLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBinaryStream("b", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement).setBinaryStream(eq(2), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetBinaryStreamLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBinaryStream("p", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement, times(2)).setBinaryStream(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetBinaryStreamLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBinaryStream("p", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement, times(3)).setBinaryStream(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetBinaryStreamLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBinaryStream("p", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement, times(4)).setBinaryStream(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetCharacterStream() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");

        NamedQuery result = namedQuery.setCharacterStream(paramName, value);

        verify(mockPreparedStatement).setCharacterStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetCharacterStreamWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");
        long length = 4L;

        NamedQuery result = namedQuery.setCharacterStream(paramName, value, length);

        verify(mockPreparedStatement).setCharacterStream(1, value, length);
        assertSame(namedQuery, result);
    }

    // --- setCharacterStream (no-length overload): loop not-found, map not-found ---

    @Test
    public void testSetCharacterStream_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setCharacterStream("unknown", new StringReader("x")));
    }

    @Test
    public void testSetCharacterStream_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setCharacterStream("unknown", new StringReader("x")));
    }

    // --- setCharacterStream(Reader) map happy paths ---

    @Test
    public void testSetCharacterStream_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setCharacterStream("e", new StringReader("hi"));
        verify(mockPreparedStatement).setCharacterStream(eq(5), any(Reader.class));
    }

    @Test
    public void testSetCharacterStream_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setCharacterStream("p", new StringReader("two"));
        verify(mockPreparedStatement, times(2)).setCharacterStream(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetCharacterStream_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setCharacterStream("p", new StringReader("three"));
        verify(mockPreparedStatement, times(3)).setCharacterStream(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetCharacterStream_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setCharacterStream("p", new StringReader("four"));
        verify(mockPreparedStatement, times(4)).setCharacterStream(anyInt(), any(Reader.class));
    }

    // --- setCharacterStream(String, Reader, long): all uncovered paths (L2283-2308) ---

    @Test
    public void testSetCharacterStreamLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setCharacterStream("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetCharacterStreamLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setCharacterStream("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetCharacterStreamLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setCharacterStream("c", new StringReader("x"), 1L);
        verify(mockPreparedStatement).setCharacterStream(eq(3), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetCharacterStreamLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setCharacterStream("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(2)).setCharacterStream(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetCharacterStreamLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setCharacterStream("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(3)).setCharacterStream(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetCharacterStreamLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setCharacterStream("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(4)).setCharacterStream(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNCharacterStream() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");

        NamedQuery result = namedQuery.setNCharacterStream(paramName, value);

        verify(mockPreparedStatement).setNCharacterStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNCharacterStreamWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");
        long length = 12L;

        NamedQuery result = namedQuery.setNCharacterStream(paramName, value, length);

        verify(mockPreparedStatement).setNCharacterStream(1, value, length);
        assertSame(namedQuery, result);
    }

    // --- setNCharacterStream (no-length overload): loop not-found, map not-found ---

    @Test
    public void testSetNCharacterStream_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setNCharacterStream("unknown", new StringReader("x")));
    }

    @Test
    public void testSetNCharacterStream_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNCharacterStream("unknown", new StringReader("x")));
    }

    // --- setNCharacterStream(Reader) map happy paths ---

    @Test
    public void testSetNCharacterStream_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNCharacterStream("a", new StringReader("x"));
        verify(mockPreparedStatement).setNCharacterStream(eq(1), any(Reader.class));
    }

    @Test
    public void testSetNCharacterStream_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"));
        verify(mockPreparedStatement, times(2)).setNCharacterStream(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetNCharacterStream_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"));
        verify(mockPreparedStatement, times(3)).setNCharacterStream(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetNCharacterStream_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"));
        verify(mockPreparedStatement, times(4)).setNCharacterStream(anyInt(), any(Reader.class));
    }

    // --- setNCharacterStream(Reader, long) all paths ---

    @Test
    public void testSetNCharacterStreamLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setNCharacterStream("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetNCharacterStreamLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNCharacterStream("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetNCharacterStreamLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNCharacterStream("b", new StringReader("hi"), 2L);
        verify(mockPreparedStatement).setNCharacterStream(eq(2), any(Reader.class), eq(2L));
    }

    @Test
    public void testSetNCharacterStreamLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(2)).setNCharacterStream(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNCharacterStreamLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(3)).setNCharacterStream(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNCharacterStreamLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(4)).setNCharacterStream(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetBlob() throws SQLException {
        String paramName = "param1";
        java.sql.Blob value = mock(java.sql.Blob.class);

        NamedQuery result = namedQuery.setBlob(paramName, value);

        verify(mockPreparedStatement).setBlob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBlobInputStream() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });

        NamedQuery result = namedQuery.setBlob(paramName, value);

        verify(mockPreparedStatement).setBlob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBlobInputStreamWithLength() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        long length = 3L;

        NamedQuery result = namedQuery.setBlob(paramName, value, length);

        verify(mockPreparedStatement).setBlob(1, value, length);
        assertSame(namedQuery, result);
    }

    // --- setBlob(Blob) all paths ---

    @Test
    public void testSetBlob_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setBlob("unknown", mock(Blob.class)));
    }

    @Test
    public void testSetBlob_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setBlob("unknown", mock(Blob.class)));
    }

    @Test
    public void testSetBlob_MapBranch_OneOccurrence() throws SQLException {
        Blob blob = mock(Blob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBlob("a", blob);
        verify(mockPreparedStatement).setBlob(1, blob);
    }

    @Test
    public void testSetBlob_MapBranch_TwoOccurrences() throws SQLException {
        Blob blob = mock(Blob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBlob("p", blob);
        verify(mockPreparedStatement, times(2)).setBlob(anyInt(), eq(blob));
    }

    @Test
    public void testSetBlob_MapBranch_ThreeOccurrences() throws SQLException {
        Blob blob = mock(Blob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBlob("p", blob);
        verify(mockPreparedStatement, times(3)).setBlob(anyInt(), eq(blob));
    }

    @Test
    public void testSetBlob_MapBranch_FourOccurrences() throws SQLException {
        Blob blob = mock(Blob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBlob("p", blob);
        verify(mockPreparedStatement, times(4)).setBlob(anyInt(), eq(blob));
    }

    // --- setBlob(InputStream) all paths ---

    @Test
    public void testSetBlobStream_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setBlob("unknown", new ByteArrayInputStream(new byte[] { 1 })));
    }

    @Test
    public void testSetBlobStream_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setBlob("unknown", new ByteArrayInputStream(new byte[] { 1 })));
    }

    @Test
    public void testSetBlobStream_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBlob("b", new ByteArrayInputStream(new byte[] { 1 }));
        verify(mockPreparedStatement).setBlob(eq(2), any(InputStream.class));
    }

    @Test
    public void testSetBlobStream_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[] { 1 }));
        verify(mockPreparedStatement, times(2)).setBlob(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetBlobStream_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[] { 1 }));
        verify(mockPreparedStatement, times(3)).setBlob(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetBlobStream_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[] { 1 }));
        verify(mockPreparedStatement, times(4)).setBlob(anyInt(), any(InputStream.class));
    }

    // --- setBlob(InputStream, long) all paths ---

    @Test
    public void testSetBlobStreamLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setBlob("unknown", new ByteArrayInputStream(new byte[] { 1 }), 1L));
    }

    @Test
    public void testSetBlobStreamLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setBlob("unknown", new ByteArrayInputStream(new byte[] { 1 }), 1L));
    }

    @Test
    public void testSetBlobStreamLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBlob("c", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement).setBlob(eq(3), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetBlobStreamLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement, times(2)).setBlob(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetBlobStreamLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement, times(3)).setBlob(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetBlobStreamLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[] { 1 }), 1L);
        verify(mockPreparedStatement, times(4)).setBlob(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetClob() throws SQLException {
        String paramName = "param1";
        java.sql.Clob value = mock(java.sql.Clob.class);

        NamedQuery result = namedQuery.setClob(paramName, value);

        verify(mockPreparedStatement).setClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetClobReader() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");

        NamedQuery result = namedQuery.setClob(paramName, value);

        verify(mockPreparedStatement).setClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetClobReaderWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");
        long length = 4L;

        NamedQuery result = namedQuery.setClob(paramName, value, length);

        verify(mockPreparedStatement).setClob(1, value, length);
        assertSame(namedQuery, result);
    }

    // --- setClob(Clob) all paths ---

    @Test
    public void testSetClob_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setClob("unknown", mock(Clob.class)));
    }

    @Test
    public void testSetClob_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setClob("unknown", mock(Clob.class)));
    }

    @Test
    public void testSetClob_MapBranch_OneOccurrence() throws SQLException {
        Clob clob = mock(Clob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setClob("d", clob);
        verify(mockPreparedStatement).setClob(4, clob);
    }

    @Test
    public void testSetClob_MapBranch_TwoOccurrences() throws SQLException {
        Clob clob = mock(Clob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setClob("p", clob);
        verify(mockPreparedStatement, times(2)).setClob(anyInt(), eq(clob));
    }

    @Test
    public void testSetClob_MapBranch_ThreeOccurrences() throws SQLException {
        Clob clob = mock(Clob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setClob("p", clob);
        verify(mockPreparedStatement, times(3)).setClob(anyInt(), eq(clob));
    }

    @Test
    public void testSetClob_MapBranch_FourOccurrences() throws SQLException {
        Clob clob = mock(Clob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setClob("p", clob);
        verify(mockPreparedStatement, times(4)).setClob(anyInt(), eq(clob));
    }

    // --- setClob(Reader) all paths ---

    @Test
    public void testSetClobReader_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setClob("unknown", new StringReader("x")));
    }

    @Test
    public void testSetClobReader_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setClob("unknown", new StringReader("x")));
    }

    @Test
    public void testSetClobReader_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setClob("e", new StringReader("text"));
        verify(mockPreparedStatement).setClob(eq(5), any(Reader.class));
    }

    @Test
    public void testSetClobReader_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setClob("p", new StringReader("text"));
        verify(mockPreparedStatement, times(2)).setClob(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetClobReader_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setClob("p", new StringReader("text"));
        verify(mockPreparedStatement, times(3)).setClob(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetClobReader_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setClob("p", new StringReader("text"));
        verify(mockPreparedStatement, times(4)).setClob(anyInt(), any(Reader.class));
    }

    // --- setClob(Reader, long) all paths ---

    @Test
    public void testSetClobReaderLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setClob("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetClobReaderLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setClob("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetClobReaderLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setClob("a", new StringReader("hi"), 2L);
        verify(mockPreparedStatement).setClob(eq(1), any(Reader.class), eq(2L));
    }

    @Test
    public void testSetClobReaderLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setClob("p", new StringReader("hi"), 2L);
        verify(mockPreparedStatement, times(2)).setClob(anyInt(), any(Reader.class), eq(2L));
    }

    @Test
    public void testSetClobReaderLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setClob("p", new StringReader("hi"), 2L);
        verify(mockPreparedStatement, times(3)).setClob(anyInt(), any(Reader.class), eq(2L));
    }

    @Test
    public void testSetClobReaderLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setClob("p", new StringReader("hi"), 2L);
        verify(mockPreparedStatement, times(4)).setClob(anyInt(), any(Reader.class), eq(2L));
    }

    @Test
    public void testSetNClob() throws SQLException {
        String paramName = "param1";
        java.sql.NClob value = mock(java.sql.NClob.class);

        NamedQuery result = namedQuery.setNClob(paramName, value);

        verify(mockPreparedStatement).setNClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNClobReader() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");

        NamedQuery result = namedQuery.setNClob(paramName, value);

        verify(mockPreparedStatement).setNClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNClobReaderWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");
        long length = 12L;

        NamedQuery result = namedQuery.setNClob(paramName, value, length);

        verify(mockPreparedStatement).setNClob(1, value, length);
        assertSame(namedQuery, result);
    }

    // --- setNClob(NClob) all paths ---

    @Test
    public void testSetNClob_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setNClob("unknown", mock(NClob.class)));
    }

    @Test
    public void testSetNClob_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNClob("unknown", mock(NClob.class)));
    }

    @Test
    public void testSetNClob_MapBranch_OneOccurrence() throws SQLException {
        NClob nclob = mock(NClob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNClob("b", nclob);
        verify(mockPreparedStatement).setNClob(2, nclob);
    }

    @Test
    public void testSetNClob_MapBranch_TwoOccurrences() throws SQLException {
        NClob nclob = mock(NClob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNClob("p", nclob);
        verify(mockPreparedStatement, times(2)).setNClob(anyInt(), eq(nclob));
    }

    @Test
    public void testSetNClob_MapBranch_ThreeOccurrences() throws SQLException {
        NClob nclob = mock(NClob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNClob("p", nclob);
        verify(mockPreparedStatement, times(3)).setNClob(anyInt(), eq(nclob));
    }

    @Test
    public void testSetNClob_MapBranch_FourOccurrences() throws SQLException {
        NClob nclob = mock(NClob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNClob("p", nclob);
        verify(mockPreparedStatement, times(4)).setNClob(anyInt(), eq(nclob));
    }

    // --- setNClob(Reader) all paths ---

    @Test
    public void testSetNClobReader_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setNClob("unknown", new StringReader("x")));
    }

    @Test
    public void testSetNClobReader_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNClob("unknown", new StringReader("x")));
    }

    @Test
    public void testSetNClobReader_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNClob("c", new StringReader("uni"));
        verify(mockPreparedStatement).setNClob(eq(3), any(Reader.class));
    }

    @Test
    public void testSetNClobReader_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNClob("p", new StringReader("uni"));
        verify(mockPreparedStatement, times(2)).setNClob(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetNClobReader_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNClob("p", new StringReader("uni"));
        verify(mockPreparedStatement, times(3)).setNClob(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetNClobReader_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNClob("p", new StringReader("uni"));
        verify(mockPreparedStatement, times(4)).setNClob(anyInt(), any(Reader.class));
    }

    // --- setNClob(Reader, long) all paths ---

    @Test
    public void testSetNClobReaderLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setNClob("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetNClobReaderLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNClob("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetNClobReaderLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNClob("d", new StringReader("x"), 1L);
        verify(mockPreparedStatement).setNClob(eq(4), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNClobReaderLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNClob("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(2)).setNClob(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNClobReaderLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNClob("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(3)).setNClob(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNClobReaderLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNClob("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(4)).setNClob(anyInt(), any(Reader.class), eq(1L));
    }
}
