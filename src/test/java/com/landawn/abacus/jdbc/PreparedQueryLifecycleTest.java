package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class PreparedQueryLifecycleTest extends PreparedQueryTestSupport {

    @Test
    public void testAutoCloseAfterExecution() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        query.queryForInt();

        assertTrue(query.isClosed);
        verify(mockStmt).close();
    }

    @Test
    public void testClose() throws SQLException {
        assertFalse(query.isClosed);

        query.close();

        assertTrue(query.isClosed);
        verify(mockStmt).close();
    }

    @Test
    public void testCloseAfterExecution() {
        // Test default behavior (should be true)
        assertTrue(query.isCloseAfterExecution());

        // Test setting to false
        PreparedQuery result = query.closeAfterExecution(false);
        assertSame(query, result);
        assertFalse(query.isCloseAfterExecution());

        // Test setting back to true
        result = query.closeAfterExecution(true);
        assertSame(query, result);
        assertTrue(query.isCloseAfterExecution());
    }

    @Test
    public void testCloseIdempotent() throws SQLException {
        query.close();
        query.close(); // Should not throw

        assertTrue(query.isClosed);
        verify(mockStmt, times(1)).close();
    }

    @Test
    public void testCloseWithHandler() throws SQLException {
        boolean[] handlerCalled = { false };
        query.onClose(() -> handlerCalled[0] = true);

        query.close();

        assertTrue(handlerCalled[0]);
        verify(mockStmt).close();
    }

    @Test
    public void testOnClose() {
        // Test single handler
        boolean[] handlerCalled = { false };
        PreparedQuery result = query.onClose(() -> handlerCalled[0] = true);
        assertSame(query, result);

        query.close();
        assertTrue(handlerCalled[0]);
    }

    @Test
    public void testOnCloseMultipleHandlers() {
        // Test multiple handlers - should execute in reverse order
        List<Integer> executionOrder = new ArrayList<>();

        query.onClose(() -> executionOrder.add(1)).onClose(() -> executionOrder.add(2)).onClose(() -> executionOrder.add(3));

        query.close();

        assertEquals(Arrays.asList(3, 2, 1), executionOrder);
    }

    @Test
    public void testOnCloseNullHandler() {
        assertThrows(IllegalArgumentException.class, () -> query.onClose(null));
    }
}
