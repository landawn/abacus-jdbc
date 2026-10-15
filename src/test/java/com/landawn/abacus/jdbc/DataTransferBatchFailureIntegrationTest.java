package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Throwables;

@Tag("integration")
@Tag("2025")
class DataTransferBatchFailureIntegrationTest {

    @Test
    void failedDatasetFilterDiscardsQueuedRows() throws SQLException {
        final IllegalStateException failure = new IllegalStateException("filter failed");
        final Dataset rows = Dataset.rows(List.of("id"), new Object[][] { { 1 }, { 2 } });

        assertStatementReusable(failure, stmt -> DataTransferUtil.importFrom(rows).filter(row -> {
            if ((Integer) row[0] == 2) {
                throw failure;
            }
            return true;
        }).to(stmt));
    }

    @Test
    void failedIteratorBindingDiscardsQueuedRows() throws SQLException {
        final SQLException failure = new SQLException("binding failed");

        assertStatementReusable(failure, stmt -> DataTransferUtil.importFrom(List.of(1, 2).iterator()).parameterSetter((query, id) -> {
            if (id == 2) {
                throw failure;
            }
            query.setInt(1, id);
        }).to(stmt));
    }

    @Test
    void failedCsvBindingDiscardsQueuedRows() throws SQLException {
        final SQLException failure = new SQLException("CSV binding failed");

        assertStatementReusable(failure, stmt -> DataTransferUtil.importCsvFrom(new StringReader("id\n1\n2\n")).parameterSetter((query, row) -> {
            if ("2".equals(row[0])) {
                throw failure;
            }
            query.setInt(1, Integer.parseInt(row[0]));
        }).to(stmt));
    }

    @Test
    void failedCopyDiscardsQueuedRows() throws SQLException {
        final SQLException failure = new SQLException("copy binding failed");

        assertStatementReusable(failure, stmt -> {
            try (PreparedStatement source = stmt.getConnection().prepareStatement("VALUES (1), (2)")) {
                try {
                    DataTransferUtil.copyFrom(source).parameterSetter((query, row) -> {
                        final int id = row.getInt(1);
                        if (id == 2) {
                            throw failure;
                        }
                        query.setInt(1, id);
                    }).to(stmt);
                } finally {
                    assertFalse(source.isClosed());
                }
            }
        });
    }

    @Test
    void failedCsvReadDiscardsQueuedRowsAndPreservesTheReadFailure() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final IOException failure = new IOException("CSV read failed");
        final Reader reader = new Reader() {
            private final String prefix = "id\n1\n";
            private int offset;

            @Override
            public int read(final char[] target, final int targetOffset, final int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (offset == prefix.length()) {
                    throw failure;
                }
                target[targetOffset] = prefix.charAt(offset++);
                return 1;
            }

            @Override
            public void close() {
                throw new AssertionError("The caller owns this reader");
            }
        };

