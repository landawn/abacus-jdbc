package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NamedQueryStreamBindingTest extends NamedQueryBindingTestSupport {

    @Test
    void testSetAsciiStream() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream("test".getBytes());

        NamedQuery result = namedQuery.setAsciiStream(paramName, value);

        verify(mockPreparedStatement).setAsciiStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetAsciiStreamWithLength() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream("test".getBytes());
        long length = 4L;

        NamedQuery result = namedQuery.setAsciiStream(paramName, value, length);

        verify(mockPreparedStatement).setAsciiStream(1, value, length);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setAsciiStream_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertStreamBinding(layout, (query, parameter, value) -> query.setAsciiStream(parameter, value),
                (statement, index, value) -> statement.setAsciiStream(index, value));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setAsciiStreamLong_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertStreamBinding(layout, (query, parameter, value) -> query.setAsciiStream(parameter, value, (long) PAYLOAD.length()),
                (statement, index, value) -> statement.setAsciiStream(index, value, eq((long) PAYLOAD.length())));
    }

    @Test
    void testSetBinaryStream() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });

        NamedQuery result = namedQuery.setBinaryStream(paramName, value);

        verify(mockPreparedStatement).setBinaryStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetBinaryStreamWithLength() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        long length = 3L;

        NamedQuery result = namedQuery.setBinaryStream(paramName, value, length);

        verify(mockPreparedStatement).setBinaryStream(1, value, length);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setBinaryStream_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertStreamBinding(layout, (query, parameter, value) -> query.setBinaryStream(parameter, value),
                (statement, index, value) -> statement.setBinaryStream(index, value));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setBinaryStreamLong_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertStreamBinding(layout, (query, parameter, value) -> query.setBinaryStream(parameter, value, (long) PAYLOAD.length()),
                (statement, index, value) -> statement.setBinaryStream(index, value, eq((long) PAYLOAD.length())));
    }

    @Test
    void testSetCharacterStream() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");

        NamedQuery result = namedQuery.setCharacterStream(paramName, value);

        verify(mockPreparedStatement).setCharacterStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetCharacterStreamWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");
        long length = 4L;

        NamedQuery result = namedQuery.setCharacterStream(paramName, value, length);

        verify(mockPreparedStatement).setCharacterStream(1, value, length);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setCharacterStream_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertReaderBinding(layout, (query, parameter, value) -> query.setCharacterStream(parameter, value),
                (statement, index, value) -> statement.setCharacterStream(index, value));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setCharacterStreamLong_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertReaderBinding(layout, (query, parameter, value) -> query.setCharacterStream(parameter, value, (long) READER_PAYLOAD.length()),
                (statement, index, value) -> statement.setCharacterStream(index, value, eq((long) READER_PAYLOAD.length())));
    }

    @Test
    void testSetNCharacterStream() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");

        NamedQuery result = namedQuery.setNCharacterStream(paramName, value);

        verify(mockPreparedStatement).setNCharacterStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetNCharacterStreamWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");
        long length = 12L;

        NamedQuery result = namedQuery.setNCharacterStream(paramName, value, length);

        verify(mockPreparedStatement).setNCharacterStream(1, value, length);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setNCharacterStream_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertReaderBinding(layout, (query, parameter, value) -> query.setNCharacterStream(parameter, value),
                (statement, index, value) -> statement.setNCharacterStream(index, value));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setNCharacterStreamLong_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertReaderBinding(layout, (query, parameter, value) -> query.setNCharacterStream(parameter, value, (long) READER_PAYLOAD.length()),
                (statement, index, value) -> statement.setNCharacterStream(index, value, eq((long) READER_PAYLOAD.length())));
    }

    @Test
    void testSetBlob() throws SQLException {
        String paramName = "param1";
        java.sql.Blob value = mock(java.sql.Blob.class);

        NamedQuery result = namedQuery.setBlob(paramName, value);

        verify(mockPreparedStatement).setBlob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetBlobInputStream() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });

        NamedQuery result = namedQuery.setBlob(paramName, value);

        verify(mockPreparedStatement).setBlob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetBlobInputStreamWithLength() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        long length = 3L;

        NamedQuery result = namedQuery.setBlob(paramName, value, length);

        verify(mockPreparedStatement).setBlob(1, value, length);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setBlob_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final Blob blob = mock(Blob.class);
        assertNamedBinding(layout, (query, parameter) -> query.setBlob(parameter, blob), (statement, index) -> statement.setBlob(index, blob));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setBlobStream_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertStreamBinding(layout, (query, parameter, value) -> query.setBlob(parameter, value), (statement, index, value) -> statement.setBlob(index, value));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setBlobStreamLong_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertStreamBinding(layout, (query, parameter, value) -> query.setBlob(parameter, value, (long) PAYLOAD.length()),
                (statement, index, value) -> statement.setBlob(index, value, eq((long) PAYLOAD.length())));
    }

    @Test
    void testSetClob() throws SQLException {
        String paramName = "param1";
        java.sql.Clob value = mock(java.sql.Clob.class);

        NamedQuery result = namedQuery.setClob(paramName, value);

        verify(mockPreparedStatement).setClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetClobReader() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");

        NamedQuery result = namedQuery.setClob(paramName, value);

        verify(mockPreparedStatement).setClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetClobReaderWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");
        long length = 4L;

        NamedQuery result = namedQuery.setClob(paramName, value, length);

        verify(mockPreparedStatement).setClob(1, value, length);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setClob_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final Clob clob = mock(Clob.class);
        assertNamedBinding(layout, (query, parameter) -> query.setClob(parameter, clob), (statement, index) -> statement.setClob(index, clob));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setClobReader_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertReaderBinding(layout, (query, parameter, value) -> query.setClob(parameter, value), (statement, index, value) -> statement.setClob(index, value));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setClobReaderLong_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertReaderBinding(layout, (query, parameter, value) -> query.setClob(parameter, value, (long) READER_PAYLOAD.length()),
                (statement, index, value) -> statement.setClob(index, value, eq((long) READER_PAYLOAD.length())));
    }

    @Test
    void testSetNClob() throws SQLException {
        String paramName = "param1";
        java.sql.NClob value = mock(java.sql.NClob.class);

        NamedQuery result = namedQuery.setNClob(paramName, value);

        verify(mockPreparedStatement).setNClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetNClobReader() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");

        NamedQuery result = namedQuery.setNClob(paramName, value);

        verify(mockPreparedStatement).setNClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetNClobReaderWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");
        long length = 12L;

        NamedQuery result = namedQuery.setNClob(paramName, value, length);

        verify(mockPreparedStatement).setNClob(1, value, length);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setNClob_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final NClob nclob = mock(NClob.class);
        assertNamedBinding(layout, (query, parameter) -> query.setNClob(parameter, nclob), (statement, index) -> statement.setNClob(index, nclob));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setNClobReader_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertReaderBinding(layout, (query, parameter, value) -> query.setNClob(parameter, value),
                (statement, index, value) -> statement.setNClob(index, value));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setNClobReaderLong_matchesNamedParameterPositions(final ParameterLayout layout) throws Exception {
        assertReaderBinding(layout, (query, parameter, value) -> query.setNClob(parameter, value, (long) READER_PAYLOAD.length()),
                (statement, index, value) -> statement.setNClob(index, value, eq((long) READER_PAYLOAD.length())));
    }
}
