package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalInt;

class PreparedQueryTest extends PreparedQueryTestSupport {

    @Test
    public void testAllMatch() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(10, 15);

        boolean result = query.allMatch(rs -> rs.getInt(1) > 5);

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testAllMatchBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("yes", "yes");

        boolean result = query.allMatch((rs, labels) -> rs.getString(1).equals("yes"));

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testAllMatchEmpty() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        boolean result = query.allMatch(rs -> rs.getInt(1) > 5);

        assertTrue(result); // Empty set matches all
        verify(mockResultSet).close();
    }

    @Test
    public void testAllMatchFalse() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(10, 3);

        boolean result = query.allMatch(rs -> rs.getInt(1) > 5);

        assertFalse(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testAnyMatch() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(5, 10);

        boolean result = query.anyMatch(rs -> rs.getInt(1) > 8);

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testAnyMatchBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString(1)).thenReturn("no", "yes");

        boolean result = query.anyMatch((rs, labels) -> rs.getString(1).equals("yes"));

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testAnyMatchFalse() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(5, 7);

        boolean result = query.anyMatch(rs -> rs.getInt(1) > 10);

        assertFalse(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testcallAsync() throws Exception {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getInt(1)).thenReturn(42);

        ContinuableFuture<OptionalInt> future = query.callAsync(q -> q.queryForInt());

        OptionalInt result = future.get();
        assertTrue(result.isPresent());
        assertEquals(42, result.orElseThrow());
    }

    @Test
    public void testcallAsyncCancelledBeforeStartClosesStatement() throws SQLException {
        final AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        final AtomicBoolean actionRan = new AtomicBoolean();
        final ContinuableFuture<Integer> future = query.callAsync(q -> {
            actionRan.set(true);
            return 1;
        }, queuedTask::set);

        assertTrue(future.cancel(false));
        assertTrue(query.isClosed);
        verify(mockStmt).close();

        queuedTask.get().run();
        assertFalse(actionRan.get());
        verify(mockStmt).close();
    }

    @Test
    public void testcallAsyncRejectedByExecutorClosesStatement() throws SQLException {
        final RejectedExecutionException rejection = new RejectedExecutionException("rejected");

        final RejectedExecutionException thrown = assertThrows(RejectedExecutionException.class, () -> query.callAsync(q -> 1, command -> {
            throw rejection;
        }));

        assertSame(rejection, thrown);
        verify(mockStmt).close();
    }

    @Test
    public void testcallAsyncWithExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("async");

        ContinuableFuture<Nullable<String>> future = query.callAsync(q -> q.queryForString(), executor);

        Nullable<String> result = future.get();
        assertTrue(result.isPresent());
        assertEquals("async", result.orElse(null));
    }

    @Test
    public void testConfigStmt() throws SQLException {
        PreparedQuery result = query.configureStatement(stmt -> {
            stmt.setFetchSize(100);
            stmt.setQueryTimeout(60);
        });
        assertSame(query, result);
        verify(mockStmt).setFetchSize(100);
        verify(mockStmt).setQueryTimeout(60);
    }

    @Test
    public void testConfigStmtBiConsumer() throws SQLException {
        PreparedQuery result = query.configureStatement((q, stmt) -> {
            q.setFetchSize(100);
            stmt.setPoolable(false);
        });
        assertSame(query, result);
        verify(mockStmt).setFetchSize(100);
        verify(mockStmt).setPoolable(false);
    }

    @Test
    public void testConstructorRejectsNullStatement() {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(null));

        assertTrue(thrown.getMessage().contains("stmt"));
    }

    @Test
    public void testIfExistsBiRowConsumer() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        List<Integer> captured = new ArrayList<>();
        query.ifExists((rs, labels) -> captured.add(labels.size()));

        assertEquals(Arrays.asList(1), captured);
        verify(mockResultSet).close();
    }

    @Test
    public void testIfExistsOrElseBiRowConsumer() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        boolean[] existsCalled = { false };
        boolean[] elseCalled = { false };

        query.ifExistsOrElse((rs, labels) -> existsCalled[0] = true, () -> elseCalled[0] = true);

        assertTrue(existsCalled[0]);
        assertFalse(elseCalled[0]);
        verify(mockResultSet).close();
    }

    @Test
    public void testIfExistsOrElseRowConsumer() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        boolean[] existsCalled = { false };
        boolean[] elseCalled = { false };

        query.ifExistsOrElse(rs -> existsCalled[0] = true, () -> elseCalled[0] = true);

        assertTrue(existsCalled[0]);
        assertFalse(elseCalled[0]);
        verify(mockResultSet).close();
    }

    @Test
    public void testIfExistsOrElseRowConsumerElsePath() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        boolean[] existsCalled = { false };
        boolean[] elseCalled = { false };

        query.ifExistsOrElse(rs -> existsCalled[0] = true, () -> elseCalled[0] = true);

        assertFalse(existsCalled[0]);
        assertTrue(elseCalled[0]);
        verify(mockResultSet).close();
    }

    @Test
    public void testIfExistsRowConsumer() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("exists");

        List<String> captured = new ArrayList<>();
        query.ifExists(rs -> captured.add(rs.getString(1)));

        assertEquals(Arrays.asList("exists"), captured);
        verify(mockResultSet).close();
    }

    @Test
    public void testInsert() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(1);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getObject(1)).thenReturn(123L);

        Optional<Long> result = query.insert();

        assertTrue(result.isPresent());
        assertEquals(123L, result.get());
        verify(mockStmt).executeUpdate();
        verify(mockResultSet).close();
    }

    @Test
    public void testInsertNoKey() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(1);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        Optional<Long> result = query.insert();

        assertFalse(result.isPresent());
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testInsertWithBiExtractor() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(1);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("code");

        Optional<Map<String, Object>> result = query.insert((rs, labels) -> {
            Map<String, Object> map = new HashMap<>();
            for (String label : labels) {
                map.put(label, rs.getObject(label));
            }
            return map;
        });

        assertTrue(result.isPresent());
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testInsertWithExtractor() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(1);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("generated-uuid");

        Optional<String> result = query.insert(rs -> rs.getString(1));

        assertTrue(result.isPresent());
        assertEquals("generated-uuid", result.get());
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testLargeBatchUpdate() throws SQLException {
        long[] expected = { 1000L, 2000L, 3000L };
        when(mockStmt.executeLargeBatch()).thenReturn(expected);

        long[] result = query.largeBatchUpdate();

        assertArrayEquals(expected, result);
        verify(mockStmt).executeLargeBatch();
    }

    @Test
    public void testLargeUpdate() throws SQLException {
        when(mockStmt.executeLargeUpdate()).thenReturn(1000000L);

        long result = query.largeUpdate();

        assertEquals(1000000L, result);
        verify(mockStmt).executeLargeUpdate();
    }

    @Test
    public void testNoAutoCloseWhenDisabled() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        query.closeAfterExecution(false).queryForInt();

        assertFalse(query.isClosed);
        verify(mockStmt, never()).close();
    }

    @Test
    public void testNoneMatch() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(5, 7);

        boolean result = query.noneMatch(rs -> rs.getInt(1) > 10);

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testNoneMatchBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("no", "no");

        boolean result = query.noneMatch((rs, labels) -> rs.getString(1).equals("yes"));

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testNoneMatchFalse() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getInt(1)).thenReturn(5, 15);

        boolean result = query.noneMatch(rs -> rs.getInt(1) > 10);

        assertFalse(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testNullParameterValidation() {
        // A fresh (open) query per assertion: a failed null-check closes the query (see
        // AbstractQuery#checkArgNotNull), and assertNotClosed() now runs before the arg check, so a
        // null arg on an already-closed query would surface as IllegalStateException instead.
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).setParameters((Jdbc.ParametersSetter<PreparedStatement>) null));
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).queryForSingleValue((Class<?>) null));
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).query((Jdbc.ResultExtractor<?>) null));
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).list((Jdbc.RowMapper<?>) null));
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).forEach((Jdbc.RowConsumer) null));
    }

    @Test
    public void testOperationsOnClosedQuery() throws SQLException {
        query.close();

        // Input binding delegates to the driver; this mock accepts the value after close.
        assertSame(query, query.setString(1, "test"));
        assertThrows(IllegalStateException.class, () -> query.queryForInt());
        assertThrows(IllegalStateException.class, () -> query.update());
        assertThrows(IllegalStateException.class, () -> query.execute());
        assertThrows(IllegalStateException.class, () -> query.list());
        assertTrue(query.isClosed);
        verify(mockStmt).setString(1, "test");
    }

    @Test
    public void testResetStatementPropertiesOnClose() throws SQLException {
        when(mockStmt.getFetchDirection()).thenReturn(ResultSet.FETCH_FORWARD);
        when(mockStmt.getFetchSize()).thenReturn(100);
        when(mockStmt.getMaxFieldSize()).thenReturn(1024);
        when(mockStmt.getQueryTimeout()).thenReturn(30);

        query.setFetchDirection(FetchDirection.REVERSE).setFetchSize(500).setMaxFieldSize(2048).setQueryTimeout(60);

        query.close();

        assertTrue(query.isClosed);
        verify(mockStmt).setFetchDirection(ResultSet.FETCH_FORWARD);
        verify(mockStmt, times(2)).setFetchSize(anyInt()); // Once for set, once for reset
        verify(mockStmt, times(2)).setMaxFieldSize(anyInt());
        verify(mockStmt, times(2)).setQueryTimeout(anyInt());
    }

    @Test
    public void testrunAsync() throws Exception {
        when(mockStmt.executeUpdate()).thenReturn(1);

        List<Integer> captured = new ArrayList<>();
        ContinuableFuture<Void> future = query.runAsync(q -> {
            int updated = q.update();
            captured.add(updated);
        });

        future.get();
        assertEquals(Arrays.asList(1), captured);
    }

    @Test
    public void testrunAsyncCancelledBeforeStartClosesStatement() throws SQLException {
        final AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        final AtomicBoolean actionRan = new AtomicBoolean();
        final ContinuableFuture<Void> future = query.runAsync(q -> actionRan.set(true), queuedTask::set);

        assertTrue(future.cancel(false));
        assertTrue(query.isClosed);
        verify(mockStmt).close();

        queuedTask.get().run();
        assertFalse(actionRan.get());
        verify(mockStmt).close();
    }

    @Test
    public void testrunAsyncRejectedByExecutorClosesStatement() throws SQLException {
        final RejectedExecutionException rejection = new RejectedExecutionException("rejected");

        final RejectedExecutionException thrown = assertThrows(RejectedExecutionException.class, () -> query.runAsync(q -> {
            // The rejected task must never execute.
        }, command -> {
            throw rejection;
        }));

        assertSame(rejection, thrown);
        verify(mockStmt).close();
    }

    @Test
    public void testrunAsyncWithExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        when(mockStmt.execute()).thenReturn(true);

        List<Boolean> captured = new ArrayList<>();
        ContinuableFuture<Void> future = query.runAsync(q -> {
            boolean hasResultSet = q.execute();
            captured.add(hasResultSet);
        }, executor);

        future.get();
        assertEquals(Arrays.asList(true), captured);
    }
}
