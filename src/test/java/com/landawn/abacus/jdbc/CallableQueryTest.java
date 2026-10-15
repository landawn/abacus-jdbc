package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class CallableQueryTest extends CallableQueryTestSupport {

    // The no-result fallback has valid empty-cursor characteristics but no current row.
    @Test
    public void testEmptyResultSetProxy_EmptyCursorContract() throws SQLException {
        ResultSet rs = emptyResultSetProxy();
        assertFalse(rs.next());
        assertSame(callableStatement, rs.getStatement());
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, rs.getType());
        assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, rs.getHoldability());
        assertEquals(ResultSet.FETCH_FORWARD, rs.getFetchDirection());
        assertEquals(0, rs.getFetchSize());
        assertEquals(0, rs.getRow());
        assertFalse(rs.isBeforeFirst());
        assertFalse(rs.isAfterLast());
        assertNull(rs.getWarnings());
        rs.clearWarnings();

        assertThrows(SQLException.class, rs::wasNull);
        assertThrows(SQLException.class, () -> rs.getBoolean(1));
        assertThrows(SQLException.class, () -> rs.getInt(1));
        assertThrows(SQLException.class, () -> rs.updateInt(1, 1));

        assertFalse(rs.isClosed());
        rs.close();
        assertTrue(rs.isClosed());
        assertThrows(SQLException.class, rs::next);
    }

    // ResultSet proxy: unwrap / isWrapperFor — L2380-2389
    @Test
    public void testEmptyResultSetProxy_UnwrapAndWrapper() throws SQLException {
        ResultSet rs = emptyResultSetProxy();
        assertTrue(rs.isWrapperFor(ResultSet.class));
        assertFalse(rs.isWrapperFor(String.class));
        assertSame(rs, rs.unwrap(ResultSet.class));
        assertThrows(SQLException.class, () -> rs.unwrap(String.class));
    }

    // ResultSet proxy: unhandled object-returning method falls through to SQLException — L2392, L2424
    @Test
    public void testEmptyResultSetProxy_UnhandledMethodThrows() throws SQLException {
        ResultSet rs = emptyResultSetProxy();
        SQLException ex = assertThrows(SQLException.class, () -> rs.getString(1));
        assertTrue(ex.getMessage().contains("did not return a ResultSet"));
    }

    // ResultSetMetaData proxy: getColumnCount/unwrap/isWrapperFor + identity & unhandled — L2346-2362, L2398-2424
    @Test
    public void testEmptyResultSetMetaDataProxy() throws SQLException {
        ResultSet rs = emptyResultSetProxy();
        ResultSetMetaData md = rs.getMetaData();
        assertNotNull(md);
        assertEquals(0, md.getColumnCount()); // L2348-2349
        assertTrue(md.isWrapperFor(ResultSetMetaData.class)); // L2358-2359
        assertFalse(md.isWrapperFor(String.class));
        assertSame(md, md.unwrap(ResultSetMetaData.class)); // L2350-2355
        assertThrows(SQLException.class, () -> md.unwrap(String.class)); // L2357
        // There is no valid column index in zero-column metadata.
        assertThrows(SQLException.class, () -> md.isAutoIncrement(1));
        assertThrows(SQLException.class, () -> md.getColumnType(1));
        // toString/hashCode/equals identity branches — L2398-2403
        assertTrue(md.toString().contains("Empty ResultSet metadata"));
        assertEquals(System.identityHashCode(md), md.hashCode());
        assertTrue(md.equals(md));
        // object-returning method (String) falls through → SQLException — L2424
        SQLException ex = assertThrows(SQLException.class, () -> md.getColumnName(1));
        assertTrue(ex.getMessage().contains("Empty ResultSet metadata has no columns"));
    }

    @Test
    public void testConstructorRejectsNullStatement() {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> new CallableQuery(null));

        assertTrue(thrown.getMessage().contains("stmt"));
    }

    @Test
    public void testExecutionFailureRemainsPrimaryWhenAutomaticCloseHandlerFails() throws SQLException {
        final SQLException executionFailure = new SQLException("call failed");
        final RuntimeException closeFailure = new RuntimeException("close handler failed");
        when(callableStatement.execute()).thenThrow(executionFailure);
        callableQuery.onClose(() -> {
            throw closeFailure;
        });

        final SQLException thrown = assertThrows(SQLException.class, callableQuery::executeAndGetOutParameters);

        assertSame(executionFailure, thrown);
        assertArrayEquals(new Throwable[] { closeFailure }, thrown.getSuppressed());
    }

    // ============================================================================
    // emptyResultSet() proxy
    // Reached via executeQuery() when the procedure produces no result set.
    // ============================================================================

    private ResultSet emptyResultSetProxy() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        return callableQuery.executeQuery();
    }
}
