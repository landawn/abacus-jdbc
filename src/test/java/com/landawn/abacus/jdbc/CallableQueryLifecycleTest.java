package com.landawn.abacus.jdbc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class CallableQueryLifecycleTest extends CallableQueryTestSupport {

    // --- closeStatement(): clearParameters throws → logger.warn (L3626) ---

    @Test
    public void testCloseStatement_ClearParametersThrows() throws SQLException {
        doThrow(new SQLException("clear failed")).when(callableStatement).clearParameters();
        // close() calls closeStatement(), which calls clearParameters()
        callableQuery.close();
        // No exception should propagate - warning is logged and super.closeStatement() is called
        verify(callableStatement).clearParameters();
        verify(callableStatement).close();
    }
}
