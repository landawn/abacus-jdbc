package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.type.Type;

@Tag("2025")
class NamedQueryObjectStreamTest {

    @Test
    void conventionalObjectBindingReplaysByteStreams() {
        assertAll(IntStream.range(0, 7).mapToObj(variant -> () -> assertByteStreamBinding(variant)));
    }

    private static void assertByteStreamBinding(final int variant) throws Exception {
        final byte[] expected = { 1, 2, 3 };
        final InputStream input = new ByteArrayInputStream(expected);

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:object_bytes");
                NamedQuery query = JdbcUtil.prepareNamedQuery(connection, variant == 6 ? "select :payload" : "select :payload, :payload")) {
            switch (variant) {
                case 0 -> query.setObject("payload", input);
                case 1 -> query.setObject("payload", input, Type.of(InputStream.class));
                case 2 -> query.setParameters(Map.of("payload", input));
                case 3 -> query.setParameters(new StreamBean(input));
                case 4 -> query.setParameters(new StreamBean(input), List.of("payload"));
                default -> query.setParameters(new StreamBean(input), List.of("payload", "payload"));
            }
            query.query((Jdbc.ResultExtractor<Void>) result -> {
                result.next();
                assertArrayEquals(expected, result.getBytes(1));
                if (variant != 6) {
                    assertArrayEquals(expected, result.getBytes(2));
                }
                return null;
            });
        }
    }

    @Test
    void conventionalObjectBindingReplaysReaders() {
        assertAll(IntStream.range(0, 7).mapToObj(variant -> () -> assertReaderBinding(variant)));
    }

    private static void assertReaderBinding(final int variant) throws Exception {
        final Reader reader = new StringReader("a\u03b2c");

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:object_readers");
                NamedQuery query = JdbcUtil.prepareNamedQuery(connection, variant == 6 ? "select :payload" : "select :payload, :payload")) {
            switch (variant) {
                case 0 -> query.setObject("payload", reader);
                case 1 -> query.setObject("payload", reader, Type.of(Reader.class));
                case 2 -> query.setParameters(Map.of("payload", reader));
                case 3 -> query.setParameters(new ReaderBean(reader));
                case 4 -> query.setParameters(new ReaderBean(reader), List.of("payload"));
                default -> query.setParameters(new ReaderBean(reader), List.of("payload", "payload"));
            }
            query.query((Jdbc.ResultExtractor<Void>) result -> {
                result.next();
                assertEquals("a\u03b2c", result.getString(1));
                if (variant != 6) {
                    assertEquals("a\u03b2c", result.getString(2));
                }
                return null;
            });
        }
    }

    // Named built-in types (BinaryStream, AsciiStream, ...) subclass InputStreamType/ReaderType and also consume the value.
    @Test
    void namedBuiltInStreamTypesAreReplayed() {
        assertAll(java.util.stream.Stream.of("BinaryStream", "AsciiStream", "ClobAsciiStream", "CharacterStream", "NCharacterStream").map(typeName -> () -> {
            final boolean bytes = typeName.endsWith("Stream") && !typeName.contains("Character");
            final Object value = bytes ? new ByteArrayInputStream("abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) : new StringReader("abc");

            try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:named_stream_types");
                    NamedQuery query = JdbcUtil.prepareNamedQuery(connection, "select :payload, :payload")) {
                query.setObject("payload", value, Type.of(typeName));
                query.query((Jdbc.ResultExtractor<Void>) result -> {
                    result.next();
                    assertEquals("abc", bytes ? new String(result.getBytes(1), java.nio.charset.StandardCharsets.US_ASCII) : result.getString(1));
                    assertEquals("abc", bytes ? new String(result.getBytes(2), java.nio.charset.StandardCharsets.US_ASCII) : result.getString(2));
                    return null;
                });
            }
        }));
    }

    @Test
    void batchBindingCreatesNewReplayBuffersForEachRow() {
        assertAll(() -> assertBatchBinding(0), () -> assertBatchBinding(1));
    }

    private static void assertBatchBinding(final int variant) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:object_stream_batch"); Statement statement = connection.createStatement()) {
            statement.execute("create table items (a varbinary, b varbinary)");
            final InputStream first = new ByteArrayInputStream(new byte[] { 1 });
            final InputStream second = new ByteArrayInputStream(new byte[] { 2 });
            try (NamedQuery query = JdbcUtil.prepareNamedQuery(connection, "insert into items values (:payload, :payload)")) {
                query.addBatchParameters(variant == 0 ? List.of(Map.of("payload", first), Map.of("payload", second))
                        : List.of(new StreamBean(first), new StreamBean(second)));
                query.batchUpdate();
            }
            try (ResultSet result = statement.executeQuery("select a, b from items order by a")) {
                for (byte expected : new byte[] { 1, 2 }) {
                    result.next();
                    assertArrayEquals(new byte[] { expected }, result.getBytes(1));
                    assertArrayEquals(new byte[] { expected }, result.getBytes(2));
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void explicitCustomStreamTypesKeepTheirOwnBindingSemantics() throws Exception {
        final Type<InputStream> customType = mock(Type.class);
        final ByteArrayInputStream input = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        doAnswer(call -> {
            assertSame(input, call.getArgument(2));
            ((PreparedStatement) call.getArgument(0)).setString(call.getArgument(1), "custom");
            return null;
        }).when(customType).set(any(PreparedStatement.class), anyInt(), any(InputStream.class));
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:custom_stream");
                NamedQuery query = JdbcUtil.prepareNamedQuery(connection, "select :payload, :payload")) {
            query.setObject("payload", input, customType);
            query.query((Jdbc.ResultExtractor<Void>) result -> {
                result.next();
                assertEquals("custom", result.getString(1));
                assertEquals("custom", result.getString(2));
                return null;
            });
        }
        assertEquals(3, input.available());
    }

    @Test
    void replayDoesNotReopenBeanStreams() {
        assertAll(IntStream.range(0, 3).mapToObj(variant -> () -> {
            final CountingStreamBean first = new CountingStreamBean();
            final CountingStreamBean second = new CountingStreamBean();
            try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:counted_stream"); Statement statement = connection.createStatement()) {
                statement.execute("create table items (a varbinary, b varbinary)");
                try (NamedQuery query = JdbcUtil.prepareNamedQuery(connection, "insert into items values (:payload, :payload)")) {
                    if (variant == 0) {
                        query.setParameters(first);
                        query.update();
                    } else if (variant == 1) {
                        query.setParameters(first, List.of("payload", "payload"));
                        query.update();
                    } else {
                        query.addBatchParameters(List.of(first, second));
                        query.batchUpdate();
                        assertEquals(1, second.getterCalls);
                    }
                    assertEquals(1, first.getterCalls);
                }
                try (ResultSet result = statement.executeQuery("select a, b from items")) {
                    int rowCount = 0;
                    while (result.next()) {
                        rowCount++;
                        assertArrayEquals(new byte[] { 1, 2, 3 }, result.getBytes(1));
                        assertArrayEquals(new byte[] { 1, 2, 3 }, result.getBytes(2));
                    }
                    assertEquals(variant == 2 ? 2 : 1, rowCount);
                }
            }
        }));
    }

    public static final class CountingStreamBean {
        private int getterCalls;

        public InputStream getPayload() {
            getterCalls++;
            return new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        }

        public void setPayload(final InputStream payload) {
        }
    }

    public static final class StreamBean {
        private InputStream payload;

        public StreamBean() {
        }

        StreamBean(final InputStream payload) {
            this.payload = payload;
        }

        public InputStream getPayload() {
            return payload;
        }

        public void setPayload(final InputStream payload) {
            this.payload = payload;
        }
    }

    public static final class ReaderBean {
        private Reader payload;

        public ReaderBean() {
        }

        ReaderBean(final Reader payload) {
            this.payload = payload;
        }

        public Reader getPayload() {
            return payload;
        }

        public void setPayload(final Reader payload) {
            this.payload = payload;
        }
    }

}
