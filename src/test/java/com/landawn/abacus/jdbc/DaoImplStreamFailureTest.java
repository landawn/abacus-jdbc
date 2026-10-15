package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

import javax.sql.DataSource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.DaoImplReadOperationsIntegrationTest.RecordDao;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.stream.Stream;

class DaoImplStreamFailureTest extends TestBase {

    private static final Condition CONDITION = Filters.gt("id", 0L);
    private static final List<String> COLUMNS = List.of("id", "name", "score");

    @ParameterizedTest(name = "{0}, preparation failure={1}")
    @MethodSource("streams")
    void executionIsLazyAndSqlFailureClosesOwnedResources(final StreamCase scenario, final boolean preparationFailure) throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final SQLException failure = new SQLException("stream execution failed", "42000");
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseProductVersion()).thenReturn("2");
        if (preparationFailure) {
            when(connection.prepareStatement(anyString())).thenThrow(failure);
        } else {
            when(connection.prepareStatement(anyString())).thenReturn(statement);
        }
        when(statement.getConnection()).thenReturn(connection);
        when(statement.executeQuery()).thenThrow(failure);
        final RecordDao dao = JdbcUtil.createDao(RecordDao.class, dataSource);
        clearInvocations(dataSource, connection, statement);

        try (Stream<?> rows = scenario.create.apply(dao)) {
            verify(dataSource, never()).getConnection();
            verify(connection, never()).prepareStatement(anyString());
            assertSame(failure, assertThrows(UncheckedSQLException.class, rows::toList).getCause());
        }

        if (preparationFailure) {
            verify(statement, never()).close();
        } else {
            verify(statement).close();
        }
        verify(connection).close();
    }

    private static java.util.stream.Stream<Arguments> streams() {
        return java.util.stream.Stream
                .of(new StreamCase("entity", d -> d.stream(CONDITION)), new StreamCase("row mapper", d -> d.stream(CONDITION, rs -> rs.getString("name"))),
                        new StreamCase("bi-row mapper", d -> d.stream(CONDITION, (rs, labels) -> rs.getString("name"))),
                        new StreamCase("row filter", d -> d.stream(CONDITION, rs -> true, rs -> rs.getString("name"))),
                        new StreamCase("bi-row filter", d -> d.stream(CONDITION, (rs, labels) -> true, (rs, labels) -> rs.getString("name"))),
                        new StreamCase("selected entity", d -> d.stream(COLUMNS, CONDITION)),
                        new StreamCase("selected row mapper", d -> d.stream(COLUMNS, CONDITION, rs -> rs.getString("name"))),
                        new StreamCase("selected bi-row mapper", d -> d.stream(COLUMNS, CONDITION, (rs, labels) -> rs.getString("name"))),
                        new StreamCase("selected row filter", d -> d.stream(COLUMNS, CONDITION, rs -> true, rs -> rs.getString("name"))),
                        new StreamCase("selected bi-row filter", d -> d.stream(COLUMNS, CONDITION, (rs, labels) -> true, (rs, labels) -> rs.getString("name"))))
                .flatMap(scenario -> java.util.stream.Stream.of(Arguments.of(scenario, false), Arguments.of(scenario, true)));
    }

    private record StreamCase(String name, Function<RecordDao, Stream<?>> create) {
        @Override
        public String toString() {
            return name;
        }
    }
}
