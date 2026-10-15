package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.util.Throwables;

class CallableQueryFailureTest extends CallableQueryTestSupport {

    @ParameterizedTest(name = "{0}, {2}, autoClose={3}")
    @MethodSource("executionFailures")
    void execute_driverFailure_preservesFailureAndHonorsResourceOwnership(final String operationName,
            final Throwables.Consumer<CallableQuery, SQLException> operation, final FailureKind kind, final boolean autoClose) throws SQLException {
        final Throwable primary = kind.create();
        final IllegalStateException cleanup = new IllegalStateException("close handler failed");
        doThrow(primary).when(callableStatement).execute();
        doThrow(primary).when(callableStatement).executeQuery();
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.closeAfterExecution(autoClose).onClose(() -> {
            throw cleanup;
        });

        assertSame(primary, assertThrows(primary.getClass(), () -> operation.accept(callableQuery)));
        assertEquals(autoClose, callableQuery.isClosed);
        assertArrayEquals(autoClose ? new Throwable[] { cleanup } : new Throwable[0], primary.getSuppressed());
        if (autoClose) {
            verify(callableStatement).close();
        } else {
            verify(callableStatement, never()).close();
            assertSame(cleanup, assertThrows(IllegalStateException.class, callableQuery::close));
        }
    }

    private static Stream<Arguments> executionFailures() {
        return operations().flatMap(test -> Stream.of(FailureKind.values())
                .flatMap(kind -> Stream.of(false, true).map(autoClose -> Arguments.of(test.name(), test.operation(), kind, autoClose))));
    }

    private static Stream<QueryOperation> operations() {
        return Stream.of(operation("executeThenApply(triFunction)", q -> q.executeThenApply((stmt, out, result) -> 1)),
                operation("executeThenAccept(triConsumer)", q -> q.executeThenAccept((stmt, out, result) -> stmt.getUpdateCount())),
                operation("queryAndGetOutParameters", q -> q.queryAndGetOutParameters((rs, labels) -> 1)),
                operation("queryAllResultSetsAndGetOutParameters", q -> q.queryAllResultSetsAndGetOutParameters((rs, labels) -> 1)),
                operation("query2ResultSetsAndGetOutParameters", q -> q.query2ResultSetsAndGetOutParameters((rs, labels) -> 1, (rs, labels) -> 2)),
                operation("query3ResultSetsAndGetOutParameters",
                        q -> q.query3ResultSetsAndGetOutParameters((rs, labels) -> 1, (rs, labels) -> 2, (rs, labels) -> 3)),
                operation("listAndGetOutParameters(rowMapper)", q -> q.listAndGetOutParameters(rs -> rs.getString(1))),
                operation("listAndGetOutParameters(rowFilter,rowMapper)", q -> q.listAndGetOutParameters(rs -> true, rs -> rs.getString(1))),
                operation("listAndGetOutParameters(biRowMapper)", q -> q.listAndGetOutParameters((rs, labels) -> rs.getString(1))),
                operation("listAndGetOutParameters(biRowFilter,biRowMapper)",
                        q -> q.listAndGetOutParameters((rs, labels) -> true, (rs, labels) -> rs.getString(1))),
                operation("listAllResultSetsAndGetOutParameters(class)", q -> q.listAllResultSetsAndGetOutParameters(String.class)),
                operation("listAllResultSetsAndGetOutParameters(rowMapper)", q -> q.listAllResultSetsAndGetOutParameters(rs -> rs.getString(1))),
                operation("listAllResultSetsAndGetOutParameters(rowFilter,rowMapper)",
                        q -> q.listAllResultSetsAndGetOutParameters(rs -> true, rs -> rs.getString(1))),
                operation("listAllResultSetsAndGetOutParameters(biRowMapper)", q -> q.listAllResultSetsAndGetOutParameters((rs, labels) -> rs.getString(1))),
                operation("listAllResultSetsAndGetOutParameters(biRowFilter,biRowMapper)",
                        q -> q.listAllResultSetsAndGetOutParameters((rs, labels) -> true, (rs, labels) -> rs.getString(1))));
    }

    private static QueryOperation operation(final String name, final Throwables.Consumer<CallableQuery, SQLException> operation) {
        return new QueryOperation(name, operation);
    }

    private record QueryOperation(String name, Throwables.Consumer<CallableQuery, SQLException> operation) {
    }

    private enum FailureKind {
        SQL, RUNTIME, ERROR;

        Throwable create() {
            return switch (this) {
                case SQL -> new SQLException("driver execution failed");
                case RUNTIME -> new IllegalStateException("driver execution failed");
                case ERROR -> new AssertionError("driver execution failed");
            };
        }
    }
}
