package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;

public class AbstractQueryTest extends TestBase {

    static final class TestQuery extends AbstractQuery<PreparedStatement, TestQuery> {
        TestQuery(final PreparedStatement stmt) {
            super(stmt);
        }
    }

    private PreparedStatement preparedStatement;
    private TestQuery query;

    @BeforeEach
    public void setUp() throws SQLException {
        preparedStatement = Mockito.mock(PreparedStatement.class);
        Connection connection = Mockito.mock(Connection.class);

        Mockito.when(preparedStatement.getConnection()).thenReturn(connection);
        query = new TestQuery(preparedStatement);
    }

    @Test
    public void testSetParameters_IntArray() throws SQLException {
        TestQuery result = query.setParameters(new int[] { 1, 2, 3 });

        assertSame(query, result);
        verify(preparedStatement).setInt(1, 1);
        verify(preparedStatement).setInt(2, 2);
        verify(preparedStatement).setInt(3, 3);
    }

    @Test
    public void testSetParametersFrom_Collection() throws SQLException {
        TestQuery result = query.setParametersFrom(2, List.of("a", "b"));

        assertSame(query, result);
        verify(preparedStatement).setString(2, "a");
        verify(preparedStatement).setString(3, "b");
    }

    @Test
    public void testSetNullForIndices() throws SQLException {
        TestQuery result = query.setNullForIndices(Types.INTEGER, 1, 3);

        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.INTEGER);
        verify(preparedStatement).setNull(3, Types.INTEGER);
    }

    @Test
    public void testSetFetchSize() throws SQLException {
        TestQuery result = query.setFetchSize(-1);

        assertSame(query, result);
        verify(preparedStatement).setFetchSize(-1);
    }

    @Test
    public void testCloseAfterExecution_ClosedQuery() throws SQLException {
        query.close();

        assertThrows(IllegalStateException.class, () -> query.closeAfterExecution(false));
    }
}
