package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import com.landawn.abacus.type.BinaryStreamType;
import com.landawn.abacus.type.CharacterStreamType;
import com.landawn.abacus.type.InputStreamType;
import com.landawn.abacus.type.ReaderType;
import com.landawn.abacus.type.Type;

@Tag("2025")
class JdbcUtilStreamBindingTest {

    @Test
    void builtInBindingsMatchTheirValueFamilyWithoutReading() throws Exception {
        final List<Type<?>> byteTypes = List.of(Type.of(InputStream.class), Type.of("BlobInputStream"), Type.of("AsciiStream"), Type.of("BinaryStream"),
                Type.of("ClobAsciiStream"));
        final List<Type<?>> characterTypes = List.of(Type.of(Reader.class), Type.of("ClobReader"), Type.of("NClobReader"), Type.of("CharacterStream"),
                Type.of("NCharacterStream"));

        try (TrackingInputStream input = new TrackingInputStream(new byte[] { 1, 2 }, false);
             TrackingReader reader = new TrackingReader("ab", false)) {
            for (final Type<?> type : byteTypes) {
                assertTrue(JdbcUtil.usesBuiltInStreamBinding(input, type), type.getClass().getSimpleName());
                assertFalse(JdbcUtil.usesBuiltInStreamBinding(reader, type));
                assertFalse(JdbcUtil.usesBuiltInStreamBinding(null, type));
            }
            for (final Type<?> type : characterTypes) {
                assertTrue(JdbcUtil.usesBuiltInStreamBinding(reader, type), type.getClass().getSimpleName());
                assertFalse(JdbcUtil.usesBuiltInStreamBinding(input, type));
                assertFalse(JdbcUtil.usesBuiltInStreamBinding(null, type));
            }
            assertFalse(JdbcUtil.usesBuiltInStreamBinding(input, null));
            assertFalse(JdbcUtil.usesBuiltInStreamBinding(reader, null));
            assertFalse(JdbcUtil.usesBuiltInStreamBinding(input, Type.of(String.class)));
            assertFalse(JdbcUtil.usesBuiltInStreamBinding(reader, Type.of(String.class)));
            assertFalse(JdbcUtil.usesBuiltInStreamBinding("ab", Type.of(Reader.class)));
            assertEquals(0, input.bulkReads + input.singleReads);
            assertEquals(0, reader.bulkReads + reader.singleReads);
        }
    }

    @Test
    void customSubclassesRetainTheirOwnBindingPolicy() {
        final InputStream input = new ByteArrayInputStream(new byte[] { 1 });
        final Reader reader = new StringReader("a");

        for (final Class<? extends Type<?>> baseType : List.of(InputStreamType.class, BinaryStreamType.class, ReaderType.class, CharacterStreamType.class)) {
            // Their constructors are package-private. Force an actual subclass: an inline mock would keep the built-in class.
            final Type<?> customType = mock(baseType, withSettings().mockMaker(MockMakers.SUBCLASS));
            assertTrue(baseType.isInstance(customType));
            assertNotEquals(baseType, customType.getClass());
            assertFalse(JdbcUtil.usesBuiltInStreamBinding(input, customType), baseType.getSimpleName());
            assertFalse(JdbcUtil.usesBuiltInStreamBinding(reader, customType), baseType.getSimpleName());
        }
    }

    @Test
    void boundedByteCopiesHaveIndependentCursorsAndLeaveTheSourceOpen() throws Exception {
        try (TrackingInputStream input = new TrackingInputStream(new byte[] { 1, 2, 3, 4, 5, 6 }, false)) {
            assertEquals(1, input.read());
            final Supplier<InputStream> copies = JdbcUtil.bufferForRepeatedBinding((InputStream) input, 3);
            assertEquals(5, input.read());

            try (InputStream first = copies.get();
                 InputStream second = copies.get()) {
                assertNotSame(first, second);
                assertNotSame(input, first);
                assertEquals(2, first.read());
                assertArrayEquals(new byte[] { 2, 3, 4 }, second.readAllBytes());
                assertArrayEquals(new byte[] { 3, 4 }, first.readAllBytes());
            }
            try (InputStream later = copies.get()) {
                assertArrayEquals(new byte[] { 2, 3, 4 }, later.readAllBytes());
            }
            assertFalse(input.closed);
            assertEquals(6, input.read());
        }
    }

    @Test
    void boundedReaderCopiesCountCharactersAndLeaveTheSourceOpen() throws Exception {
        try (TrackingReader reader = new TrackingReader("x\u03b2\uD83D\uDE00zTAIL", false)) {
            assertEquals('x', reader.read());
            final Supplier<Reader> copies = JdbcUtil.bufferForRepeatedBinding((Reader) reader, 3);
            assertEquals('z', reader.read());

            try (Reader first = copies.get();
                 Reader second = copies.get()) {
                assertNotSame(first, second);
                assertNotSame(reader, first);
                assertEquals('\u03b2', first.read());
                assertEquals("\u03b2\uD83D\uDE00", readString(second));
                assertEquals("\uD83D\uDE00", readString(first));
            }
            try (Reader later = copies.get()) {
                assertEquals("\u03b2\uD83D\uDE00", readString(later));
            }
            assertFalse(reader.closed);
            assertEquals('T', reader.read());
        }
    }

