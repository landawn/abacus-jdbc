package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.Dataset;

/**
 * Tests for the result-side methods of {@link AbstractQuery} (query/list/stream/execute/insert families),
 * focused on the resource-closing contract documented in their Javadoc.
 */
public class AbstractQueryResultTest extends TestBase {

    static final class ResultTestQuery extends AbstractQuery<PreparedStatement, ResultTestQuery> {
        ResultTestQuery(final PreparedStatement stmt) {
            super(stmt);
        }
    }

    private PreparedStatement preparedStatement;
    private ResultTestQuery query;

    @BeforeEach
    public void setUp() throws SQLException {
        preparedStatement = Mockito.mock(PreparedStatement.class);
        final Connection connection = Mockito.mock(Connection.class);

        when(preparedStatement.getConnection()).thenReturn(connection);
        query = new ResultTestQuery(preparedStatement);
    }

    private ResultSet mockSingleEmptyResultSet() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("value");
        when(metadata.getColumnName(1)).thenReturn("value");
        when(rs.next()).thenReturn(false);

        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(rs);
        when(preparedStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);

        return rs;
    }

    // queryAllResultSets() closes each consumed result set quietly: a close failure is logged and
    // ignored rather than propagated (the Javadoc must not claim otherwise).
    @Test
    public void testQueryAllResultSetsIgnoresConsumedResultSetCloseFailure() throws SQLException {
        final ResultSet rs = mockSingleEmptyResultSet();
        doThrow(new SQLException("close failed")).when(rs).close();

        final List<Dataset> result = query.queryAllResultSets();

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).size());
        verify(rs).close();
        verify(preparedStatement).close();
        assertTrue(query.isClosed);
    }

    // listAllResultSets(Class) has the same quiet-close contract as queryAllResultSets().
    @Test
    public void testListAllResultSetsClassIgnoresConsumedResultSetCloseFailure() throws SQLException {
        final ResultSet rs = mockSingleEmptyResultSet();
        doThrow(new SQLException("close failed")).when(rs).close();

        final List<List<String>> result = query.listAllResultSets(String.class);

        assertEquals(1, result.size());
        assertTrue(result.get(0).isEmpty());
        verify(rs).close();
        verify(preparedStatement).close();
        assertTrue(query.isClosed);
    }

    // When the statement is kept open, the trailing results are drained after the last consumed result
    // set; a drain failure is the only remaining failure and is propagated as the SQLException.
    @Test
    public void testQueryAllResultSetsPropagatesDrainFailureWhenStatementIsReusable() throws SQLException {
        final ResultSet rs = mockSingleEmptyResultSet();
        final SQLException drainFailure = new SQLException("drain failed");
        when(preparedStatement.getMoreResults()).thenThrow(drainFailure);
        query.closeAfterExecution(false);

        final SQLException thrown = assertThrows(SQLException.class, () -> query.queryAllResultSets());

        assertSame(drainFailure, thrown);
        verify(rs).close();
        verify(preparedStatement).getMoreResults();
        verify(preparedStatement, never()).close();
        assertFalse(query.isClosed);
    }

    // Without closeAfterExecution(false) nothing is drained: the statement is simply closed.
    @Test
    public void testListAllResultSetsClassDoesNotDrainWhenStatementIsClosedAfterExecution() throws SQLException {
        final ResultSet rs = mockSingleEmptyResultSet();

        final List<List<String>> result = query.listAllResultSets(String.class);

        assertEquals(1, result.size());
        verify(rs).close();
        verify(preparedStatement, never()).getMoreResults();
        verify(preparedStatement).close();
    }
}
