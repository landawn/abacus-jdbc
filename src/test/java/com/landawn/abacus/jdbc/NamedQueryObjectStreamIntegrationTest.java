package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.type.Type;

@Tag("integration")
@Tag("2025")
public class NamedQueryObjectStreamIntegrationTest extends TestBase {

    @ParameterizedTest(name = "setParameters_repeatedBinaryStream_replaysEveryOccurrence: {0}")
    @EnumSource(ObjectBinding.class)
    void setParameters_repeatedBinaryStream_replaysEveryOccurrence(final ObjectBinding binding) throws Exception {
        final byte[] expected = { 1, 2, 3 };
        final InputStream input = new ByteArrayInputStream(expected);

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:object_bytes");
             NamedQuery query = JdbcUtil.prepareNamedQuery(connection,
                     binding == ObjectBinding.REPEATED_PROPERTY_SINGLE_OCCURRENCE ? "select :payload" : "select :payload, :payload")) {
            switch (binding) {
                case OBJECT -> query.setObject("payload", input);
                case TYPED_OBJECT -> query.setObject("payload", input, Type.of(InputStream.class));
                case MAP -> query.setParameters(Map.of("payload", input));
                case BEAN -> query.setParameters(new StreamBean(input));
                case SELECTED_PROPERTY -> query.setParameters(new StreamBean(input), List.of("payload"));
                case REPEATED_PROPERTY, REPEATED_PROPERTY_SINGLE_OCCURRENCE -> query.setParameters(new StreamBean(input), List.of("payload", "payload"));
            }
            query.query((Jdbc.ResultExtractor<Void>) result -> {
                assertTrue(result.next());
                assertArrayEquals(expected, result.getBytes(1));
                if (binding != ObjectBinding.REPEATED_PROPERTY_SINGLE_OCCURRENCE) {
                    assertArrayEquals(expected, result.getBytes(2));
                }
                assertFalse(result.next());
                return null;
            });
        }
    }

    @ParameterizedTest(name = "setParameters_repeatedReader_replaysEveryOccurrence: {0}")
    @EnumSource(ObjectBinding.class)
    void setParameters_repeatedReader_replaysEveryOccurrence(final ObjectBinding binding) throws Exception {
        final Reader reader = new StringReader("a\u03b2c");

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:object_readers");
             NamedQuery query = JdbcUtil.prepareNamedQuery(connection,
                     binding == ObjectBinding.REPEATED_PROPERTY_SINGLE_OCCURRENCE ? "select :payload" : "select :payload, :payload")) {
            switch (binding) {
                case OBJECT -> query.setObject("payload", reader);
                case TYPED_OBJECT -> query.setObject("payload", reader, Type.of(Reader.class));
                case MAP -> query.setParameters(Map.of("payload", reader));
                case BEAN -> query.setParameters(new ReaderBean(reader));
                case SELECTED_PROPERTY -> query.setParameters(new ReaderBean(reader), List.of("payload"));
                case REPEATED_PROPERTY, REPEATED_PROPERTY_SINGLE_OCCURRENCE -> query.setParameters(new ReaderBean(reader), List.of("payload", "payload"));
            }
            query.query((Jdbc.ResultExtractor<Void>) result -> {
                assertTrue(result.next());
                assertEquals("a\u03b2c", result.getString(1));
                if (binding != ObjectBinding.REPEATED_PROPERTY_SINGLE_OCCURRENCE) {
                    assertEquals("a\u03b2c", result.getString(2));
                }
                assertFalse(result.next());
                return null;
            });
        }
    }

    // Named built-in types (BinaryStream, AsciiStream, ...) subclass InputStreamType/ReaderType and also consume the value.
    @ParameterizedTest(name = "setObject_namedStreamType_replaysEveryOccurrence: {0}")
    @ValueSource(strings = { "BinaryStream", "AsciiStream", "ClobAsciiStream", "CharacterStream", "NCharacterStream" })
    void setObject_namedStreamType_replaysEveryOccurrence(final String typeName) throws Exception {
        final boolean bytes = typeName.endsWith("Stream") && !typeName.contains("Character");
        final Object value = bytes ? new ByteArrayInputStream("abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) : new StringReader("abc");

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:named_stream_types");
             NamedQuery query = JdbcUtil.prepareNamedQuery(connection, "select :payload, :payload")) {
            query.setObject("payload", value, Type.of(typeName));
            query.query((Jdbc.ResultExtractor<Void>) result -> {
                assertTrue(result.next());
                assertEquals("abc", bytes ? new String(result.getBytes(1), java.nio.charset.StandardCharsets.US_ASCII) : result.getString(1));
                assertEquals("abc", bytes ? new String(result.getBytes(2), java.nio.charset.StandardCharsets.US_ASCII) : result.getString(2));
                assertFalse(result.next());
                return null;
            });
        }
    }

    @ParameterizedTest(name = "addBatchParameters_streamRows_usesIndependentBuffers: {0}")
    @EnumSource(BatchBinding.class)
    void addBatchParameters_streamRows_usesIndependentBuffers(final BatchBinding binding) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:object_stream_batch");
             Statement statement = connection.createStatement()) {
            statement.execute("create table items (a varbinary, b varbinary)");
            final InputStream first = new ByteArrayInputStream(new byte[] { 1 });
            final InputStream second = new ByteArrayInputStream(new byte[] { 2 });
            try (NamedQuery query = JdbcUtil.prepareNamedQuery(connection, "insert into items values (:payload, :payload)")) {
                query.addBatchParameters(binding == BatchBinding.MAP ? List.of(Map.of("payload", first), Map.of("payload", second))
                        : List.of(new StreamBean(first), new StreamBean(second)));
                query.batchUpdate();
            }
            try (ResultSet result = statement.executeQuery("select a, b from items order by a")) {
                for (byte expected : new byte[] { 1, 2 }) {
                    assertTrue(result.next());
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
                assertFalse(result.next());
                return null;
            });
        }
        assertEquals(3, input.available());
    }

    @ParameterizedTest(name = "setParameters_streamBean_readsEachPropertyOnce: {0}")
    @EnumSource(BeanBinding.class)
    void setParameters_streamBean_readsEachPropertyOnce(final BeanBinding binding) throws Exception {
        final CountingStreamBean first = new CountingStreamBean();
        final CountingStreamBean second = new CountingStreamBean();
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:counted_stream");
             Statement statement = connection.createStatement()) {
            statement.execute("create table items (a varbinary, b varbinary)");
            try (NamedQuery query = JdbcUtil.prepareNamedQuery(connection, "insert into items values (:payload, :payload)")) {
                if (binding == BeanBinding.BEAN) {
                    query.setParameters(first);
                    query.update();
                } else if (binding == BeanBinding.SELECTED_PROPERTIES) {
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
                assertEquals(binding == BeanBinding.BATCH ? 2 : 1, rowCount);
            }
        }
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

    private enum ObjectBinding {
        OBJECT, TYPED_OBJECT, MAP, BEAN, SELECTED_PROPERTY, REPEATED_PROPERTY, REPEATED_PROPERTY_SINGLE_OCCURRENCE
    }

    private enum BatchBinding {
        MAP, BEAN
    }

    private enum BeanBinding {
        BEAN, SELECTED_PROPERTIES, BATCH
    }
}