    @Test
    void zeroLengthCreatesEmptyCopiesWithoutReadingOrClosing() throws Exception {
        try (TrackingInputStream input = new TrackingInputStream(new byte[] { 1 }, false);
             TrackingReader reader = new TrackingReader("a", false)) {
            final Supplier<InputStream> byteCopies = JdbcUtil.bufferForRepeatedBinding((InputStream) input, 0);
            final Supplier<Reader> characterCopies = JdbcUtil.bufferForRepeatedBinding((Reader) reader, 0);
            try (InputStream bytes = byteCopies.get();
                 Reader characters = characterCopies.get()) {
                assertEquals(-1, bytes.read());
                assertEquals(-1, characters.read());
            }
            assertEquals(0, input.bulkReads + input.singleReads);
            assertEquals(0, reader.bulkReads + reader.singleReads);
            assertFalse(input.closed);
            assertFalse(reader.closed);
            assertEquals(1, input.read());
            assertEquals('a', reader.read());
        }
    }

    @Test
    void lengthBeyondEofCopiesAllRemainingDataAcrossMultipleChunks() throws Exception {
        final byte[] expectedBytes = new byte[20017];
        for (int i = 0; i < expectedBytes.length; i++) {
            expectedBytes[i] = (byte) (i % 251);
        }
        final String expectedText = "a\u03b2".repeat(10009);

        try (TrackingInputStream input = new TrackingInputStream(expectedBytes, false);
             TrackingReader reader = new TrackingReader(expectedText, false)) {
            final Supplier<InputStream> byteCopies = JdbcUtil.bufferForRepeatedBinding((InputStream) input, Long.MAX_VALUE);
            final Supplier<Reader> characterCopies = JdbcUtil.bufferForRepeatedBinding((Reader) reader, Long.MAX_VALUE);
            try (InputStream bytes = byteCopies.get();
                 Reader characters = characterCopies.get()) {
                assertArrayEquals(expectedBytes, bytes.readAllBytes());
                assertEquals(expectedText, readString(characters));
            }
            assertEquals(-1, input.read());
            assertEquals(-1, reader.read());
            assertFalse(input.closed);
            assertFalse(reader.closed);
        }
    }

    @Test
    void zeroBulkReadsFallBackToSingleReadsAndStopAtEof() throws Exception {
        final byte[] expectedBytes = { 0, 1, (byte) 255 };
        final String expectedText = "\u0000\u03b2z";

        try (TrackingInputStream input = new TrackingInputStream(expectedBytes, true);
             TrackingReader reader = new TrackingReader(expectedText, true)) {
            final Supplier<InputStream> byteCopies = JdbcUtil.bufferForRepeatedBinding((InputStream) input, Long.MAX_VALUE);
            final Supplier<Reader> characterCopies = JdbcUtil.bufferForRepeatedBinding((Reader) reader, Long.MAX_VALUE);
            try (InputStream bytes = byteCopies.get();
                 Reader characters = characterCopies.get()) {
                assertArrayEquals(expectedBytes, bytes.readAllBytes());
                assertEquals(expectedText, readString(characters));
            }
            assertEquals(expectedBytes.length + 1, input.singleReads);
            assertEquals(expectedText.length() + 1, reader.singleReads);
            assertFalse(input.closed);
            assertFalse(reader.closed);
        }
    }

    @Test
    void bulkAndFallbackIoFailuresPreserveTheCauseWithoutClosingTheSource() throws Exception {
        for (final boolean fallback : new boolean[] { false, true }) {
            final IOException inputFailure = new IOException("input failure, fallback=" + fallback);
            final InputStream input = mock(InputStream.class);
            when(input.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(call -> {
                if (fallback) {
                    return 0;
                }
                throw inputFailure;
            });
            when(input.read()).thenThrow(inputFailure);
            final SQLException inputThrown = assertThrows(SQLException.class, () -> JdbcUtil.bufferForRepeatedBinding(input, Long.MAX_VALUE));
            assertSame(inputFailure, inputThrown.getCause());
            verify(input, never()).close();

            final IOException readerFailure = new IOException("reader failure, fallback=" + fallback);
            final Reader reader = mock(Reader.class);
            when(reader.read(any(char[].class), anyInt(), anyInt())).thenAnswer(call -> {
                if (fallback) {
                    return 0;
                }
                throw readerFailure;
            });
            when(reader.read()).thenThrow(readerFailure);
            final SQLException readerThrown = assertThrows(SQLException.class, () -> JdbcUtil.bufferForRepeatedBinding(reader, Long.MAX_VALUE));
            assertSame(readerFailure, readerThrown.getCause());
            verify(reader, never()).close();
        }
    }

    private static String readString(final Reader reader) throws IOException {
        final StringWriter result = new StringWriter();
        reader.transferTo(result);
        return result.toString();
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private final boolean zeroBulkReads;
        private int bulkReads;
        private int singleReads;
        private boolean closed;

        TrackingInputStream(final byte[] bytes, final boolean zeroBulkReads) {
            super(bytes);
            this.zeroBulkReads = zeroBulkReads;
        }

        @Override
        public synchronized int read(final byte[] bytes, final int offset, final int length) {
            bulkReads++;
            if (zeroBulkReads) {
                // Bound the fixture so a broken fallback fails instead of leaving the test in a busy loop.
                assertTrue(bulkReads <= 32, "Buffering did not make progress after a zero-byte read");
                return 0;
            }
            return super.read(bytes, offset, length);
        }

        @Override
        public synchronized int read() {
            singleReads++;
            return super.read();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TrackingReader extends StringReader {
        private final boolean zeroBulkReads;
        private int bulkReads;
        private int singleReads;
        private boolean closed;

        TrackingReader(final String text, final boolean zeroBulkReads) {
            super(text);
            this.zeroBulkReads = zeroBulkReads;
        }

        @Override
        public int read(final char[] characters, final int offset, final int length) throws IOException {
            bulkReads++;
            if (zeroBulkReads) {
                assertTrue(bulkReads <= 32, "Buffering did not make progress after a zero-character read");
                return 0;
            }
            return super.read(characters, offset, length);
        }

        @Override
        public int read() throws IOException {
            singleReads++;
            return super.read();
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }
}
