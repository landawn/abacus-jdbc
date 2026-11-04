package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.Throwables;

/**
 * Cleanup paths of {@link JdbcUtil} facing a checked exception that a driver, wrapper or callback throws without declaring
 * it (Kotlin, a sneaky throw), and close {@link Error}s raised while an operation's failure propagates.
 */
class JdbcUtilUndeclaredCheckedCleanupTest extends JdbcUtilTestSupport {

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(final Throwable e) throws E {
        throw (E) e;
    }

    // A SQL log handler runs in the finally block of executeQuery/executeBatch/executeLargeBatch. Only a RuntimeException of
    // it was caught: an undeclared checked one escaped, dropping the query's result set unclosed and skipping the cleanup.
    @Test
    void sqlLogHandlerUndeclaredCheckedFailureKeepsQueryResultAndClearsParameters() throws Exception {
        JdbcUtil.setSqlLogHandler((sql, start, end) -> sneakyThrow(new IOException("handler failure")));

        try (ResultSet result = JdbcUtil.executeQuery(mockPreparedStatement)) {
            assertNotNull(result);
        }

        verify(mockPreparedStatement).clearParameters();
        verify(mockResultSet).close();
    }

    @ParameterizedTest(name = "large={0}")
    @ValueSource(booleans = { false, true })
    void sqlLogHandlerUndeclaredCheckedFailureKeepsBatchFailurePrimaryAndClearsBatch(final boolean large) throws Exception {
        final SQLException primary = new SQLException("batch failed");
        doThrow(primary).when(mockStatement).executeBatch();
        doThrow(primary).when(mockStatement).executeLargeBatch();
        JdbcUtil.setSqlLogHandler((sql, start, end) -> sneakyThrow(new IOException("handler failure")));

        assertSame(primary, assertThrows(SQLException.class, () -> {
            if (large) {
                JdbcUtil.executeLargeBatch(mockStatement);
            } else {
                JdbcUtil.executeBatch(mockStatement);
            }
        }));

        verify(mockStatement).clearBatch();
    }

    // clearParameters caught SQLException | RuntimeException only, while its twin clearBatchAfterExecution catches Exception.
    @Test
    void undeclaredCheckedParameterCleanupFailureKeepsResultAndPrimaryFailure() throws Exception {
        doAnswer(invocation -> {
            throw new IOException("clear failed");
        }).when(mockPreparedStatement).clearParameters();
        when(mockPreparedStatement.executeUpdate()).thenReturn(7);

        assertEquals(7, JdbcUtil.executeUpdate(mockPreparedStatement));

        final SQLException primary = new SQLException("execution failed");
        doThrow(primary).when(mockPreparedStatement).execute();

        assertSame(primary, assertThrows(SQLException.class, () -> JdbcUtil.execute(mockPreparedStatement)));
    }

    // The explicit multi-resource overloads attach a raw undeclared checked close failure to the first failure; close(rs,
    // true, false) attached the IllegalStateException wrapper that close(rs, stmt, conn) throws for it.
    @Test
    void closeWithLookupFailureAttachesRawUndeclaredCheckedCloseFailure() throws Exception {
        final SQLException lookupFailure = new SQLException("getStatement failed");
        final IOException closeFailure = new IOException("close failed");
        when(mockResultSet.getStatement()).thenThrow(lookupFailure);
        doAnswer(invocation -> {
            throw closeFailure;
        }).when(mockResultSet).close();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(mockResultSet, true, false));

