package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.query.ParsedSql;

@Tag("2025")
class NamedQueryRepeatedStreamTest {

    @Test
    void repeatedByteStreamsBindCompleteValuesWithAnEagerDriver() throws Exception {
        final byte[] expected = "abc".getBytes(StandardCharsets.US_ASCII);

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:repeated_byte_stream")) {
            for (int occurrences : new int[] { 2, 5 }) {
                for (int variant = 0; variant < 6; variant++) {
                    final boolean hasLength = variant % 2 == 1;
                    final TrackingInputStream input = new TrackingInputStream(hasLength ? "abcTAIL" : "abc");
                    // H2 caches parameter types per SQL string; aliases survive ParsedSql comment removal.
                    try (NamedQuery query = JdbcUtil.prepareNamedQuery(connection, repeatedSelect(occurrences, variant))) {
                        bindBytes(query, input, variant, expected.length);
                        query.query((Jdbc.ResultExtractor<Void>) rs -> {
                            rs.next();
                            for (int column = 1; column <= occurrences; column++) {
                                assertArrayEquals(expected, rs.getBytes(column));
                            }
                            return null;
                        });
                    }
                    assertEquals(hasLength ? 4 : 0, input.available());
                    assertFalse(input.closed, "The caller owns the supplied stream");
                }
            }
        }
    }

    @Test
    void repeatedReadersBindCompleteValuesWithAnEagerDriver() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:repeated_reader")) {
            for (int occurrences : new int[] { 2, 5 }) {
                for (int variant = 0; variant < 8; variant++) {
                    final boolean hasLength = variant % 2 == 1;
                    final TrackingReader reader = new TrackingReader(hasLength ? "a\u03b2cTAIL" : "a\u03b2c");
                    try (NamedQuery query = JdbcUtil.prepareNamedQuery(connection, repeatedSelect(occurrences, variant))) {
                        bindCharacters(query, reader, variant, 3);
                        query.query((Jdbc.ResultExtractor<Void>) rs -> {
                            rs.next();
                            for (int column = 1; column <= occurrences; column++) {
                                assertEquals("a\u03b2c", rs.getString(column));
                            }
                            return null;
                        });
                    }
                    assertFalse(reader.closed, "The caller owns the supplied reader");
                    assertEquals(hasLength ? 'T' : -1, reader.read());
                }
            }
        }
    }

    @Test
    void repeatedValuesRemainIndependentWhenTheDriverReadsAfterBinding() throws Exception {
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

        try (NamedQuery query = new NamedQuery(statement, ParsedSql.parse(repeatedSelect(2)))) {
            query.setBinaryStream("payload", new ByteArrayInputStream(new byte[] { 1, 2, 3 }));
            query.setCharacterStream("payload", new StringReader("abc"));

            assertEquals(2, byteBindings.size());
            assertEquals(2, characterBindings.size());
            for (InputStream binding : byteBindings) {
                assertArrayEquals(new byte[] { 1, 2, 3 }, binding.readAllBytes());
            }
            for (Reader binding : characterBindings) {
                assertEquals("abc", readString(binding));
            }
        }
    }

    @Test
    void aSingleOccurrenceKeepsTheOriginalStreamsLazy() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final TrackingInputStream input = new TrackingInputStream("abc");
        final TrackingReader reader = new TrackingReader("abc");

        try (NamedQuery query = new NamedQuery(statement, ParsedSql.parse(repeatedSelect(1)))) {
            query.setBinaryStream("payload", input);
            query.setCharacterStream("payload", reader);

            verify(statement).setBinaryStream(1, input);
            verify(statement).setCharacterStream(1, reader);
            assertEquals(3, input.available());
            assertEquals('a', reader.read());
        }
        assertFalse(input.closed);
        assertFalse(reader.closed);
    }

    @Test
    void bufferingFailureClosesTheQueryWithoutBindingPartialValues() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final IOException failure = new IOException("stream failed");
        final InputStream input = new InputStream() {
            @Override
            public int read() throws IOException {
                throw failure;
            }
        };
        final NamedQuery query = new NamedQuery(statement, ParsedSql.parse(repeatedSelect(2)));

        final SQLException thrown = assertThrows(SQLException.class, () -> query.setBinaryStream("payload", input));

        assertSame(failure, thrown.getCause());
        verify(statement, never()).setBinaryStream(anyInt(), any(InputStream.class));
        verify(statement).close();
    }

    @Test
    void readerFailureKeepsItsCauseWhenQueryCleanupAlsoFails() throws Exception {
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
        final NamedQuery query = new NamedQuery(statement, ParsedSql.parse(repeatedSelect(2))).onClose(() -> {
            throw closeFailure;
        });

        final SQLException thrown = assertThrows(SQLException.class, () -> query.setCharacterStream("payload", reader));

        assertSame(readFailure, thrown.getCause());
        assertArrayEquals(new Throwable[] { closeFailure }, thrown.getSuppressed());
        verify(statement, never()).setCharacterStream(anyInt(), any(Reader.class));
        verify(statement).close();
    }

    @Test
    void invalidNamesAndLengthsAreRejectedBeforeReadingStreams() throws Exception {
        for (boolean invalidName : new boolean[] { false, true }) {
            final PreparedStatement statement = mock(PreparedStatement.class);
            final TrackingInputStream input = new TrackingInputStream("abc");
            final NamedQuery query = new NamedQuery(statement, ParsedSql.parse(repeatedSelect(2)));
            assertThrows(IllegalArgumentException.class, () -> query.setBinaryStream(invalidName ? "missing" : "payload", input, invalidName ? 3 : -1));
            assertEquals(3, input.available());
            assertFalse(input.closed);
            verify(statement, never()).setBinaryStream(anyInt(), any(InputStream.class), anyLong());
            verify(statement).close();
        }
    }

    @Test
    void aClosedQueryDoesNotConsumeRepeatedStreams() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final TrackingInputStream input = new TrackingInputStream("abc");
        final TrackingReader reader = new TrackingReader("abc");
        final NamedQuery query = new NamedQuery(statement, ParsedSql.parse(repeatedSelect(2)));
        query.close();

        assertThrows(IllegalStateException.class, () -> query.setBinaryStream("payload", input));
        assertThrows(IllegalStateException.class, () -> query.setCharacterStream("payload", reader));
        assertEquals(3, input.available());
        assertEquals('a', reader.read());
        verify(statement, never()).setBinaryStream(anyInt(), any(InputStream.class));
        verify(statement, never()).setCharacterStream(anyInt(), any(Reader.class));
    }

    @Test
    void longLengthsAreNotNarrowedWhenDuplicatingStreams() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final long length = (long) Integer.MAX_VALUE + 1;
        final List<InputStream> byteBindings = new ArrayList<>();
        final List<Reader> characterBindings = new ArrayList<>();
        doAnswer(call -> {
            assertEquals(length, (long) call.getArgument(2));
            byteBindings.add(call.getArgument(1));
            return null;
        }).when(statement).setBinaryStream(anyInt(), any(InputStream.class), anyLong());
        doAnswer(call -> {
            assertEquals(length, (long) call.getArgument(2));
            characterBindings.add(call.getArgument(1));
            return null;
        }).when(statement).setCharacterStream(anyInt(), any(Reader.class), anyLong());

        try (NamedQuery query = new NamedQuery(statement, ParsedSql.parse(repeatedSelect(2)))) {
            query.setBinaryStream("payload", new ByteArrayInputStream(new byte[] { 1, 2, 3 }), length);
            query.setCharacterStream("payload", new StringReader("abc"), length);
            assertEquals(2, byteBindings.size());
            assertEquals(2, characterBindings.size());
            for (InputStream binding : byteBindings) {
                assertArrayEquals(new byte[] { 1, 2, 3 }, binding.readAllBytes());
            }
            for (Reader binding : characterBindings) {
                assertEquals("abc", readString(binding));
            }
        }
    }

    @Test
    void zeroLengthAndNullStreamsDoNotConsumeAnyCallerData() throws Exception {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final TrackingInputStream input = new TrackingInputStream("abc");
        final TrackingReader reader = new TrackingReader("abc");
        doAnswer(call -> {
            final InputStream binding = call.getArgument(1);
            if (binding != null) {
                assertEquals(-1, binding.read());
            }
            return null;
        }).when(statement).setBinaryStream(anyInt(), any(), anyLong());
        doAnswer(call -> {
            final Reader binding = call.getArgument(1);
            if (binding != null) {
                assertEquals(-1, binding.read());
            }
            return null;
        }).when(statement).setCharacterStream(anyInt(), any(), anyLong());

        try (NamedQuery query = new NamedQuery(statement, ParsedSql.parse(repeatedSelect(2)))) {
            query.setBinaryStream("payload", input, 0);
            query.setCharacterStream("payload", reader, 0);
            query.setBinaryStream("payload", null, 0);
            query.setCharacterStream("payload", null, 0);
            assertEquals(3, input.available());
            assertEquals('a', reader.read());
            verify(statement).setBinaryStream(1, null, 0L);
            verify(statement).setBinaryStream(2, null, 0L);
            verify(statement).setCharacterStream(1, null, 0L);
            verify(statement).setCharacterStream(2, null, 0L);
        }
    }

    private static String repeatedSelect(final int occurrences) {
        return "select " + String.join(", ", java.util.Collections.nCopies(occurrences, ":payload"));
    }

    private static String repeatedSelect(final int occurrences, final int variant) {
        return "select " + java.util.stream.IntStream.rangeClosed(1, occurrences)
                .mapToObj(column -> ":payload as v" + variant + "_" + column)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static void bindBytes(final NamedQuery query, final InputStream input, final int variant, final long length) throws SQLException {
        switch (variant) {
            case 0 -> query.setAsciiStream("payload", input);
            case 1 -> query.setAsciiStream("payload", input, length);
            case 2 -> query.setBinaryStream("payload", input);
            case 3 -> query.setBinaryStream("payload", input, length);
            case 4 -> query.setBlob("payload", input);
            default -> query.setBlob("payload", input, length);
        }
    }

    private static void bindCharacters(final NamedQuery query, final Reader reader, final int variant, final long length) throws SQLException {
        switch (variant) {
            case 0 -> query.setCharacterStream("payload", reader);
            case 1 -> query.setCharacterStream("payload", reader, length);
            case 2 -> query.setNCharacterStream("payload", reader);
            case 3 -> query.setNCharacterStream("payload", reader, length);
            case 4 -> query.setClob("payload", reader);
            case 5 -> query.setClob("payload", reader, length);
            case 6 -> query.setNClob("payload", reader);
            default -> query.setNClob("payload", reader, length);
        }
    }

    private static String readString(final Reader reader) throws IOException {
        final StringBuilder result = new StringBuilder();
        for (int ch; (ch = reader.read()) != -1;) {
            result.append((char) ch);
        }
        return result.toString();
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        TrackingInputStream(final String text) {
            super(text.getBytes(StandardCharsets.US_ASCII));
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
}
