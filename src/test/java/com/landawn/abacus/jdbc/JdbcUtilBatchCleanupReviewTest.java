package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.function.TriConsumer;

@Tag("2025")
class JdbcUtilBatchCleanupReviewTest {

    private Field handlerField;
    private Object previousHandler;
    private SqlLogConfig previousLogConfig;

    @BeforeEach
    void preserveLoggingConfiguration() throws ReflectiveOperationException {
        handlerField = JdbcUtil.class.getDeclaredField("_sqlLogHandler");
        handlerField.setAccessible(true);
        previousHandler = handlerField.get(null);
        previousLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();
        JdbcUtil.sqlPerfLogThresholdMillis_TL.set(new SqlLogConfig(-1, 128));
    }

    @AfterEach
    void restoreLoggingConfiguration() throws IllegalAccessException {
        handlerField.set(null, previousHandler);
        JdbcUtil.sqlPerfLogThresholdMillis_TL.set(previousLogConfig);
    }

    @Test
    void executionFailureRetainsCheckedAndUncheckedCleanupFailures() throws Exception {
        forEachBatchMode(largeBatch -> {
            for (final Throwable cleanup : new Throwable[] { new SQLException("clear failed"), new IllegalStateException("clear failed"),
                    new AssertionError("clear failed") }) {
                final Statement stmt = mock(Statement.class);
                final SQLException primary = new SQLException("batch failed");
                doThrow(primary).when(stmt).executeBatch();
                doThrow(primary).when(stmt).executeLargeBatch();
                doThrow(cleanup).when(stmt).clearBatch();

                assertSame(primary, assertThrows(SQLException.class, () -> executeBatch(stmt, largeBatch)));
                assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
                verify(stmt).clearBatch();
            }
        });
    }

    @Test
    void uncheckedExecutionFailureRetainsCleanupFailure() throws Exception {
        forEachBatchMode(largeBatch -> {
            for (final Throwable cleanup : new Throwable[] { new SQLException("clear failed"), new IllegalStateException("clear failed"),
                    new AssertionError("clear failed") }) {
                for (final Throwable primary : new Throwable[] { new IllegalStateException("batch failed"), new AssertionError("batch failed") }) {
                    final Statement stmt = mock(Statement.class);
                    doThrow(primary).when(stmt).executeBatch();
                    doThrow(primary).when(stmt).executeLargeBatch();
                    doThrow(cleanup).when(stmt).clearBatch();

                    assertSame(primary, assertThrows(primary.getClass(), () -> executeBatch(stmt, largeBatch)));
                    assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
                    verify(stmt).clearBatch();
                }
            }
        });
    }

    @Test
    void successfulBatchKeepsExactResultsWhenCheckedOrRuntimeCleanupFails() throws Exception {
        forEachBatchMode(largeBatch -> {
            for (final Throwable cleanup : new Throwable[] { new SQLException("clear failed"), new IllegalStateException("clear failed") }) {
                final Statement stmt = mock(Statement.class);
                final int[] counts = { 1, 2, Statement.SUCCESS_NO_INFO };
                final long[] largeCounts = { 1L, Integer.MAX_VALUE + 1L, Statement.SUCCESS_NO_INFO };
                when(stmt.executeBatch()).thenReturn(counts);
                when(stmt.executeLargeBatch()).thenReturn(largeCounts);
                doThrow(cleanup).when(stmt).clearBatch();

                assertSame(largeBatch ? largeCounts : counts, executeBatch(stmt, largeBatch));
                verify(stmt).clearBatch();
            }
        });
    }

    @Test
    void errorDuringCleanupAfterSuccessStillPropagates() throws Exception {
        forEachBatchMode(largeBatch -> {
            final Statement stmt = mock(Statement.class);
            final AssertionError cleanup = new AssertionError("clear failed");
            when(stmt.executeBatch()).thenReturn(new int[] { 1 });
            when(stmt.executeLargeBatch()).thenReturn(new long[] { 1L });
            doThrow(cleanup).when(stmt).clearBatch();

            assertSame(cleanup, assertThrows(AssertionError.class, () -> executeBatch(stmt, largeBatch)));
            verify(stmt).clearBatch();
        });
    }

    @Test
    void reusedFailureIsNotSelfSuppressed() throws Exception {
        forEachBatchMode(largeBatch -> {
            for (final Throwable shared : new Throwable[] { new SQLException("shared failure"), new IllegalStateException("shared failure"),
                    new AssertionError("shared failure") }) {
                final Statement stmt = mock(Statement.class);
                doThrow(shared).when(stmt).executeBatch();
                doThrow(shared).when(stmt).executeLargeBatch();
                doThrow(shared).when(stmt).clearBatch();

                assertSame(shared, assertThrows(shared.getClass(), () -> executeBatch(stmt, largeBatch)));
                assertEquals(0, shared.getSuppressed().length);
                verify(stmt).clearBatch();
            }
        });
    }

