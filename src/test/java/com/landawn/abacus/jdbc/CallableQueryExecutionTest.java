package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CallableQueryExecutionTest extends CallableQueryTestSupport {

    // --- executeQuery() returns the first ResultSet (L2104) ---

    @Test
    public void testExecuteQuery_ReturnsFirstResultSet() throws SQLException {
        ResultSet mockRs = Mockito.mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(mockRs);
        ResultSet rs = callableQuery.executeQuery();
        // Wrapped for parity with PreparedQuery/NamedQuery reads: values are normalized by ResultSetProxy.
        assertTrue(rs instanceof com.landawn.abacus.jdbc.ResultSetProxy);
        rs.next();
        verify(mockRs).next(); // the proxy delegates to the procedure's first ResultSet
    }

    // --- executeQuery() else-branch: update count loop (L2106, L2107) ---

    @Test
    public void testExecuteQuery_UpdateCountLoop_ElseBranch() throws SQLException {
        // execute()=false so ret=false; getUpdateCount()=5 first → enters while, hits else branch
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(5, -1);
        when(callableStatement.getMoreResults()).thenReturn(false);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<?, Jdbc.OutParamResult> result = callableQuery.queryAndGetOutParameters();
        assertNotNull(result);
        verify(callableStatement).getMoreResults();
    }

    // executeQuery() with isFetchDirectionSet already true (L2102 pc)
    @Test
    public void testExecuteQuery_FetchDirectionAlreadySet() throws SQLException {
        callableQuery.setFetchDirection(FetchDirection.FORWARD);
        // reset mock to track only executeQuery() calls
        Mockito.reset(callableStatement);
        Connection connection = Mockito.mock(Connection.class);
        when(callableStatement.getConnection()).thenReturn(connection);
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        ResultSet rs = callableQuery.executeQuery();
        assertNotNull(rs);
        assertFalse(rs.next());
        // setFetchDirection should NOT be called by executeQuery since it was already set
        verify(callableStatement, Mockito.never()).setFetchDirection(ResultSet.FETCH_FORWARD);
    }

    // Regression: CallableQuery.executeQuery() override (which walks past update counts to find the
    // first ResultSet) used to set FETCH_FORWARD without capturing the prior driver-default fetch
    // direction. closeStatement() then couldn't restore it on pooled CallableStatement reuse.
    // Fix mirrors the AbstractQuery.executeQuery fix — capture before mutating.
    @Test
    public void testExecuteQuery_CapturesDefaultFetchDirectionForRestoration() throws Exception {
        when(callableStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_REVERSE);
        // Make the result-walk terminate quickly: execute returns false (no first RS), then
        // getUpdateCount -1 means no more results, loop exits returning null.
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);

        // executeQuery is protected; invoke via reflection.
        final java.lang.reflect.Method m = CallableQuery.class.getDeclaredMethod("executeQuery");
        m.setAccessible(true);
        m.invoke(callableQuery);

        // The fix captures the prior direction before forcing FORWARD.
        verify(callableStatement).getFetchDirection();
        verify(callableStatement).setFetchDirection(ResultSet.FETCH_FORWARD);

        // close() must restore the original FETCH_REVERSE direction. Pre-fix the capture never
        // happened and the restoration in closeStatement was a no-op (default == -1).
        callableQuery.close();
        verify(callableStatement).setFetchDirection(ResultSet.FETCH_REVERSE);
    }

    // --- executeQuery() returns an empty ResultSet when no result sets (L2096-2111) ---

    @Test
    public void testExecuteQuery_ReturnsEmptyResultSetWhenNoResultSet() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        // isFetchDirectionSet=false so setFetchDirection is called first
        ResultSet rs = callableQuery.executeQuery();
        assertNotNull(rs);
        assertFalse(rs.next());
        assertTrue(rs.equals(rs));
        assertEquals(System.identityHashCode(rs), rs.hashCode());
        assertTrue(rs.toString().contains("did not return a ResultSet"));
        verify(callableStatement).setFetchDirection(ResultSet.FETCH_FORWARD);
    }

    @Test
    public void testExecuteQuery_SkipsNullResultSetClaimAndFindsLaterResultSet() throws SQLException {
        final ResultSet laterResultSet = mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        when(callableStatement.getResultSet()).thenReturn(null, laterResultSet);
        when(callableStatement.getMoreResults()).thenReturn(true);

        final ResultSet actual = callableQuery.executeQuery();

        assertNotNull(actual);
        assertNotSame(laterResultSet, actual);
        assertFalse(actual.next());
        verify(callableStatement).getMoreResults();
        verify(callableStatement, Mockito.times(2)).getResultSet();
        verify(laterResultSet).next();
    }

    // --- executeThenApply(Function<CallableStatement>) (L2140) ---

    @Test
    public void testExecuteThenApply_WithFunction() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        String result = callableQuery.executeThenApply(stmt -> "done");
        assertEquals("done", result);
        verify(callableStatement).execute();
    }

    // --- executeThenApply(BiFunction<CallableStatement, Boolean>) (L2174) ---

    @Test
    public void testExecuteThenApply_WithBiFunction() throws SQLException {
        when(callableStatement.execute()).thenReturn(true);
        Boolean result = callableQuery.executeThenApply((stmt, isResultSet) -> isResultSet);
        assertTrue(result);
        verify(callableStatement).execute();
    }

    @Test
    public void testExecuteThenApplyCannotClearRegisteredOutParameters() throws SQLException {
        when(callableStatement.execute()).thenReturn(false, false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.closeAfterExecution(false).registerOutParameter(1, Types.INTEGER);

        callableQuery.executeThenApply((stmt, definitions, isResultSet) -> {
            definitions.clear();
            return null;
        });

        final Jdbc.OutParamResult result = callableQuery.executeAndGetOutParameters();
        assertEquals(1, result.getOutParams().size());
        assertEquals(1, result.getOutParams().get(0).parameterIndex());
    }

    // executeThenApply(TriFunction) without registered outParams (L2234 pc)
    @Test
    public void testExecuteThenApply_TriFunction_NoOutParams() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        String result = callableQuery.executeThenApply((stmt, outParams, isResultSet) -> {
            assertEquals(0, outParams.size());
            return "no-outparams";
        });
        assertEquals("no-outparams", result);
    }

    // --- executeThenAccept(Consumer<CallableStatement>) (L2264) ---

    @Test
    public void testExecuteThenAccept_WithConsumer() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        final boolean[] called = { false };
        callableQuery.executeThenAccept(stmt -> called[0] = true);
        assertTrue(called[0]);
        verify(callableStatement).execute();
    }

    // --- executeThenAccept(BiConsumer<CallableStatement, Boolean>) (L2295) ---

    @Test
    public void testExecuteThenAccept_WithBiConsumer() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        final boolean[] called = { false };
        callableQuery.executeThenAccept((stmt, isResultSet) -> called[0] = true);
        assertTrue(called[0]);
        verify(callableStatement).execute();
    }

    // --- executeThenAccept(TriConsumer<CallableStatement, OutParams, Boolean>) (L2334) ---

    @Test
    public void testExecuteThenAccept_WithTriConsumer() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        final boolean[] called = { false };
        callableQuery.executeThenAccept((stmt, outParams, isResultSet) -> called[0] = true);
        assertTrue(called[0]);
    }

    // executeThenAccept(TriConsumer) without registered outParams (L2346 pc)
    @Test
    public void testExecuteThenAccept_TriConsumer_NoOutParams() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        final boolean[] called = { false };
        callableQuery.executeThenAccept((stmt, outParams, isResultSet) -> {
            assertEquals(0, outParams.size());
            called[0] = true;
        });
        assertTrue(called[0]);
    }

    // --- executeAndGetOutParameters() (L2374) ---

    @Test
    public void testExecuteAndGetOutParameters() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        // Real drivers return -1 once results are drained; without this stub the post-execute
        // drainRemainingResultsForOutParams() loop hangs (Mockito's default int return is 0).
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        Jdbc.OutParamResult result = callableQuery.executeAndGetOutParameters();
        assertNotNull(result);
    }

    // Regression: executeAndGetOutParameters used to call JdbcUtil.getOutParameters immediately
    // after cstmt.execute(), without draining any side-effect result sets / update counts the
    // procedure may have produced. Per JDBC spec, OUT params are only guaranteed final once all
    // results are consumed — SQL Server and Oracle in particular returned stale values pre-fix.
    @Test
    public void testExecuteAndGetOutParameters_DrainsRemainingResultsBeforeReadingOutParams() throws SQLException {
        // Simulate procedure that produced one trailing update count before the drain finds nothing.
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getMoreResults()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(7, -1);

        callableQuery.registerOutParameter(1, Types.INTEGER);
        when(callableStatement.getInt(1)).thenReturn(123);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(callableStatement);
        Jdbc.OutParamResult result = callableQuery.executeAndGetOutParameters();

        assertNotNull(result);
        assertEquals(Integer.valueOf(123), result.getOutParamValue(1));
        // The fix drains before reading OUT params: getMoreResults must be called BEFORE getInt(1).
        inOrder.verify(callableStatement).execute();
        inOrder.verify(callableStatement, org.mockito.Mockito.atLeast(1)).getMoreResults();
        inOrder.verify(callableStatement).getInt(1);
    }
}
