package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.landawn.abacus.util.ImmutableList;

/** Shared parameter-position contracts for the NamedQuery binding families. */
abstract class NamedQueryBindingTestSupport extends NamedQueryTestSupport {

    protected static final String PAYLOAD = "payload";
    protected static final String READER_PAYLOAD = "text-π-水-🌱";

    protected void assertNamedBinding(final ParameterLayout layout, final NamedBinding binding, final JdbcBinding verification) throws SQLException {
        final NamedQuery query = createQueryWithParameters(layout.names);
        clearInvocations(mockPreparedStatement);

        if (layout.indexes.length == 0) {
            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> binding.bind(query, "missing_parameter"));
            assertTrue(failure.getMessage().contains("missing_parameter"));
            verify(mockPreparedStatement).close();
        } else {
            assertSame(query, binding.bind(query, "p"));
            final InOrder order = inOrder(mockPreparedStatement);
            for (final int index : layout.indexes) {
                verification.bind(order.verify(mockPreparedStatement), index);
            }
            order.verifyNoMoreInteractions();
        }

        verifyNoMoreInteractions(mockPreparedStatement);
    }

    protected void assertStreamBinding(final ParameterLayout layout, final NamedStreamBinding binding, final JdbcStreamBinding verification) throws Exception {
        final byte[] expected = PAYLOAD.getBytes(StandardCharsets.US_ASCII);
        final ArgumentCaptor<InputStream> streams = ArgumentCaptor.forClass(InputStream.class);
        try (InputStream input = new ByteArrayInputStream(expected)) {
            assertNamedBinding(layout, (query, name) -> binding.bind(query, name, input),
                    (statement, index) -> verification.bind(statement, eq(index), streams.capture()));
            for (final InputStream stream : streams.getAllValues()) {
                try (stream) {
                    assertArrayEquals(expected, stream.readAllBytes());
                }
            }
        }
    }

    protected void assertReaderBinding(final ParameterLayout layout, final NamedReaderBinding binding, final JdbcReaderBinding verification) throws Exception {
        final ArgumentCaptor<Reader> readers = ArgumentCaptor.forClass(Reader.class);
        try (Reader input = new StringReader(READER_PAYLOAD)) {
            assertNamedBinding(layout, (query, name) -> binding.bind(query, name, input),
                    (statement, index) -> verification.bind(statement, eq(index), readers.capture()));
            for (final Reader reader : readers.getAllValues()) {
                try (reader;
                     StringWriter output = new StringWriter()) {
                    reader.transferTo(output);
                    assertEquals(READER_PAYLOAD, output.toString());
                }
            }
        }
    }

    protected enum ParameterLayout {
        LINEAR_SINGLE(ImmutableList.of("other", "p"), 2),
        LINEAR_REPEATED(ImmutableList.of("p", "other", "p", "p"), 1, 3, 4),
        INDEXED_SINGLE(ImmutableList.of("a", "b", "c", "d", "p"), 5),
        INDEXED_TWICE(ImmutableList.of("p", "a", "b", "p", "c"), 1, 4),
        INDEXED_THREE_TIMES(ImmutableList.of("p", "a", "p", "b", "p"), 1, 3, 5),
        INDEXED_FOUR_TIMES(ImmutableList.of("p", "p", "a", "p", "p"), 1, 2, 4, 5),
        LINEAR_MISSING(ImmutableList.of("a", "b")),
        INDEXED_MISSING(ImmutableList.of("a", "b", "c", "d", "e"));

        private final ImmutableList<String> names;
        private final int[] indexes;

        ParameterLayout(final ImmutableList<String> names, final int... indexes) {
            this.names = names;
            this.indexes = indexes;
        }
    }

    @FunctionalInterface
    protected interface NamedBinding {
        NamedQuery bind(NamedQuery query, String name) throws SQLException;
    }

    @FunctionalInterface
    protected interface JdbcBinding {
        void bind(PreparedStatement statement, int index) throws SQLException;
    }

    @FunctionalInterface
    protected interface NamedStreamBinding {
        NamedQuery bind(NamedQuery query, String name, InputStream value) throws SQLException;
    }

    @FunctionalInterface
    protected interface JdbcStreamBinding {
        void bind(PreparedStatement statement, int index, InputStream value) throws SQLException;
    }

    @FunctionalInterface
    protected interface NamedReaderBinding {
        NamedQuery bind(NamedQuery query, String name, Reader value) throws SQLException;
    }

    @FunctionalInterface
    protected interface JdbcReaderBinding {
        void bind(PreparedStatement statement, int index, Reader value) throws SQLException;
    }
}
