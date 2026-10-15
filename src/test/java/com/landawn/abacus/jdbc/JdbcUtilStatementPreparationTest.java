package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.query.ParsedSql;

class JdbcUtilStatementPreparationTest extends JdbcUtilTestSupport {

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void statementAdaptersPreserveSqlAndResultSetOptions(final int variant) throws SQLException {
        final String sql = "select ?";
        final ParsedSql parsed = ParsedSql.parse("select :value");
        final int type = ResultSet.TYPE_SCROLL_INSENSITIVE;
        final int concurrency = ResultSet.CONCUR_UPDATABLE;
        final int holdability = ResultSet.HOLD_CURSORS_OVER_COMMIT;
        when(mockConnection.prepareStatement(sql, type, concurrency, holdability)).thenReturn(mockPreparedStatement);

        final PreparedStatement result = switch (variant) {
            case 0 -> JdbcUtil.prepareStatement(mockConnection, sql, type, concurrency, holdability);
            case 1 -> JdbcUtil.prepareStatement(mockConnection, parsed, type, concurrency);
            default -> JdbcUtil.prepareStatement(mockConnection, parsed, type, concurrency, holdability);
        };

        assertSame(mockPreparedStatement, result);
        if (variant == 1) {
            verify(mockConnection).prepareStatement(sql, type, concurrency);
        } else {
            verify(mockConnection).prepareStatement(sql, type, concurrency, holdability);
        }
        verify(mockPreparedStatement, never()).close();
        verify(mockConnection, never()).close();
    }

    @Test
    void callableCreatorReceivesParameterizedSqlAndKeepsItsException() throws SQLException {
        final ParsedSql parsed = ParsedSql.parse("{call sample(:value)}");
        assertSame(mockCallableStatement, JdbcUtil.prepareCallable(mockConnection, parsed, (connection, sql) -> {
            assertSame(mockConnection, connection);
            assertEquals("{call sample(?)}", sql);
            return mockCallableStatement;
        }));

        final SQLException failure = new SQLException("custom preparation failed");
        assertSame(failure, assertThrows(SQLException.class, () -> JdbcUtil.prepareCallable(mockConnection, parsed, (connection, sql) -> {
            throw failure;
        })));
        verify(mockCallableStatement, never()).close();
        verify(mockConnection, never()).close();
    }
}
