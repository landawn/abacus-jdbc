package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

class JdbcUtilBatchCleanupTest extends JdbcUtilBatchCleanupTestSupport {

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

    // A checked exception the driver throws without declaring it (Kotlin, sneaky throw) is handled as an SQLException is:
    // logged after a successful batch, attached to the batch failure otherwise.
    @Test
    void undeclaredCheckedCleanupFailureKeepsBatchOutcome() throws Exception {
        forEachBatchMode(largeBatch -> {
            final IOException cleanup = new IOException("clear failed");
            final Statement stmt = mock(Statement.class);
            final int[] counts = { 1, 2 };
            final long[] largeCounts = { 1L, 2L };
            when(stmt.executeBatch()).thenReturn(counts);
            when(stmt.executeLargeBatch()).thenReturn(largeCounts);
            doAnswer(invocation -> {
                throw cleanup;
            }).when(stmt).clearBatch();

            assertSame(largeBatch ? largeCounts : counts, executeBatch(stmt, largeBatch));

            final SQLException primary = new SQLException("batch failed");
            doThrow(primary).when(stmt).executeBatch();
            doThrow(primary).when(stmt).executeLargeBatch();

            assertSame(primary, assertThrows(SQLException.class, () -> executeBatch(stmt, largeBatch)));
            assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
            verify(stmt, times(2)).clearBatch();
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
}
