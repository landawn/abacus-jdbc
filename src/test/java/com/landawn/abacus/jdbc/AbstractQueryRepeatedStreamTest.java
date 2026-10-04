package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.landawn.abacus.type.Type;

@Tag("2025")
class AbstractQueryRepeatedStreamTest {

    @Test
    void repeatedByteStreamIndicesBindCompleteValuesWithAnEagerDriver() throws Exception {
        final byte[] expected = { 1, 2, 3 };
        final TrackingInputStream input = new TrackingInputStream(expected);
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:indices_bytes");
                PreparedQuery query = JdbcUtil.prepareQuery(connection, "select ?, ?")) {
            query.setObjectForIndices(input, 2, 1, 2);
            query.query((Jdbc.ResultExtractor<Void>) result -> {
                assertTrue(result.next());
                assertArrayEquals(expected, result.getBytes(1));
                assertArrayEquals(expected, result.getBytes(2));
                return null;
            });
        }
        assertFalse(input.closed, "The caller owns the stream");
    }

    @Test
    void repeatedReaderIndicesBindCompleteValuesWithAnEagerDriver() throws Exception {
        final TrackingReader reader = new TrackingReader("a\u03b2c");
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:indices_characters");
                PreparedQuery query = JdbcUtil.prepareQuery(connection, "select ?, ?")) {
            query.setObjectForIndices(reader, 2, 1, 2);
            query.query((Jdbc.ResultExtractor<Void>) result -> {
                assertTrue(result.next());
                assertEquals("a\u03b2c", result.getString(1));
                assertEquals("a\u03b2c", result.getString(2));
                return null;
            });
        }
        assertFalse(reader.closed, "The caller owns the reader");
    }

    @Test
    void repeatedIndicesReceiveIndependentCursorsForDeferredDriverReads() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final List<InputStream> byteBindings = new ArrayList<>();
        final List<Reader> characterBindings = new ArrayList<>();
        doAnswer(call -> {
            byteBindings.add(call.getArgument(1));
            return null;
        }).when(statement).setBinaryStream(anyInt(), any(InputStream.class));
        doAnswer(call -> {
            characterBindings.add(call.getArgument(1));
            return null;
        }).when(statement).setCharacterStream(anyInt(), any(Reader.class));
        try (PreparedQuery query = new PreparedQuery(statement)) {
            query.setObjectForIndices(new ByteArrayInputStream(new byte[] { 1, 2, 3 }), 1, 2);
            query.setObjectForIndices(new StringReader("a\u03b2c"), 3, 4);
            assertEquals(2, byteBindings.size());
            assertEquals(2, characterBindings.size());
            for (InputStream binding : byteBindings) {
                assertArrayEquals(new byte[] { 1, 2, 3 }, binding.readAllBytes());
            }
            for (Reader binding : characterBindings) {
                assertEquals("a\u03b2c", readString(binding));
            }
        }
    }

    @Test
    void singleIndexKeepsTheOriginalStreamsLazy() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final TrackingInputStream input = new TrackingInputStream(new byte[] { 1, 2, 3 });
        final TrackingReader reader = new TrackingReader("abc");
        try (PreparedQuery query = new PreparedQuery(statement)) {
            query.setObjectForIndices(input, 1);
            query.setObjectForIndices(reader, 2);
            verify(statement).setBinaryStream(1, input);
            verify(statement).setCharacterStream(2, reader);
            assertEquals(3, input.available());
            assertEquals('a', reader.read());
        }
        assertFalse(input.closed);
        assertFalse(reader.closed);
    }

    @Test
    void allIndicesAreValidatedBeforeConsumingCallerData() throws Exception {
        for (int[] indices : new int[][] { null, {}, { 1, 0 }, { 1, -1 } }) {
            final PreparedStatement statement = mock(PreparedStatement.class);
            final TrackingInputStream input = new TrackingInputStream(new byte[] { 1, 2, 3 });
            final PreparedQuery query = new PreparedQuery(statement);
            assertThrows(IllegalArgumentException.class, () -> query.setObjectForIndices(input, indices));
            assertEquals(3, input.available());
            assertFalse(input.closed);
            verify(statement, never()).setBinaryStream(anyInt(), any(InputStream.class));
            verify(statement).close();
        }
    }

    @Test
    void closedQueryDoesNotConsumeRepeatedStreams() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final TrackingInputStream input = new TrackingInputStream(new byte[] { 1, 2, 3 });
        final TrackingReader reader = new TrackingReader("abc");
        final PreparedQuery query = new PreparedQuery(statement);
        query.close();
        assertThrows(IllegalStateException.class, () -> query.setObjectForIndices(input, 1, 2));
        assertThrows(IllegalStateException.class, () -> query.setObjectForIndices(reader, 1, 2));
        assertEquals(3, input.available());
        assertEquals('a', reader.read());
        verify(statement, never()).setBinaryStream(anyInt(), any(InputStream.class));
        verify(statement, never()).setCharacterStream(anyInt(), any(Reader.class));
    }

    @Test
    void bufferingFailureClosesQueryWithoutBindingPartialValues() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final IOException readFailure = new IOException("stream failed");
        final InputStream input = new InputStream() {
            @Override
            public int read() throws IOException {
                throw readFailure;
            }
        };
        final PreparedQuery query = new PreparedQuery(statement);
        final SQLException thrown = assertThrows(SQLException.class, () -> query.setObjectForIndices(input, 1, 2));
        assertSame(readFailure, thrown.getCause());
        verify(statement, never()).setBinaryStream(anyInt(), any(InputStream.class));
        verify(statement).close();
    }

    @Test
    void readerFailurePreservesItsCauseWhenCleanupAlsoFails() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final IOException readFailure = new IOException("reader failed");
        final IllegalStateException closeFailure = new IllegalStateException("close handler failed");
        final Reader reader = new Reader() {
            @Override
            public int read(final char[] chars, final int offset, final int length) throws IOException {
                throw readFailure;
            }

            @Override
            public void close() {
                throw new AssertionError("The caller owns the reader");
            }
        };
        final PreparedQuery query = new PreparedQuery(statement).onClose(() -> {
            throw closeFailure;
        });
        final SQLException thrown = assertThrows(SQLException.class, () -> query.setObjectForIndices(reader, 1, 2));
        assertSame(readFailure, thrown.getCause());
        assertArrayEquals(new Throwable[] { closeFailure }, thrown.getSuppressed());
        verify(statement, never()).setCharacterStream(anyInt(), any(Reader.class));
        verify(statement).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void customStreamTypeKeepsItsOwnBindingSemantics() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final Type<CustomInputStream> customType = mock(Type.class);
        final CustomInputStream input = new CustomInputStream();
        final PreparedQuery query = new PreparedQuery(statement);
        try (MockedStatic<Type> types = mockStatic(Type.class); query) {
            types.when(() -> Type.of(CustomInputStream.class)).thenReturn(customType);
            query.setObjectForIndices(input, 1, 2);
            verify(customType).set(statement, 1, input);
            verify(customType).set(statement, 2, input);
            assertEquals(3, input.available());
        }
    }

    private static String readString(final Reader reader) throws IOException {
        final StringBuilder result = new StringBuilder();
        for (int ch; (ch = reader.read()) != -1;) {
            result.append((char) ch);
        }
        return result.toString();
    }

    private static class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        TrackingInputStream(final byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TrackingReader extends StringReader {
        private boolean closed;

        TrackingReader(final String text) {
            super(text);
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }

    private static final class CustomInputStream extends ByteArrayInputStream {
        CustomInputStream() {
            super(new byte[] { 1, 2, 3 });
        }
    }
}
