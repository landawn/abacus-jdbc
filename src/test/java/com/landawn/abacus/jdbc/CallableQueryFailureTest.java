package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

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

    // A driver, extractor or mapper can throw an undeclared checked exception (Kotlin, sneaky throw). The OUT-parameter
    // result methods took it for success, so the failing drain (closeAfterExecution(false)) or close handler replaced it.
    @ParameterizedTest(name = "{0}, autoClose={2}")
    @MethodSource("resultReadingOperations")
    void resultReading_undeclaredCheckedReadFailure_staysPrimaryOverCleanupFailures(final String operationName,
            final Throwables.Consumer<CallableQuery, SQLException> operation, final boolean autoClose) throws SQLException {
        final IOException readFailure = new IOException("reading the result set failed");
        final SQLException drainFailure = new SQLException("drain failed");
        final IllegalStateException cleanup = new IllegalStateException("close handler failed");
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("c");
        when(rs.next()).thenAnswer(invocation -> {
            throw readFailure;
        });
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(callableStatement.getMoreResults()).thenThrow(drainFailure);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.closeAfterExecution(autoClose).onClose(() -> {
            throw cleanup;
        });

        final Throwable thrown = assertThrows(Throwable.class, () -> operation.accept(callableQuery));

        assertSame(readFailure, thrown);
        assertArrayEquals(new Throwable[] { autoClose ? cleanup : drainFailure }, thrown.getSuppressed());
        assertEquals(autoClose, callableQuery.isClosed);
        verify(rs).close();

        if (!autoClose) {
            assertSame(cleanup, assertThrows(IllegalStateException.class, callableQuery::close));
        }
    }

    // The binding and OUT-registration methods close the query when binding fails; an undeclared checked exception (from a
    // driver, a getter or a registrar written in Kotlin, or a sneaky throw) skipped the close and left the statement open.
    @ParameterizedTest(name = "{0}")
    @MethodSource("bindingOperations")
    void binding_undeclaredCheckedFailure_closesQuery(final String operationName, final Throwables.Consumer<CallableQuery, SQLException> operation)
            throws SQLException {
        final IOException bindingFailure = new IOException("binding failed");
        Mockito.doAnswer(invocation -> {
            throw bindingFailure;
        }).when(callableStatement).setString(Mockito.anyString(), Mockito.anyString());
        Mockito.doAnswer(invocation -> {
            throw bindingFailure;
        }).when(callableStatement).setLong(Mockito.anyString(), Mockito.anyLong());
        Mockito.doAnswer(invocation -> {
            throw bindingFailure;
        }).when(callableStatement).registerOutParameter(Mockito.anyString(), Mockito.anyInt());

        assertSame(bindingFailure, assertThrows(Throwable.class, () -> operation.accept(callableQuery)));
        assertEquals(true, callableQuery.isClosed);
        verify(callableStatement).close();
    }

    // clearParameters() caught SQLException | RuntimeException only: an undeclared checked exception escaped closeStatement()
    // once the statement was closed, and close() then skipped the close handler that releases a data-source query's connection.
    @Test
    void close_clearParametersThrowsUndeclaredCheckedException_resetsPropertiesClosesStatementAndRunsHandler() throws SQLException {
        final AtomicInteger connectionReleases = new AtomicInteger();
        when(callableStatement.getFetchSize()).thenReturn(100);
        callableQuery.setFetchSize(500).onClose(connectionReleases::incrementAndGet);
        Mockito.doAnswer(invocation -> {
            throw new IOException("clearing parameters failed, undeclared");
        }).when(callableStatement).clearParameters();

        assertDoesNotThrow(callableQuery::close);

        verify(callableStatement).setFetchSize(100);
        verify(callableStatement).close();
        assertEquals(1, connectionReleases.get());
        assertEquals(true, callableQuery.isClosed);
    }

    private static Stream<Arguments> resultReadingOperations() {
        return Stream.of(operation("queryAndGetOutParameters(resultExtractor)", q -> q.queryAndGetOutParameters(rs -> rs.next())),
                operation("queryAndGetOutParameters(biResultExtractor)", q -> q.queryAndGetOutParameters((rs, labels) -> rs.next())),
                operation("queryAllResultSetsAndGetOutParameters(resultExtractor)", q -> q.queryAllResultSetsAndGetOutParameters(rs -> rs.next())),
                operation("queryAllResultSetsAndGetOutParameters(biResultExtractor)", q -> q.queryAllResultSetsAndGetOutParameters((rs, labels) -> rs.next())),
                operation("query2ResultSetsAndGetOutParameters", q -> q.query2ResultSetsAndGetOutParameters((rs, labels) -> rs.next(), (rs, labels) -> 2)),
                operation("query3ResultSetsAndGetOutParameters",
                        q -> q.query3ResultSetsAndGetOutParameters((rs, labels) -> rs.next(), (rs, labels) -> 2, (rs, labels) -> 3)),
                operation("listAndGetOutParameters(class)", q -> q.listAndGetOutParameters(String.class)),
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
                        q -> q.listAllResultSetsAndGetOutParameters((rs, labels) -> true, (rs, labels) -> rs.getString(1))))
                .flatMap(test -> Stream.of(false, true).map(autoClose -> Arguments.of(test.name(), test.operation(), autoClose)));
    }

    private static Stream<Arguments> bindingOperations() {
        return Stream
                .of(operation("setLong(String, BigInteger)", q -> q.setLong("p", BigInteger.ONE)),
                        operation("setParameters(Map)", q -> q.setParameters(Map.of("p", "v"))),
                        operation("setParameters(entity, names)", q -> q.setParameters(new NameBean("v"), List.of("name"))),
                        operation("registerOutParameters(registrar)", q -> q.registerOutParameters(cq -> cq.registerOutParameter("p", Types.INTEGER))),
                        operation("registerOutParameters(parameter, registrar)",
                                q -> q.registerOutParameters("p", (cq, name) -> cq.registerOutParameter(name, Types.INTEGER))))
                .map(test -> Arguments.of(test.name(), test.operation()));
    }

    public static class NameBean {
        private String name;

        public NameBean() {
        }

        NameBean(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
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
