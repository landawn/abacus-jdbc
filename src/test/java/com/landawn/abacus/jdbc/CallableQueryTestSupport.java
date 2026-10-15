package com.landawn.abacus.jdbc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;

abstract class CallableQueryTestSupport extends TestBase {

    protected CallableStatement callableStatement;

    protected CallableQuery callableQuery;

    @BeforeEach
    public void setUp() throws SQLException {
        callableStatement = Mockito.mock(CallableStatement.class);
        Connection connection = Mockito.mock(Connection.class);

        when(callableStatement.getConnection()).thenReturn(connection);

        callableQuery = new CallableQuery(callableStatement);
    }
}
