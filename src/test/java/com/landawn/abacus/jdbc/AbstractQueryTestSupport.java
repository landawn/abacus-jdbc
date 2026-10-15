package com.landawn.abacus.jdbc;

import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;

abstract class AbstractQueryTestSupport extends TestBase {

    protected PreparedStatement preparedStatement;

    protected TestQuery query;

    @BeforeEach
    public void setUp() throws SQLException {
        preparedStatement = Mockito.mock(PreparedStatement.class);
        Connection connection = Mockito.mock(Connection.class);

        Mockito.when(preparedStatement.getConnection()).thenReturn(connection);
        query = new TestQuery(preparedStatement);
    }

    static final class TestQuery extends AbstractQuery<PreparedStatement, TestQuery> {
        TestQuery(final PreparedStatement stmt) {
            super(stmt);
        }
    }
}