        assertSame(lookupFailure, thrown.getCause());
        assertArrayEquals(new Throwable[] { closeFailure }, lookupFailure.getSuppressed());
    }

    @Test
    void closeWithoutLookupFailureStillWrapsUndeclaredCheckedCloseFailure() throws Exception {
        final IOException closeFailure = new IOException("close failed");
        when(mockResultSet.getStatement()).thenReturn(mockStatement);
        doAnswer(invocation -> {
            throw closeFailure;
        }).when(mockResultSet).close();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> JdbcUtil.close(mockResultSet, true, false));

        assertSame(closeFailure, thrown.getCause());
        verify(mockStatement).close();
    }

    // The single-resource close overloads let an undeclared checked close failure escape raw, while the multi-resource
    // overloads wrap it in IllegalStateException.
    @SuppressWarnings("deprecation")
    @Test
    void singleResourceCloseWrapsUndeclaredCheckedFailureAsMultiResourceOverloadsDo() throws Exception {
        final IOException rsFailure = new IOException("rs close failed");
        final IOException stmtFailure = new IOException("stmt close failed");
        final IOException connFailure = new IOException("conn close failed");
        doAnswer(invocation -> {
            throw rsFailure;
        }).when(mockResultSet).close();
        doAnswer(invocation -> {
            throw stmtFailure;
        }).when(mockStatement).close();
        doAnswer(invocation -> {
            throw connFailure;
        }).when(mockConnection).close();

        assertSame(rsFailure, assertThrows(IllegalStateException.class, () -> JdbcUtil.close(mockResultSet)).getCause());
        assertSame(stmtFailure, assertThrows(IllegalStateException.class, () -> JdbcUtil.close(mockStatement)).getCause());
        assertSame(connFailure, assertThrows(IllegalStateException.class, () -> JdbcUtil.close(mockConnection)).getCause());
        assertSame(rsFailure, assertThrows(IllegalStateException.class, () -> JdbcUtil.close(mockResultSet, (Statement) null)).getCause());
    }

    @SuppressWarnings("deprecation")
    @Test
    void singleResourceCloseKeepsSqlAndUncheckedFailureMapping() throws Exception {
        final SQLException sqlFailure = new SQLException("close failed");
        final IllegalArgumentException runtimeFailure = new IllegalArgumentException("close failed");
        final LinkageError error = new LinkageError("close failed");
        doThrow(sqlFailure).when(mockResultSet).close();
        doThrow(runtimeFailure).when(mockStatement).close();
        doThrow(error).when(mockConnection).close();

        assertSame(sqlFailure, assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(mockResultSet)).getCause());
        assertSame(runtimeFailure, assertThrows(IllegalArgumentException.class, () -> JdbcUtil.close(mockStatement)));
        assertSame(error, assertThrows(LinkageError.class, () -> JdbcUtil.close(mockConnection)));
    }

    // getAllColumnValues and stream(rs, label) reject a null or empty label; these passed it to the driver (pgjdbc: NPE).
    @SuppressWarnings("deprecation")
    @Test
    void getColumnValueByLabelRejectsNullOrEmptyLabelBeforeReachingTheDriver() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnValue(mockResultSet, (String) null));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnValue(mockResultSet, ""));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnValue(mockResultSet, (String) null, String.class));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getColumnValue(mockResultSet, "", String.class));

        verifyNoInteractions(mockResultSet);
    }

    // closeQuietly rethrows a close Error: in the finally blocks of runQuery/runUpdate/runExecute/getColumnNamesBySelect it
    // replaced the SQLException propagating from the operation.
    @ParameterizedTest(name = "operation={0}")
    @ValueSource(ints = { 0, 1, 2 })
    void closeErrorDoesNotReplaceExecutionFailure(final int operation) throws Exception {
        final SQLException primary = new SQLException("execution failed");
        final LinkageError closeError = new LinkageError("close failed");
        doThrow(primary).when(mockPreparedStatement).executeQuery();
        doThrow(primary).when(mockPreparedStatement).executeUpdate();
        doThrow(primary).when(mockPreparedStatement).execute();
        doThrow(closeError).when(mockPreparedStatement).close();

        final Throwables.Runnable<SQLException> command = switch (operation) {
            case 0 -> () -> JdbcUtil.executeQuery(mockConnection, "SELECT 1");
            case 1 -> () -> JdbcUtil.executeUpdate(mockConnection, "UPDATE t SET a = 1");
            default -> () -> JdbcUtil.execute(mockConnection, "UPDATE t SET a = 1");
        };

        assertSame(primary, assertThrows(SQLException.class, command::run));
        assertArrayEquals(new Throwable[] { closeError }, primary.getSuppressed());
        verify(mockPreparedStatement).close();
    }

    // The same in the finally blocks of runBatchUpdate/runLargeBatchUpdate, with and without the batch's own transaction.
    @ParameterizedTest(name = "variant={0}")
    @ValueSource(ints = { 0, 1, 2, 3 })
    void closeErrorDoesNotReplaceBatchFailure(final int variant) throws Exception {
        final boolean large = variant % 2 == 1;
        final boolean autoCommit = variant >= 2;
        final SQLException primary = new SQLException("batch failed");
        final LinkageError closeError = new LinkageError("close failed");
        when(mockConnection.getAutoCommit()).thenReturn(autoCommit);
        doThrow(primary).when(mockPreparedStatement).executeBatch();
        doThrow(primary).when(mockPreparedStatement).executeLargeBatch();
        doThrow(closeError).when(mockPreparedStatement).close();
        final java.util.List<Object[]> rows = java.util.List.of(new Object[] { 1 }, new Object[] { 2 });

        assertSame(primary, assertThrows(SQLException.class, () -> {
            if (large) {
                JdbcUtil.executeLargeBatchUpdate(mockConnection, "UPDATE t SET a = ?", rows, 10);
            } else {
                JdbcUtil.executeBatchUpdate(mockConnection, "UPDATE t SET a = ?", rows, 10);
            }
        }));
        assertArrayEquals(new Throwable[] { closeError }, primary.getSuppressed());
        verify(mockPreparedStatement).close();

        if (autoCommit) {
            verify(mockConnection).rollback();
            verify(mockConnection).setAutoCommit(true);
        }
    }

    @Test
    void closeErrorStillPropagatesAfterSuccessfulUpdate() throws Exception {
        final LinkageError closeError = new LinkageError("close failed");
        when(mockPreparedStatement.executeUpdate()).thenReturn(1);
        doThrow(closeError).when(mockPreparedStatement).close();

        assertSame(closeError, assertThrows(LinkageError.class, () -> JdbcUtil.executeUpdate(mockConnection, "UPDATE t SET a = 1")));
    }

    @Test
    void closeErrorDoesNotReplaceColumnNameProbeFailure() throws Exception {
        final SQLException primary = new SQLException("metadata failed");
        final LinkageError closeError = new LinkageError("close failed");
        when(mockResultSetMetaData.getColumnCount()).thenThrow(primary);
        doThrow(closeError).when(mockPreparedStatement).close();

        assertSame(primary, assertThrows(SQLException.class, () -> JdbcUtil.getColumnNames(mockConnection, "plain_tbl")));
        assertArrayEquals(new Throwable[] { closeError }, primary.getSuppressed());
        verify(mockConnection).prepareStatement("SELECT * FROM plain_tbl WHERE 1 > 2");
    }

    // Guard: an IllegalStateException a driver throws from close is attached as it is, not mistaken for the wrapper.
    @Test
    void closeWithLookupFailureAttachesDriverIllegalStateExceptionAsIs() throws Exception {
        final SQLException lookupFailure = new SQLException("getStatement failed");
        final IllegalStateException closeFailure = new IllegalStateException("Unexpected JDBC resource close failure", new IOException("cause"));
        when(mockResultSet.getStatement()).thenThrow(lookupFailure);
        doThrow(closeFailure).when(mockResultSet).close();

        assertSame(lookupFailure, assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(mockResultSet, true, true)).getCause());
        assertArrayEquals(new Throwable[] { closeFailure }, lookupFailure.getSuppressed());
    }

    // An undeclared InterruptedException (a Kotlin SQL log handler blocking on BlockingQueue.put, a sneaky-throwing driver) was
    // logged and swallowed by the SQL log handler call, the SQL extractor, the parameter and batch cleanup and the quiet
    // closes, or wrapped by close(...), without restoring the thread's interrupt flag.
    @ParameterizedTest(name = "site={0}")
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7 })
    void swallowedOrWrappedUndeclaredInterruptRestoresInterruptFlag(final int site) throws Exception {
        final InterruptedException interrupt = new InterruptedException("interrupted");
        when(mockPreparedStatement.executeUpdate()).thenReturn(3);
        when(mockStatement.executeBatch()).thenReturn(new int[] { 1 });
        when(mockStatement.executeLargeBatch()).thenReturn(new long[] { 1L });

        try {
            switch (site) {
                case 0 -> {
                    JdbcUtil.setSqlLogHandler((sql, start, end) -> sneakyThrow(interrupt));
                    assertEquals(3, JdbcUtil.executeUpdate(mockPreparedStatement));
                }
                case 1 -> {
                    JdbcUtil.setSqlLogHandler((sql, start, end) -> {
                    });
                    JdbcUtil.setSqlExtractor(stmt -> {
                        sneakyThrow(interrupt);
                        return null;
                    });
                    assertEquals(3, JdbcUtil.executeUpdate(mockPreparedStatement));
                }
                case 2 -> {
                    doAnswer(invocation -> {
                        throw interrupt;
                    }).when(mockPreparedStatement).clearParameters();
                    assertEquals(3, JdbcUtil.executeUpdate(mockPreparedStatement));
                }
                case 3, 4 -> {
                    doAnswer(invocation -> {
                        throw interrupt;
                    }).when(mockStatement).clearBatch();

                    if (site == 3) {
                        assertArrayEquals(new int[] { 1 }, JdbcUtil.executeBatch(mockStatement));
                    } else {
                        assertArrayEquals(new long[] { 1L }, JdbcUtil.executeLargeBatch(mockStatement));
                    }
                }
                case 5 -> {
                    doAnswer(invocation -> {
                        throw interrupt;
                    }).when(mockResultSet).close();
                    JdbcUtil.closeQuietly(mockResultSet);
                }
                case 6 -> {
                    when(mockResultSet.getStatement()).thenAnswer(invocation -> {
                        throw interrupt;
                    });
                    JdbcUtil.closeQuietly(mockResultSet, true);
                    verify(mockResultSet).close();
                }
                default -> {
                    doAnswer(invocation -> {
                        throw interrupt;
                    }).when(mockResultSet).close();
                    assertSame(interrupt, assertThrows(IllegalStateException.class, () -> JdbcUtil.close(mockResultSet)).getCause());
                }
            }

            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // don't leak the flag into later tests
        }
    }

    // The cleanup catches took Exception (and Error in the batch cleanup): a sneaky-thrown Throwable that is neither escaped,
    // replacing the batch failure or turning a successful statement into a failure.
    @ParameterizedTest(name = "large={0}")
    @ValueSource(booleans = { false, true })
    void rawThrowableFromBatchCleanupKeepsBatchOutcome(final boolean large) throws Exception {
        final Throwable raw = new Throwable("raw clear failure");
        doAnswer(invocation -> {
            throw raw;
        }).when(mockStatement).clearBatch();
        when(mockStatement.executeBatch()).thenReturn(new int[] { 2 });
        when(mockStatement.executeLargeBatch()).thenReturn(new long[] { 2L });

        if (large) {
            assertArrayEquals(new long[] { 2L }, JdbcUtil.executeLargeBatch(mockStatement));
        } else {
            assertArrayEquals(new int[] { 2 }, JdbcUtil.executeBatch(mockStatement));
        }

        final SQLException primary = new SQLException("batch failed");
        doThrow(primary).when(mockStatement).executeBatch();
        doThrow(primary).when(mockStatement).executeLargeBatch();

        assertSame(primary, assertThrows(SQLException.class, () -> {
            if (large) {
                JdbcUtil.executeLargeBatch(mockStatement);
            } else {
                JdbcUtil.executeBatch(mockStatement);
            }
        }));
        assertArrayEquals(new Throwable[] { raw }, primary.getSuppressed());
    }

    @Test
    void rawThrowableFromParameterCleanupOrSqlLogHandlerKeepsStatementOutcome() throws Exception {
        final Throwable raw = new Throwable("raw failure");
        doAnswer(invocation -> {
            throw raw;
        }).when(mockPreparedStatement).clearParameters();
        JdbcUtil.setSqlLogHandler((sql, start, end) -> sneakyThrow(raw));
        when(mockPreparedStatement.executeUpdate()).thenReturn(4);

        assertEquals(4, JdbcUtil.executeUpdate(mockPreparedStatement));

        final SQLException primary = new SQLException("execution failed");
        doThrow(primary).when(mockPreparedStatement).execute();

        assertSame(primary, assertThrows(SQLException.class, () -> JdbcUtil.execute(mockPreparedStatement)));

        JdbcUtil.setSqlLogHandler((sql, start, end) -> {
        });
        JdbcUtil.setSqlExtractor(stmt -> {
            sneakyThrow(raw);
            return null;
        });

        assertEquals(4, JdbcUtil.executeUpdate(mockPreparedStatement));
    }

    // releaseConnection closes a connection directly (outside Spring's transaction support) through closeQuietly, which rethrows
    // a close Error: in the finally blocks of the DataSource overloads it replaced the failure propagating from the operation.
    @ParameterizedTest(name = "operation={0}")
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7 })
    void connectionCloseErrorDoesNotReplaceDataSourceOperationFailure(final int operation) throws Exception {
        final SQLException primary = new SQLException("operation failed");
        final Error closeError = operation % 2 == 0 ? new LinkageError("close failed") : new AssertionError("close failed");
        doThrow(primary).when(mockPreparedStatement).executeQuery();
        doThrow(primary).when(mockPreparedStatement).executeUpdate();
        doThrow(primary).when(mockPreparedStatement).execute();
        doThrow(primary).when(mockPreparedStatement).executeBatch();
        doThrow(primary).when(mockPreparedStatement).executeLargeBatch();
        doThrow(closeError).when(mockConnection).close();
        final List<Object[]> oneRow = List.<Object[]> of(new Object[] { 1 });

        if (operation == 5 || operation == 7) {
            when(mockConnection.prepareStatement(anyString())).thenThrow(primary);
            when(mockConnection.prepareStatement(anyString(), anyInt(), anyInt())).thenThrow(primary);
        } else if (operation == 6) {
            when(mockConnection.prepareCall(anyString())).thenThrow(primary);
            when(mockConnection.prepareCall(anyString(), anyInt(), anyInt())).thenThrow(primary);
        }

        final Throwables.Runnable<SQLException> command = switch (operation) {
            case 0 -> () -> JdbcUtil.executeQuery(mockDataSource, "SELECT 1");
            case 1 -> () -> JdbcUtil.executeUpdate(mockDataSource, "UPDATE t SET a = 1");
            case 2 -> () -> JdbcUtil.execute(mockDataSource, "UPDATE t SET a = 1");
            case 3 -> () -> JdbcUtil.executeBatchUpdate(mockDataSource, "UPDATE t SET a = ?", oneRow);
            case 4 -> () -> JdbcUtil.executeLargeBatchUpdate(mockDataSource, "UPDATE t SET a = ?", oneRow);
            case 5 -> () -> JdbcUtil.prepareQuery(mockDataSource, "SELECT 1");
            case 6 -> () -> JdbcUtil.prepareCallableQuery(mockDataSource, "{call p()}");
            default -> () -> JdbcUtil.prepareNamedQuery(mockDataSource, "SELECT * FROM t WHERE a = :a");
        };

        // Outside Spring's transaction support, as without Spring: Spring's DataSourceUtils logs close failures itself.
        assertSame(primary, assertThrows(SQLException.class, () -> JdbcUtil.runIgnoringSpringTransaction(command)));
        assertArrayEquals(new Throwable[] { closeError }, primary.getSuppressed());
        verify(mockConnection).close();
    }

    @ParameterizedTest(name = "tableExists={0}")
    @ValueSource(booleans = { false, true })
    void connectionCloseErrorDoesNotReplaceDataSourceMetadataFailure(final boolean tableExists) throws Exception {
        final SQLException primary = new SQLException("metadata failed");
        final LinkageError closeError = new LinkageError("close failed");
        when(mockConnection.getMetaData()).thenThrow(primary);
        doThrow(closeError).when(mockConnection).close();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.runIgnoringSpringTransaction(() -> {
            if (tableExists) {
                JdbcUtil.tableExists(mockDataSource, "users");
            } else {
                JdbcUtil.getDBProductInfo(mockDataSource);
            }
        }));

        assertSame(primary, thrown.getCause());
        assertArrayEquals(new Throwable[] { closeError }, thrown.getSuppressed());
        verify(mockConnection).close();
    }

    @Test
    void connectionCloseErrorStillPropagatesAfterSuccessfulDataSourceUpdate() throws Exception {
        final LinkageError closeError = new LinkageError("close failed");
        when(mockPreparedStatement.executeUpdate()).thenReturn(1);
        doThrow(closeError).when(mockConnection).close();

        assertSame(closeError, assertThrows(LinkageError.class,
                () -> JdbcUtil.runIgnoringSpringTransaction(() -> JdbcUtil.executeUpdate(mockDataSource, "UPDATE t SET a = 1"))));
    }

    // getOutParameters closed an OUT-parameter result set with closeQuietly in a finally block: a close Error replaced the
    // failure of reading it.
    @Test
    void closeErrorDoesNotReplaceOutParameterResultSetReadFailure() throws Exception {
        final SQLException primary = new SQLException("read failed");
        final LinkageError closeError = new LinkageError("close failed");
        when(mockCallableStatement.getObject(1)).thenReturn(mockResultSet);
        when(mockResultSet.getMetaData()).thenThrow(primary);
        doThrow(closeError).when(mockResultSet).close();

        assertSame(primary,
                assertThrows(SQLException.class, () -> JdbcUtil.getOutParameters(mockCallableStatement, List.of(Jdbc.OutParam.of(1, Types.REF_CURSOR)))));
        assertArrayEquals(new Throwable[] { closeError }, primary.getSuppressed());
        verify(mockResultSet).close();
    }

    // runBatchUpdate's own transaction: the commit succeeded but restoring auto-commit failed (restoreFailure), or the commit
    // failed (rolled back), and closing the statement then threw an Error, which must not replace that failure.
    @ParameterizedTest(name = "variant={0}")
    @ValueSource(ints = { 0, 1, 2, 3 })
    void closeErrorDoesNotReplaceBatchCompletionFailure(final int variant) throws Exception {
        final boolean large = variant % 2 == 1;
        final boolean commitFails = variant >= 2;
        final SQLException primary = new SQLException(commitFails ? "commit failed" : "restore failed");
        final LinkageError closeError = new LinkageError("close failed");
        when(mockConnection.getAutoCommit()).thenReturn(true);
        when(mockPreparedStatement.executeBatch()).thenReturn(new int[] { 1, 1 });
        when(mockPreparedStatement.executeLargeBatch()).thenReturn(new long[] { 1L, 1L });

        if (commitFails) {
            doThrow(primary).when(mockConnection).commit();
        } else {
            doThrow(primary).when(mockConnection).setAutoCommit(true);
        }

        doThrow(closeError).when(mockPreparedStatement).close();
        final List<Object[]> rows = List.of(new Object[] { 1 }, new Object[] { 2 });

        assertSame(primary, assertThrows(SQLException.class, () -> {
            if (large) {
                JdbcUtil.executeLargeBatchUpdate(mockConnection, "UPDATE t SET a = ?", rows, 10);
            } else {
                JdbcUtil.executeBatchUpdate(mockConnection, "UPDATE t SET a = ?", rows, 10);
            }
        }));
        assertArrayEquals(new Throwable[] { closeError }, primary.getSuppressed());
        verify(mockPreparedStatement).close();
        verify(mockConnection).setAutoCommit(true);

        if (commitFails) {
            verify(mockConnection).rollback();
        }
    }

    // close(rs, true, true): the lookup of the connection failed after the statement was located, and closing both the result
    // set and the statement failed: the first close failure (with the second attached to it) is attached to the lookup failure.
    @Test
    void closeWithLookupFailureAttachesNestedCloseFailures() throws Exception {
        final SQLException lookupFailure = new SQLException("getConnection failed");
        final SQLException rsFailure = new SQLException("rs close failed");
        final IOException stmtFailure = new IOException("stmt close failed");
        when(mockResultSet.getStatement()).thenReturn(mockStatement);
        when(mockStatement.getConnection()).thenThrow(lookupFailure);
        doThrow(rsFailure).when(mockResultSet).close();
        doAnswer(invocation -> {
            throw stmtFailure;
        }).when(mockStatement).close();

        assertSame(lookupFailure, assertThrows(UncheckedSQLException.class, () -> JdbcUtil.close(mockResultSet, true, true)).getCause());
        assertArrayEquals(new Throwable[] { rsFailure }, lookupFailure.getSuppressed());
        assertArrayEquals(new Throwable[] { stmtFailure }, rsFailure.getSuppressed());
    }
}