        final UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                () -> DataTransferUtil.importCsvFrom(reader).parameterSetter((query, row) -> query.setInt(1, Integer.parseInt(row[0]))).to(stmt));

        assertSame(failure, thrown.getCause());
        verify(stmt).addBatch();
        verify(stmt).clearBatch();
        verify(stmt, never()).close();
    }

    @Test
    void failedAddBatchDiscardsAPossiblyQueuedRow() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final SQLException failure = new SQLException("addBatch failed");
        doThrow(failure).when(stmt).addBatch();

        assertSame(failure, assertThrows(SQLException.class,
                () -> DataTransferUtil.importFrom(List.of(1).iterator()).parameterSetter((query, id) -> query.setInt(1, id)).to(stmt)));

        verify(stmt).clearBatch();
        verify(stmt, never()).close();
    }

    @Test
    void cleanupFailureIsSuppressedOnTheTransferFailure() throws SQLException {
        for (final Throwable cleanupFailure : new Throwable[] { new SQLException("clear failed"), new IllegalStateException("clear failed"),
                new AssertionError("clear failed") }) {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            final SQLException failure = new SQLException("binding failed");
            doThrow(cleanupFailure).when(stmt).clearBatch();

            final SQLException thrown = assertThrows(SQLException.class,
                    () -> DataTransferUtil.importFrom(List.of(1, 2).iterator()).parameterSetter((query, id) -> {
                        if (id == 2) {
                            throw failure;
                        }
                    }).to(stmt));

            assertSame(failure, thrown);
            assertArrayEquals(new Throwable[] { cleanupFailure }, thrown.getSuppressed());
            verify(stmt, never()).close();
        }
    }

    @Test
    void failedIteratorReadDiscardsQueuedRows() throws SQLException {
        final IllegalStateException failure = new IllegalStateException("iterator failed");
        final Iterator<Integer> rows = new Iterator<>() {
            private boolean consumed;

            @Override
            public boolean hasNext() {
                if (consumed) {
                    throw failure;
                }
                return true;
            }

            @Override
            public Integer next() {
                consumed = true;
                return 1;
            }
        };

        assertStatementReusable(failure, stmt -> DataTransferUtil.importFrom(rows).parameterSetter((query, id) -> query.setInt(1, id)).to(stmt));
    }

    @Test
    void failedCopyReadDiscardsQueuedRowsAndClosesOnlyItsResultSet() throws SQLException {
        final PreparedStatement source = mock(PreparedStatement.class);
        final ResultSet rows = mock(ResultSet.class);
        final SQLException failure = new SQLException("source read failed");
        when(source.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true).thenThrow(failure);
        when(rows.getInt(1)).thenReturn(1);

        assertStatementReusable(failure, stmt -> DataTransferUtil.copyFrom(source).parameterSetter((query, row) -> query.setInt(1, row.getInt(1))).to(stmt));

        verify(rows).close();
        verify(source, never()).close();
    }

    @Test
    void failedCsvParsingDiscardsQueuedRows() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);

        assertThrows(IllegalArgumentException.class,
                () -> DataTransferUtil.importCsvFrom(new StringReader("id\n1\n2,3\n"))
                        .parameterSetter((query, row) -> query.setInt(1, Integer.parseInt(row[0])))
                        .to(stmt));

        verify(stmt).addBatch();
        verify(stmt).clearBatch();
        verify(stmt, never()).close();
    }

    @Test
    void failureAfterCompletedBatchesOnlyDiscardsTheUnexecutedRows() throws SQLException {
        final SQLException failure = new SQLException("fourth row failed");
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:");
             Statement setup = conn.createStatement()) {
            setup.execute("CREATE TABLE transferred (id INT)");
            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO transferred VALUES (?)")) {
                assertSame(failure, assertThrows(SQLException.class,
                        () -> DataTransferUtil.importFrom(List.of(1, 2, 3, 4).iterator()).batchSize(2).parameterSetter((query, id) -> {
                            if (id == 4) {
                                throw failure;
                            }
                            query.setInt(1, id);
                        }).to(stmt)));

                assertFalse(stmt.isClosed());
                assertArrayEquals(new int[0], stmt.executeBatch());
            }
            try (ResultSet rows = setup.executeQuery("SELECT id FROM transferred ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void failureImmediatelyAfterACompletedBatchDoesNotClearAgain() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final AssertionError failure = new AssertionError("next binding failed");
        when(stmt.executeBatch()).thenReturn(new int[] { 1 });

        assertSame(failure,
                assertThrows(AssertionError.class, () -> DataTransferUtil.importFrom(List.of(1, 2).iterator()).batchSize(1).parameterSetter((query, id) -> {
                    if (id == 2) {
                        throw failure;
                    }
                    query.setInt(1, id);
                }).to(stmt)));

        verify(stmt).executeBatch();
        verify(stmt).clearBatch();
        verify(stmt, never()).close();
    }

    @Test
    void failedExecutionRetriesFailedCleanupWithoutLosingTheOriginalFailure() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final SQLException failure = new SQLException("execution failed");
        final SQLException cleanupFailure = new SQLException("first cleanup failed");
        doThrow(failure).when(stmt).executeBatch();
        doThrow(cleanupFailure).doNothing().when(stmt).clearBatch();

        assertSame(failure, assertThrows(SQLException.class,
                () -> DataTransferUtil.importFrom(List.of(1).iterator()).parameterSetter((query, id) -> query.setInt(1, id)).to(stmt)));

        assertArrayEquals(new Throwable[] { cleanupFailure }, failure.getSuppressed());
        verify(stmt, times(2)).clearBatch();
        verify(stmt, never()).close();
    }

    @Test
    void sharedCleanupFailureIsNotSelfSuppressed() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final SQLException failure = new SQLException("shared failure");
        doThrow(failure).when(stmt).addBatch();
        doThrow(failure).when(stmt).clearBatch();

        assertSame(failure, assertThrows(SQLException.class,
                () -> DataTransferUtil.importFrom(List.of(1).iterator()).parameterSetter((query, id) -> query.setInt(1, id)).to(stmt)));

        assertEquals(0, failure.getSuppressed().length);
        verify(stmt, never()).close();
    }

    @Test
    void failureBeforeAddingRowsDoesNotClearACallersExistingBatch() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final SQLException failure = new SQLException("first binding failed");

        assertSame(failure, assertThrows(SQLException.class, () -> DataTransferUtil.importFrom(List.of(1).iterator()).parameterSetter((query, id) -> {
            throw failure;
        }).to(stmt)));

        verify(stmt, never()).addBatch();
        verify(stmt, never()).clearBatch();
        verify(stmt, never()).close();
    }

    private static void assertStatementReusable(final Throwable failure, final Throwables.Consumer<PreparedStatement, SQLException> transfer)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:");
             Statement setup = conn.createStatement()) {
            setup.execute("CREATE TABLE transferred (id INT)");

            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO transferred VALUES (?)")) {
                assertSame(failure, assertThrows(failure.getClass(), () -> transfer.accept(stmt)));
                assertFalse(stmt.isClosed());
                assertArrayEquals(new int[0], stmt.executeBatch(), "A failed transfer must not leave rows queued on the caller's statement");

                stmt.setInt(1, 3);
                stmt.addBatch();
                assertArrayEquals(new int[] { 1 }, stmt.executeBatch());
            }

            try (ResultSet rows = setup.executeQuery("SELECT id FROM transferred")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
                assertFalse(rows.next());
            }
        }
    }
}
