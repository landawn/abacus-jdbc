package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;

public class CallableQueryTest extends TestBase {

    private CallableStatement callableStatement;
    private CallableQuery callableQuery;

    @BeforeEach
    public void setUp() throws SQLException {
        callableStatement = Mockito.mock(CallableStatement.class);
        Connection connection = Mockito.mock(Connection.class);

        when(callableStatement.getConnection()).thenReturn(connection);

        callableQuery = new CallableQuery(callableStatement);
    }

    @Test
    public void testSetBoolean_NullValue() throws SQLException {
        CallableQuery result = callableQuery.setBoolean("active", (Boolean) null);

        assertSame(callableQuery, result);
        verify(callableStatement).setNull("active", Types.BOOLEAN);
    }

    @Test
    public void testRegisterOutParameters_NullSetter() {
        assertThrows(IllegalArgumentException.class, () -> callableQuery.registerOutParameters((Jdbc.ParametersSetter<? super CallableQuery>) null));
    }

    @Test
    public void testRegisterOutParameters_ClosesStatementOnFailure() throws SQLException {
        assertThrows(SQLException.class, () -> callableQuery.registerOutParameters(query -> {
            throw new SQLException("boom");
        }));

        verify(callableStatement).close();
    }

    @Test
    public void testRegisterOutParameter_ReplacesExistingNamedParameter() throws SQLException {
        when(callableStatement.execute()).thenReturn(true);

        callableQuery.registerOutParameter("total", Types.INTEGER);
        callableQuery.registerOutParameter("total", Types.DECIMAL, 2);

        Boolean isResultSet = callableQuery.executeThenApply((stmt, outParams, firstResultSet) -> {
            assertSame(callableStatement, stmt);
            assertEquals(1, outParams.size());
            assertEquals("total", outParams.get(0).getParameterName());
            assertEquals(Types.DECIMAL, outParams.get(0).getSqlType());
            assertEquals(2, outParams.get(0).getScale());
            return firstResultSet;
        });

        assertTrue(isResultSet);
        verify(callableStatement).registerOutParameter("total", Types.INTEGER);
        verify(callableStatement).registerOutParameter("total", Types.DECIMAL, 2);
        verify(callableStatement).execute();
    }

    @Test
    public void testRegisterOutParameters_WithContextObject() throws SQLException {
        List<String> names = List.of("first", "second");

        CallableQuery result = callableQuery.registerOutParameters(names, (query, value) -> {
            query.registerOutParameter(value.get(0), Types.INTEGER);
            query.registerOutParameter(value.get(1), Types.VARCHAR);
        });

        assertNotNull(result);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter("first", Types.INTEGER);
        verify(callableStatement).registerOutParameter("second", Types.VARCHAR);
    }
}
