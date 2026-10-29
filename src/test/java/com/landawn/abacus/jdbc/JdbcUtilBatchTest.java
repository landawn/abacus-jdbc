package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.exception.UncheckedSQLException;

class JdbcUtilBatchTest extends JdbcUtilTestSupport {

    @ParameterizedTest(name = "wide named batch: route={0}, repeatedNames={1}")
    @CsvSource({ "prepare,false", "call,false", "execute,false", "large,false", "prepare,true", "call,true", "execute,true", "large,true" })
    void wideNamedBatchLayoutsBindEveryValueForEachRow(final String route, final boolean repeatedNames) throws SQLException {
        final List<String> names = new ArrayList<>();
        final Map<String, String> first = new LinkedHashMap<>();
        final Map<String, String> second = new LinkedHashMap<>();

        for (int i = 0; i < 64; i++) {
            final String name = "column_" + i;
            names.add(name);
            first.put(name, "first-" + i);
            second.put(name, "second-" + i);
        }

        if (repeatedNames) {
            // Repeat in reverse order so positional binding cannot accidentally pass by reusing
            // the first half's order, and use different row values to detect stale row caching.
            for (int i = 63; i >= 0; i--) {
                names.add("column_" + i);
            }
        }

        final List<String> placeholders = new ArrayList<>();
        for (final String name : names) {
            placeholders.add(":" + name);
        }

        final String parameters = String.join(", ", placeholders);
        final String sql = route.equals("call") ? "{call test_proc(" + parameters + ")}" : "INSERT INTO sample VALUES (" + parameters + ")";
        final List<Map<String, String>> rows = List.of(first, second);
        final java.sql.PreparedStatement stmt = route.equals("call") ? mockCallableStatement : mockPreparedStatement;
        when(stmt.executeBatch()).thenReturn(new int[] { 1, 1 });
        when(stmt.executeLargeBatch()).thenReturn(new long[] { 1, 1 });

        switch (route) {
            case "prepare" -> assertSame(stmt, JdbcUtil.prepareBatchStmt(mockConnection, sql, rows));
            case "call" -> assertSame(stmt, JdbcUtil.prepareBatchCall(mockConnection, sql, rows));
            case "execute" -> assertEquals(2, JdbcUtil.executeBatchUpdate(mockConnection, sql, rows, 2));
            case "large" -> assertEquals(2L, JdbcUtil.executeLargeBatchUpdate(mockConnection, sql, rows, 2));
            default -> throw new AssertionError(route);
        }

        final org.mockito.InOrder bindings = org.mockito.Mockito.inOrder(stmt);
        for (final Map<String, String> row : rows) {
            for (int i = 0; i < names.size(); i++) {
                bindings.verify(stmt).setString(i + 1, row.get(names.get(i)));
            }
            bindings.verify(stmt).addBatch();
        }
        verify(stmt, org.mockito.Mockito.times(2)).addBatch();
    }

    @ParameterizedTest
    @ValueSource(strings = { "prepare", "call", "execute", "large" })
    void namedBatchLayoutReuseKeepsGetterValuesAndPositionalRowsSeparate(final String route) throws SQLException {
        final CountingBatchBean first = new CountingBatchBean("first");
        final CountingBatchBean second = new CountingBatchBean("second");
        final List<?> rows = Arrays.asList(first, java.util.Map.of("value", "map"), second, new Object[] { "left", "right" });
        final java.sql.PreparedStatement stmt = route.equals("call") ? mockCallableStatement : mockPreparedStatement;
        final String sql = route.equals("call") ? "{call test_proc(:value, :value)}" : "INSERT INTO sample VALUES (:value, :value)";
        when(stmt.executeBatch()).thenReturn(new int[] { 1, 1 });
        when(stmt.executeLargeBatch()).thenReturn(new long[] { 1, 1 });

        switch (route) {
            case "prepare" -> assertSame(stmt, JdbcUtil.prepareBatchStmt(mockConnection, sql, rows));
            case "call" -> assertSame(stmt, JdbcUtil.prepareBatchCall(mockConnection, sql, rows));
            case "execute" -> assertEquals(4, JdbcUtil.executeBatchUpdate(mockConnection, sql, rows, 2));
            case "large" -> assertEquals(4L, JdbcUtil.executeLargeBatchUpdate(mockConnection, sql, rows, 2));
            default -> throw new AssertionError(route);
        }

        final org.mockito.InOrder bindings = org.mockito.Mockito.inOrder(stmt);
        for (final String[] row : new String[][] { { "first1", "first1" }, { "map", "map" }, { "second1", "second1" }, { "left", "right" } }) {
            bindings.verify(stmt).setString(1, row[0]);
            bindings.verify(stmt).setString(2, row[1]);
            bindings.verify(stmt).addBatch();
        }
        assertEquals(1, first.reads);
        assertEquals(1, second.reads);
    }

