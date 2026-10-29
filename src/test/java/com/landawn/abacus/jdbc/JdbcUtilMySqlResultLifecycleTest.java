package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.landawn.abacus.util.stream.ObjIteratorEx;
import com.mysql.cj.Query;
import com.mysql.cj.conf.RuntimeProperty;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.jdbc.StatementImpl;
import com.mysql.cj.jdbc.result.ResultSetImpl;
import com.mysql.cj.protocol.Resultset;
import com.mysql.cj.protocol.ResultsetRows;

@Tag("2025")
class JdbcUtilMySqlResultLifecycleTest {

    // Use the pinned driver's real ResultSet.close, close notification, and getMoreResults methods. Only rows are simulated:
    // a streaming result discovers its successor at EOF, while a buffered result already has its successor when delivered.
    // A streamed result is read to its end but not closed: against a server, Connector/J fails to close it while the
    // statement streams its successor, and the advance releases it instead. Only the last one is closed, at the end.
    @ParameterizedTest(name = "streaming={0}, fullyRead={1}, wrapped={2}")
    @CsvSource({ "true,false,false", "true,true,false", "false,false,false", "false,true,false", "true,false,true", "true,true,true", "false,false,true",
            "false,true,true" })
    void extractionPreservesTrailingResultsWithoutTrackingClosedResults(final boolean streaming, final boolean fullyRead, final boolean wrapped)
            throws Exception {
        final JdbcConnection connection = mock(JdbcConnection.class);
        when(connection.getConnectionLock()).thenReturn(new ReentrantLock());
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(metadata.supportsMultipleOpenResults()).thenReturn(true);
        when(metadata.getDriverName()).thenReturn(com.mysql.cj.Constants.CJ_NAME);
        when(connection.getMetaData()).thenReturn(metadata);

        final Query query = mock(Query.class);
        when(query.getResultType()).thenReturn(Resultset.Type.FORWARD_ONLY);
        when(query.getResultFetchSize()).thenReturn(streaming ? Integer.MIN_VALUE : 0);
        @SuppressWarnings("unchecked")
        final RuntimeProperty<Boolean> dontTrack = mock(RuntimeProperty.class);
        when(dontTrack.getValue()).thenReturn(false);

        final StatementImpl statement = mock(StatementImpl.class, CALLS_REAL_METHODS);
        set(statement, "connection", connection);
        set(statement, "query", query);
        set(statement, "resultSetConcurrency", ResultSet.CONCUR_READ_ONLY);
        set(statement, "dontTrackOpenResources", dontTrack);
        set(statement, "openResultSets", new HashSet<>());

        final Cursor second = new Cursor(connection, statement, 3, streaming, null);
        final Cursor first = new Cursor(connection, statement, 100, streaming, second.result);
        set(statement, "results", first.result);

        // A pool wrapper exposes only JDBC interfaces, while connection metadata still identifies the actual driver.
        final Statement exposedStatement = wrapped
                ? (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Statement.class }, (proxy, method, args) -> {
                    try {
                        return method.invoke(statement, args);
                    } catch (final InvocationTargetException e) {
                        throw e.getCause();
                    }
                })
                : statement;
        final ObjIteratorEx<ResultSet> iterator = JdbcUtil.iterateAllResultSets(exposedStatement, true);
        try {
            final ResultSet delivered = iterator.next();
            assertEquals(0, first.position, "Delivering a result must not consume its rows");
            final int extracted = JdbcUtil.extractAndCloseResultSet(delivered, (Jdbc.ResultExtractor<Integer>) rs -> {
                int count = 0;
                while (rs.next()) {
                    count++;
                    if (!fullyRead) {
                        break;
                    }
                }
                return count;
            });

            assertEquals(fullyRead ? 100 : 1, extracted);
            assertTrue(delivered.isClosed());
            assertEquals(!streaming, first.result.isClosed(), "Only buffered results are closed by the delivered wrapper");
            assertEquals(streaming || fullyRead ? 100 : 1, first.position, "Only streaming results need unread rows drained before close");
            assertTrue(iterator.hasNext(), "Closing an incompletely read streaming result must not lose its successor");
            assertEquals(0, statement.getOpenResultSetCount(), "Advancement must not register an already-closed result as still open");

            assertEquals(3, JdbcUtil.extractAndCloseResultSet(iterator.next(), (Jdbc.ResultExtractor<Integer>) rs -> {
                int count = 0;
                while (rs.next()) {
                    count++;
                }
                return count;
            }));
            assertEquals(!streaming, second.result.isClosed());
            assertEquals(3, second.position);
            assertFalse(iterator.hasNext());
            // Once no results remain, the last streamed result is closed too; earlier ones stay released by the advance.
            assertTrue(second.result.isClosed());
            assertEquals(!streaming, first.result.isClosed());
            assertEquals(0, statement.getOpenResultSetCount());
            verify(statement, never()).getMoreResults(Statement.KEEP_CURRENT_RESULT);
        } finally {
            iterator.closeResource();
        }
    }

    private static final class Cursor {
        final ResultSetImpl result = mock(ResultSetImpl.class, CALLS_REAL_METHODS);
        final AtomicReference<Resultset> next = new AtomicReference<>();
        final int rowCount;
        final ResultSetImpl successor;
        int position;

        Cursor(final JdbcConnection connection, final StatementImpl statement, final int rowCount, final boolean streaming, final ResultSetImpl successor)
                throws Exception {
            this.rowCount = rowCount;
            this.successor = successor;
            if (!streaming) {
                next.set(successor);
            }
            final ResultsetRows rows = mock(ResultsetRows.class);
            doAnswer(invocation -> {
                if (streaming) {
                    while (readNext()) {
                        // Match ResultsetRowsStreaming.close(): drain to EOF and discover the following result.
                    }
                }
                return null;
            }).when(rows).close();
            doAnswer(invocation -> readNext()).when(result).next();
            doAnswer(invocation -> next.get()).when(result).getNextResultset();
            doAnswer(invocation -> {
                next.set(null);
                return null;
            }).when(result).clearNextResultset();
            set(result, "connection", connection);
            set(result, "owningStatement", statement);
            set(result, "rowData", rows);
        }

        private boolean readNext() throws SQLException {
            if (result.isClosed()) {
                throw new SQLException("ResultSet is closed");
            }
            if (position < rowCount) {
                position++;
                return true;
            }
            next.set(successor);
            return false;
        }
    }

    private static void set(final Object target, final String name, final Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (final NoSuchFieldException ignored) {
                // ResultSetImpl inherits rowData from NativeResultset.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
