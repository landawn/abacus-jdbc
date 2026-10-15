package com.landawn.abacus.jdbc;

import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;

abstract class JdbcCodeGenerationUtilTestSupport extends TestBase {

    protected Connection connection;

    protected PreparedStatement preparedStatement;

    protected ResultSet resultSet;

    protected ResultSetMetaData resultSetMetaData;

    protected DatabaseMetaData databaseMetaData;

    @BeforeEach
    public void setUp() throws SQLException {
        connection = Mockito.mock(Connection.class);
        preparedStatement = Mockito.mock(PreparedStatement.class);
        resultSet = Mockito.mock(ResultSet.class);
        resultSetMetaData = Mockito.mock(ResultSetMetaData.class);
        databaseMetaData = Mockito.mock(DatabaseMetaData.class);

        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(databaseMetaData.getIdentifierQuoteString()).thenReturn("`");
        when(databaseMetaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(connection.prepareStatement("SELECT * FROM order_history WHERE 1 > 2")).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
        when(resultSetMetaData.getColumnCount()).thenReturn(3);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(resultSetMetaData.getColumnLabel(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnLabel(3)).thenReturn("status");
    }

    // Setup helper for a full generateEntityClass mock
    protected void setupFullGenerateEntityClassMock() throws SQLException {
        Statement stmt = Mockito.mock(Statement.class);
        ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnName(1)).thenReturn("id");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.BIGINT);
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");
    }

    static final class TestEntity {
    }
}