    @Test
    void namedBatchLayoutReuseKeepsRepeatedStreamsScopedToEachRow() throws SQLException {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:h2:mem:batch_stream_layout_" + java.util.UUID.randomUUID());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE sample (a BLOB, b BLOB)");
            final byte[] first = { 1, 2, 3 };
            final byte[] second = { 4, 5 };
            final List<?> rows = List.of(java.util.Map.of("value", new java.io.ByteArrayInputStream(first)),
                    java.util.Map.of("value", new java.io.ByteArrayInputStream(second)));
            assertEquals(2, JdbcUtil.executeBatchUpdate(conn, "INSERT INTO sample VALUES (:value, :value)", rows, 1));
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT a, b FROM sample ORDER BY OCTET_LENGTH(a) DESC")) {
                for (final byte[] expected : new byte[][] { first, second }) {
                    assertTrue(rs.next());
                    assertArrayEquals(expected, rs.getBytes(1));
                    assertArrayEquals(expected, rs.getBytes(2));
                }
                org.junit.jupiter.api.Assertions.assertFalse(rs.next());
            }
        }
    }

    public static final class CountingBatchBean {
        private String prefix;
        private int reads;

        public CountingBatchBean() {
        }

        CountingBatchBean(final String value) {
            prefix = value;
        }

        public String getValue() {
            return prefix + ++reads;
        }

        public void setValue(final String value) {
            prefix = value;
        }
    }

    @Test
    public void testBatchPreparationPreservesFailureWhenClosingAlsoFails() throws SQLException {
        final AssertionError failure = new AssertionError("addBatch failed");
        final AssertionError closeFailure = new AssertionError("close failed");
        doThrow(failure).when(mockPreparedStatement).addBatch();
        doThrow(closeFailure).when(mockPreparedStatement).close();

        assertSame(failure, assertThrows(AssertionError.class, () -> JdbcUtil.prepareBatchStmt(mockConnection, "SELECT ?", List.of(1))));
        assertArrayEquals(new Throwable[] { closeFailure }, failure.getSuppressed());
        verify(mockPreparedStatement).close();
    }

    @Test
    public void testExecuteBatchUpdate_NamedSingleValueCalendarElementsAreBoundDirectly() throws SQLException {
        final java.util.GregorianCalendar first = new java.util.GregorianCalendar();
        first.setTimeInMillis(1_000L);
        final java.util.GregorianCalendar second = new java.util.GregorianCalendar();
        second.setTimeInMillis(2_000L);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1, 1 });

        assertEquals(2, JdbcUtil.executeBatchUpdate(mockConnection, "INSERT INTO events (created_at) VALUES (:createdAt)", List.of(first, second)));

        verify(mockPreparedStatement).setTimestamp(1, new java.sql.Timestamp(1_000L));
        verify(mockPreparedStatement).setTimestamp(1, new java.sql.Timestamp(2_000L));
        verify(mockPreparedStatement, org.mockito.Mockito.times(2)).addBatch();
    }

    @Test
    public void testExecuteBatchUpdate_ThrowsOnIntCountOverflow() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        List<Object[]> params = Arrays.asList(new Object[] { "John" }, new Object[] { "Jane" });
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { Integer.MAX_VALUE }, new int[] { 1 });

        ArithmeticException thrown = assertThrows(ArithmeticException.class, () -> JdbcUtil.executeBatchUpdate(mockConnection, sql, params, 1));
        assertTrue(thrown.getMessage().contains("executeLargeBatchUpdate"));
    }

    @Test
    @DisplayName("executeBatchUpdate(conn, ...): unchecked rollback/cleanup failures do not mask the batch failure")
    public void testExecuteBatchUpdatePreservesPrimaryFailureWhenUncheckedCleanupFails() throws SQLException {
        final List<Object[]> parameters = List.of(new Object[] { 1 }, new Object[] { 2 });
        final SQLException batchFailure = new SQLException("batch failed");
        final IllegalStateException rollbackFailure = new IllegalStateException("rollback failed unchecked");

        when(mockConnection.getAutoCommit()).thenReturn(true);
        org.mockito.Mockito.doNothing().when(mockConnection).setAutoCommit(false);
        doThrow(batchFailure).when(mockPreparedStatement).executeBatch();
        doThrow(rollbackFailure).when(mockConnection).rollback();

        final SQLException thrown = assertThrows(SQLException.class,
                () -> JdbcUtil.executeBatchUpdate(mockConnection, "UPDATE account SET status = ?", parameters, 2));

        assertSame(batchFailure, thrown);
        assertArrayEquals(new Throwable[] { rollbackFailure }, thrown.getSuppressed());
        verify(mockConnection, never()).setAutoCommit(true);
    }

    @Test
    @DisplayName("executeBatchUpdate(conn, ...): a reused batch/cleanup failure is not self-suppressed")
    public void testExecuteBatchUpdateDoesNotSelfSuppressReusedFailure() throws SQLException {
        final List<Object[]> parameters = List.of(new Object[] { 1 }, new Object[] { 2 });
        final SQLException sharedFailure = new SQLException("shared batch cleanup failure");

        when(mockConnection.getAutoCommit()).thenReturn(true);
        org.mockito.Mockito.doNothing().doThrow(sharedFailure).when(mockConnection).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
        doThrow(sharedFailure).when(mockPreparedStatement).executeBatch();
        doThrow(sharedFailure).when(mockConnection).rollback();

        final SQLException thrown = assertThrows(SQLException.class,
                () -> JdbcUtil.executeBatchUpdate(mockConnection, "UPDATE account SET status = ?", parameters, 2));

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        verify(mockConnection).rollback();
        verify(mockConnection, never()).setAutoCommit(true);
    }

    @Test
    public void testExecuteLargeBatchUpdate_ThrowsOnLongCountOverflow() throws SQLException {
        String sql = "INSERT INTO users (name) VALUES (?)";
        List<Object[]> params = Arrays.asList(new Object[] { "John" }, new Object[] { "Jane" });
        when(mockPreparedStatement.executeLargeBatch()).thenReturn(new long[] { Long.MAX_VALUE }, new long[] { 1L });

        ArithmeticException thrown = assertThrows(ArithmeticException.class, () -> JdbcUtil.executeLargeBatchUpdate(mockConnection, sql, params, 1));
        assertTrue(thrown.getMessage().contains("exceeds Long.MAX_VALUE"));
    }

    @Test
    @DisplayName("executeLargeBatchUpdate(conn, ...): unchecked rollback/cleanup failures do not mask the commit failure")
    public void testExecuteLargeBatchUpdatePreservesCommitFailureWhenUncheckedCleanupFails() throws SQLException {
        final List<Object[]> parameters = List.of(new Object[] { 1 }, new Object[] { 2 });
        final SQLException commitFailure = new SQLException("large commit failed");
        final AssertionError rollbackFailure = new AssertionError("large rollback failed unchecked");

        when(mockConnection.getAutoCommit()).thenReturn(true);
        org.mockito.Mockito.doNothing().when(mockConnection).setAutoCommit(false);
        when(mockPreparedStatement.executeLargeBatch()).thenReturn(new long[] { 1, 1 });
        doThrow(commitFailure).when(mockConnection).commit();
        doThrow(rollbackFailure).when(mockConnection).rollback();

        final SQLException thrown = assertThrows(SQLException.class,
                () -> JdbcUtil.executeLargeBatchUpdate(mockConnection, "UPDATE account SET status = ?", parameters, 2));

        assertSame(commitFailure, thrown);
        assertArrayEquals(new Throwable[] { rollbackFailure }, thrown.getSuppressed());
        verify(mockConnection, never()).setAutoCommit(true);
    }

    @Test
    @DisplayName("executeLargeBatchUpdate(conn, ...): a reused commit/cleanup failure is not self-suppressed")
    public void testExecuteLargeBatchUpdateDoesNotSelfSuppressReusedFailure() throws SQLException {
        final List<Object[]> parameters = List.of(new Object[] { 1 }, new Object[] { 2 });
        final SQLException sharedFailure = new SQLException("shared large-batch completion failure");

        when(mockConnection.getAutoCommit()).thenReturn(true);
        org.mockito.Mockito.doNothing().doThrow(sharedFailure).when(mockConnection).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
        when(mockPreparedStatement.executeLargeBatch()).thenReturn(new long[] { 1, 1 });
        doThrow(sharedFailure).when(mockConnection).commit();
        doThrow(sharedFailure).when(mockConnection).rollback();

        final SQLException thrown = assertThrows(SQLException.class,
                () -> JdbcUtil.executeLargeBatchUpdate(mockConnection, "UPDATE account SET status = ?", parameters, 2));

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        verify(mockConnection).rollback();
        verify(mockConnection, never()).setAutoCommit(true);
    }

    // Tests for public constants
    @Test
    public void testDefaultBatchSize() {
        assertEquals(200, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @ParameterizedTest(name = "successful batch, largeBatch={0}")
    @ValueSource(booleans = { false, true })
    void executeBatchUpdate_success_returnsAffectedRows(final boolean largeBatch) throws SQLException {
        final List<Object[]> parameters = Arrays.asList(new Object[] { "John" }, new Object[] { "Jane" });
        stubBatchCounts(largeBatch, 1, 1);

        final long affected = largeBatch ? JdbcUtil.executeLargeBatchUpdate(mockDataSource, "INSERT INTO users (name) VALUES (?)", parameters)
                : JdbcUtil.executeBatchUpdate(mockDataSource, "INSERT INTO users (name) VALUES (?)", parameters);

        assertEquals(2L, affected);
    }

    @ParameterizedTest(name = "JDBC sentinel counts, largeBatch={0}")
    @ValueSource(booleans = { false, true })
    void executeBatchUpdate_sentinelCounts_sumsOnlyAffectedRows(final boolean largeBatch) throws SQLException {
        final List<Object[]> parameters = Arrays.asList(new Object[] { "John" }, new Object[] { "Jane" }, new Object[] { "Bob" }, new Object[] { "Alice" });
        stubBatchCounts(largeBatch, 2, Statement.SUCCESS_NO_INFO, Statement.EXECUTE_FAILED, 1);

        final long affected = largeBatch ? JdbcUtil.executeLargeBatchUpdate(mockDataSource, "INSERT INTO users (name) VALUES (?)", parameters)
                : JdbcUtil.executeBatchUpdate(mockDataSource, "INSERT INTO users (name) VALUES (?)", parameters);

        assertEquals(3L, affected);
    }

    @ParameterizedTest(name = "auto-commit restoration, largeBatch={0}")
    @ValueSource(booleans = { false, true })
    void executeBatchUpdate_restorationFailure_preservesFailureAndClosesStatement(final boolean largeBatch) throws SQLException {
        final List<Object[]> parameters = List.of(new Object[] { 1 }, new Object[] { 2 });
        final SQLException failure = new SQLException("restore auto-commit failed");
        when(mockConnection.getAutoCommit()).thenReturn(true);
        stubBatchCounts(largeBatch, 1, 1);
        org.mockito.Mockito.doNothing().doThrow(failure).when(mockConnection).setAutoCommit(anyBoolean());

        assertSame(failure, assertThrows(SQLException.class, () -> executeConfiguredBatch(largeBatch, parameters)));
        verify(mockConnection).commit();
        verify(mockConnection).setAutoCommit(true);
        verify(mockPreparedStatement).close();
    }

    @ParameterizedTest(name = "commit and rollback failure, largeBatch={0}")
    @ValueSource(booleans = { false, true })
    void executeBatchUpdate_commitAndRollbackFailure_preservesBothFailures(final boolean largeBatch) throws SQLException {
        final List<Object[]> parameters = List.of(new Object[] { 1 }, new Object[] { 2 });
        final SQLException commitFailure = new SQLException("commit failed");
        final SQLException rollbackFailure = new SQLException("rollback failed");
        when(mockConnection.getAutoCommit()).thenReturn(true);
        stubBatchCounts(largeBatch, 1, 1);
        doThrow(commitFailure).when(mockConnection).commit();
        doThrow(rollbackFailure).when(mockConnection).rollback();

        final SQLException thrown = assertThrows(SQLException.class, () -> executeConfiguredBatch(largeBatch, parameters));

        assertSame(commitFailure, thrown);
        assertArrayEquals(new Throwable[] { rollbackFailure }, thrown.getSuppressed());
        verify(mockConnection).rollback();
        verify(mockConnection, never()).setAutoCommit(true);
    }

    @ParameterizedTest(name = "execution and rollback failure, largeBatch={0}")
    @ValueSource(booleans = { false, true })
    void executeBatchUpdate_executionAndRollbackFailure_preservesBothFailures(final boolean largeBatch) throws SQLException {
        final List<Object[]> parameters = List.of(new Object[] { 1 }, new Object[] { 2 }, new Object[] { 3 });
        final SQLException executionFailure = new SQLException("batch failed");
        final SQLException rollbackFailure = new SQLException("rollback failed");
        if (largeBatch) {
            doThrow(executionFailure).when(mockPreparedStatement).executeLargeBatch();
        } else {
            doThrow(executionFailure).when(mockPreparedStatement).executeBatch();
        }
        doThrow(rollbackFailure).when(mockConnection).rollback();

        final SQLException thrown = assertThrows(SQLException.class, () -> {
            if (largeBatch) {
                JdbcUtil.executeLargeBatchUpdate(mockDataSource, "UPDATE account SET status = ?", parameters, 2);
            } else {
                JdbcUtil.executeBatchUpdate(mockDataSource, "UPDATE account SET status = ?", parameters, 2);
            }
        });

        assertSame(executionFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof UncheckedSQLException);
        assertSame(rollbackFailure, thrown.getSuppressed()[0].getCause());
    }

    @ParameterizedTest(name = "largeBatch={0}, commitFailure={1}, restorationFailure={2}")
    @CsvSource({ "false,false,false", "false,false,true", "false,true,false", "false,true,true", "true,false,false", "true,false,true", "true,true,false",
            "true,true,true" })
    void failedBatchCompletionRollsBackAndPreservesRestorationFailure(final boolean largeBatch, final boolean failCommit, final boolean failRestoration)
            throws SQLException {
        final SQLException primary = new SQLException(failCommit ? "commit failed" : "batch failed");
        final SQLException restoration = new SQLException("auto-commit restoration failed");
        when(mockConnection.getAutoCommit()).thenReturn(true);
        stubBatchCounts(largeBatch, 1, 1);
        if (failCommit) {
            doThrow(primary).when(mockConnection).commit();
        } else if (largeBatch) {
            doThrow(primary).when(mockPreparedStatement).executeLargeBatch();
        } else {
            doThrow(primary).when(mockPreparedStatement).executeBatch();
        }
        if (failRestoration) {
            doThrow(restoration).when(mockConnection).setAutoCommit(true);
        }

        assertSame(primary, assertThrows(SQLException.class, () -> executeConfiguredBatch(largeBatch, List.of(new Object[] { 1 }, new Object[] { 2 }))));
        assertArrayEquals(failRestoration ? new Throwable[] { restoration } : new Throwable[0], primary.getSuppressed());
        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(mockConnection, mockPreparedStatement);
        order.verify(mockConnection).setAutoCommit(false);
        if (failCommit) {
            order.verify(mockConnection).commit();
        } else {
            verify(mockConnection, never()).commit();
        }
        order.verify(mockConnection).rollback();
        order.verify(mockConnection).setAutoCommit(true);
        order.verify(mockPreparedStatement).close();
        verify(mockConnection, never()).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void emptyBatchesDoNotTouchCallerConnection(final boolean largeBatch) throws SQLException {
        assertEquals(0, executeConfiguredBatch(largeBatch, List.of()));
        assertEquals(0, executeConfiguredBatch(largeBatch, null));
        org.mockito.Mockito.verifyNoInteractions(mockConnection, mockPreparedStatement);
    }

    @Test
    void largeBatchDefaultSizeOnCallerConnectionKeepsLongCountsAndConnection() throws SQLException {
        final long count = (long) Integer.MAX_VALUE + 1;
        when(mockPreparedStatement.executeLargeBatch()).thenReturn(new long[] { count });

        assertEquals(count, JdbcUtil.executeLargeBatchUpdate(mockConnection, "UPDATE account SET status = ?", List.of(1)));
        verify(mockPreparedStatement).close();
        verify(mockConnection, never()).commit();
        verify(mockConnection, never()).rollback();
        verify(mockConnection, never()).close();
    }

    private void stubBatchCounts(final boolean largeBatch, final int... counts) throws SQLException {
        if (largeBatch) {
            when(mockPreparedStatement.executeLargeBatch()).thenReturn(Arrays.stream(counts).asLongStream().toArray());
        } else {
            when(mockPreparedStatement.executeBatch()).thenReturn(counts);
        }
    }

    private long executeConfiguredBatch(final boolean largeBatch, final List<Object[]> parameters) throws SQLException {
        return largeBatch ? JdbcUtil.executeLargeBatchUpdate(mockConnection, "UPDATE account SET status = ?", parameters, 2)
                : JdbcUtil.executeBatchUpdate(mockConnection, "UPDATE account SET status = ?", parameters, 2);
    }

}
