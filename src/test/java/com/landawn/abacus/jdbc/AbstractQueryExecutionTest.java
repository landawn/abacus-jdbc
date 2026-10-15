package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AbstractQueryExecutionTest extends AbstractQueryTestSupport {

    // addBatch() must close the statement when the addBatchAction throws, otherwise a
    // fluent chain that fails mid-way (e.g. query.setX(...).addBatch()) leaks the statement
    // because batchUpdate()/closeAfterExecutionIfAllowed() will never run.
    @Test
    public void testAddBatch_ExceptionClosesQuery() throws SQLException {
        doThrow(new SQLException("addBatch failed")).when(preparedStatement).addBatch();

        assertThrows(SQLException.class, () -> query.addBatch());

        verify(preparedStatement).close();
    }

    // Regression: executeQuery() forced FETCH_FORWARD without capturing the driver-default
    // direction first, so closeStatement() couldn't restore it. For pooled PreparedStatements
    // this silently leaked the FETCH_FORWARD setting to the next caller.
    // executeQuery is protected; we exercise it via reflection through a TestQuery instance.
    @Test
    public void testExecuteQuery_CapturesDefaultFetchDirectionForRestoration() throws Exception {
        // Pre-set the "current" fetch direction the driver would report (e.g., FETCH_REVERSE).
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_REVERSE);

        // Invoke the protected executeQuery via reflection.
        final java.lang.reflect.Method m = AbstractQuery.class.getDeclaredMethod("executeQuery");
        m.setAccessible(true);
        m.invoke(query);

        // The fix captures the prior fetch direction before forcing FORWARD.
        verify(preparedStatement).getFetchDirection();
        verify(preparedStatement).setFetchDirection(ResultSet.FETCH_FORWARD);

        // close() must restore the original FETCH_REVERSE direction. Pre-fix the capture never
        // happened and the restoration in closeStatement was a no-op (default == -1).
        query.close();
        verify(preparedStatement).setFetchDirection(ResultSet.FETCH_REVERSE);
    }

    // batchInsert keeps the full key list (one id per row) when at least one key is non-default,
    // so a legitimate explicit id of 0 is preserved and the 1:1 row-to-id correspondence is not broken.
    @Test
    public void testBatchInsert_RowMapper_MixedDefaultIds_ReturnsAll() throws SQLException {
        ResultSet generatedKeys = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeBatch()).thenReturn(new int[] { 1, 1, 1 });
        when(preparedStatement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.next()).thenReturn(true, true, true, false);
        when(generatedKeys.getLong(1)).thenReturn(10L, 0L, 20L);

        Predicate<Object> isDefault = id -> id == null || (Long) id == 0L;
        List<Long> result = query.batchInsert((Jdbc.RowMapper<Long>) rs -> rs.getLong(1), isDefault);

        assertEquals(List.of(10L, 0L, 20L), result);
    }

    // batchInsert keeps the full key list (one id per row) when at least one key is non-default,
    // so a legitimate explicit id of 0 is preserved and the 1:1 row-to-id correspondence is not broken.
    @Test
    public void testBatchInsert_BiRowMapper_MixedDefaultIds_ReturnsAll() throws SQLException {
        ResultSet generatedKeys = Mockito.mock(ResultSet.class);
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeBatch()).thenReturn(new int[] { 1, 1, 1 });
        when(preparedStatement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(generatedKeys.next()).thenReturn(true, true, true, false);
        when(generatedKeys.getLong(1)).thenReturn(10L, 0L, 20L);

        Predicate<Object> isDefault = id -> id == null || (Long) id == 0L;
        List<Long> result = query.batchInsert((Jdbc.BiRowMapper<Long>) (rs, cols) -> rs.getLong(1), isDefault);

        assertEquals(List.of(10L, 0L, 20L), result);
    }

    // batchInsert(RowMapper, Predicate): all IDs are null → returns empty list (L8961)
    @Test
    public void testBatchInsert_RowMapper_AllNullIds_ReturnsEmptyList() throws SQLException {
        ResultSet generatedKeys = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeBatch()).thenReturn(new int[] { 1 });
        when(preparedStatement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.next()).thenReturn(true, false);

        @SuppressWarnings("unchecked")
        Predicate<Object> isDefault = id -> id == null;
        List<?> result = query.batchInsert((Jdbc.RowMapper<Long>) rs -> null, isDefault);
        assertEquals(0, result.size());
    }

    // batchInsert(BiRowMapper, Predicate): all IDs are null → returns empty list (L9001)
    @Test
    public void testBatchInsert_BiRowMapper_AllNullIds_ReturnsEmptyList() throws SQLException {
        ResultSet generatedKeys = Mockito.mock(ResultSet.class);
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeBatch()).thenReturn(new int[] { 1 });
        when(preparedStatement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(generatedKeys.next()).thenReturn(true, false);

        @SuppressWarnings("unchecked")
        Predicate<Object> isDefault = id -> id == null;
        List<?> result = query.batchInsert((Jdbc.BiRowMapper<Long>) (rs, cols) -> null, isDefault);
        assertEquals(0, result.size());
    }
}