    @Test
    void successfulH2BatchesRemainReusableWhenTheDriverAlreadyClearedExecutedCommands() throws Exception {
        forEachBatchMode(largeBatch -> {
            for (final Throwable cleanup : new Throwable[] { new SQLException("clear failed"), new IllegalStateException("clear failed") }) {
                try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:");
                     Statement driver = connection.createStatement()) {
                    driver.execute("CREATE TABLE batch_cleanup (id INT PRIMARY KEY)");
                    final BatchFailureProxy intercepted = new BatchFailureProxy(driver, cleanup, null);
                    final Statement stmt = intercepted.statement;
                    stmt.addBatch("INSERT INTO batch_cleanup VALUES (1)");
                    stmt.addBatch("INSERT INTO batch_cleanup VALUES (2)");

                    final Object firstCounts = executeBatch(stmt, largeBatch);
                    assertSame(intercepted.lastBatchResult, firstCounts);
                    assertCounts(firstCounts, largeBatch, 2);
                    assertEquals(1, intercepted.cleanupAttempts);
                    assertEquals(List.of(1, 2), readIds(connection));
                    assertFalse(stmt.isClosed());

                    // H2 discards successfully executed commands itself. Reusing it here must not be read as
                    // a guarantee that an ignored cleanup failure empties every driver's pending batch.
                    stmt.addBatch("INSERT INTO batch_cleanup VALUES (3)");
                    final Object secondCounts = executeBatch(stmt, largeBatch);
                    assertSame(intercepted.lastBatchResult, secondCounts);
                    assertCounts(secondCounts, largeBatch, 1);
                    assertEquals(2, intercepted.cleanupAttempts);
                    assertEquals(List.of(1, 2, 3), readIds(connection));
                    assertFalse(stmt.isClosed());
                }
            }
        });
    }

    @Test
    void failedExecutionAndCleanupRequireExplicitClearingBeforeStatementReuse() throws Exception {
        forEachBatchMode(largeBatch -> {
            for (final Throwable cleanup : new Throwable[] { new SQLException("clear failed"), new IllegalStateException("clear failed") }) {
                try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:");
                     Statement driver = connection.createStatement()) {
                    driver.execute("CREATE TABLE batch_cleanup (id INT PRIMARY KEY)");
                    final SQLException primary = new SQLException("execution rejected before reaching the driver");
                    final BatchFailureProxy intercepted = new BatchFailureProxy(driver, cleanup, primary);
                    final Statement stmt = intercepted.statement;
                    stmt.addBatch("INSERT INTO batch_cleanup VALUES (1)");

                    assertSame(primary, assertThrows(SQLException.class, () -> executeBatch(stmt, largeBatch)));
                    assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
                    assertEquals(1, intercepted.cleanupAttempts);
                    assertEquals(List.of(), readIds(connection));
                    assertFalse(stmt.isClosed());

                    // Both injected failures occurred before delegation, so the real H2 statement still has
                    // row 1 queued. Recover explicitly before a second batch; failed cleanup promises no reset.
                    stmt.clearBatch();
                    stmt.addBatch("INSERT INTO batch_cleanup VALUES (2)");
                    final Object counts = executeBatch(stmt, largeBatch);
                    assertSame(intercepted.lastBatchResult, counts);
                    assertCounts(counts, largeBatch, 1);
                    assertEquals(3, intercepted.cleanupAttempts);
                    assertEquals(List.of(2), readIds(connection));
                    assertFalse(stmt.isClosed());
                }
            }
        });
    }

    private static void assertCounts(final Object counts, final boolean largeBatch, final int size) {
        if (largeBatch) {
            final long[] expected = new long[size];
            java.util.Arrays.fill(expected, 1L);
            assertArrayEquals(expected, (long[]) counts);
        } else {
            final int[] expected = new int[size];
            java.util.Arrays.fill(expected, 1);
            assertArrayEquals(expected, (int[]) counts);
        }
    }

    private static List<Integer> readIds(final Connection connection) throws SQLException {
        final List<Integer> ids = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT id FROM batch_cleanup ORDER BY id")) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        return ids;
    }

    private static final class BatchFailureProxy {
        private final Statement delegate;
        private final Throwable cleanupFailure;
        private final Statement statement;
        private Throwable executionFailure;
        private Object lastBatchResult;
        private int cleanupAttempts;

        private BatchFailureProxy(final Statement delegate, final Throwable cleanupFailure, final Throwable executionFailure) {
            this.delegate = delegate;
            this.cleanupFailure = cleanupFailure;
            this.executionFailure = executionFailure;
            statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] { Statement.class }, this::invoke);
        }

        private Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            if (method.getName().equals("clearBatch") && ++cleanupAttempts == 1) {
                throw cleanupFailure;
            }
            final boolean executingBatch = method.getName().equals("executeBatch") || method.getName().equals("executeLargeBatch");
            if (executingBatch && executionFailure != null) {
                final Throwable failure = executionFailure;
                executionFailure = null;
                throw failure;
            }
            try {
                final Object result = method.invoke(delegate, args);
                if (executingBatch) {
                    lastBatchResult = result;
                }
                return result;
            } catch (final InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private void forEachBatchMode(final BatchModeTest test) throws Exception {
        final TriConsumer<String, Long, Long> handler = (sql, start, end) -> {
        };
        for (final boolean logging : new boolean[] { false, true }) {
            handlerField.set(null, logging ? handler : null);
            for (final boolean largeBatch : new boolean[] { false, true }) {
                test.run(largeBatch);
            }
        }
    }

    private static Object executeBatch(final Statement stmt, final boolean largeBatch) throws SQLException {
        return largeBatch ? JdbcUtil.executeLargeBatch(stmt) : JdbcUtil.executeBatch(stmt);
    }

    @FunctionalInterface
    private interface BatchModeTest {
        void run(boolean largeBatch) throws Exception;
    }
}
