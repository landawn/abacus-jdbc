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

import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Throwables;

class AbstractQueryFailureTest extends AbstractQueryTestSupport {

    @ParameterizedTest(name = "{0}, {2}, autoClose={3}")
    @MethodSource("executionFailures")
    void execute_driverFailure_preservesFailureAndHonorsResourceOwnership(final String operationName,
            final Throwables.Consumer<TestQuery, SQLException> operation, final FailureKind kind, final boolean autoClose) throws SQLException {
        final Throwable primary = kind.create();
        final IllegalStateException cleanup = new IllegalStateException("close handler failed");
        doThrow(primary).when(preparedStatement).executeQuery();
        doThrow(primary).when(preparedStatement).execute();
        doThrow(primary).when(preparedStatement).executeUpdate();
        doThrow(primary).when(preparedStatement).executeLargeUpdate();
        doThrow(primary).when(preparedStatement).executeBatch();
        doThrow(primary).when(preparedStatement).executeLargeBatch();
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
        query.closeAfterExecution(autoClose).onClose(() -> {
            throw cleanup;
        });

        assertSame(primary, assertThrows(primary.getClass(), () -> operation.accept(query)));
        assertEquals(autoClose, query.isClosed);
        assertArrayEquals(autoClose ? new Throwable[] { cleanup } : new Throwable[0], primary.getSuppressed());
        if (autoClose) {
            verify(preparedStatement).close();
        } else {
            verify(preparedStatement, never()).close();
            assertSame(cleanup, assertThrows(IllegalStateException.class, query::close));
        }
    }

    private static Stream<Arguments> executionFailures() {
        return Stream.concat(operations().flatMap(test -> failures(test, Stream.of(FailureKind.values()))),
                Stream.of(operation("stream(rowMapper)", q -> q.stream(rs -> rs.getString(1)).toList()),
                        operation("streamAllResultSets", q -> q.streamAllResultSets((Jdbc.ResultExtractor<Integer>) rs -> 1).toList()))
                        .flatMap(test -> failures(test, Stream.of(FailureKind.RUNTIME, FailureKind.ERROR))));
    }

    private static Stream<Arguments> failures(final QueryOperation test, final Stream<FailureKind> kinds) {
        return kinds.flatMap(kind -> Stream.of(false, true).map(autoClose -> Arguments.of(test.name(), test.operation(), kind, autoClose)));
    }

    private static Stream<QueryOperation> operations() {
        return Stream.of(operation("queryForBoolean", TestQuery::queryForBoolean), operation("queryForChar", TestQuery::queryForChar),
                operation("queryForByte", TestQuery::queryForByte), operation("queryForShort", TestQuery::queryForShort),
                operation("queryForInt", TestQuery::queryForInt), operation("queryForLong", TestQuery::queryForLong),
                operation("queryForFloat", TestQuery::queryForFloat), operation("queryForDouble", TestQuery::queryForDouble),
                operation("queryForString", TestQuery::queryForString), operation("queryForBigDecimal", TestQuery::queryForBigDecimal),
                operation("queryForDate", TestQuery::queryForDate), operation("queryForTime", TestQuery::queryForTime),
                operation("queryForTimestamp", TestQuery::queryForTimestamp), operation("queryForBytes", TestQuery::queryForBytes),
                operation("queryForSingleValue(type)", q -> q.queryForSingleValue(Type.of(String.class))),
                operation("queryForSingleNonNull(type)", q -> q.queryForSingleNonNull(Type.of(String.class))),
                operation("query3ResultSets", q -> q.query3ResultSets((rs, labels) -> 1, (rs, labels) -> 2, (rs, labels) -> 3)),
                operation("queryAllResultSets", q -> q.queryAllResultSets((rs, labels) -> 1)),
                operation("findFirstOrNull(class)", q -> q.findFirstOrNull(String.class)),
                operation("findFirstOrNull(rowMapper)", q -> q.findFirstOrNull(rs -> rs.getString(1))),
                operation("findFirstOrNull(rowFilter,rowMapper)", q -> q.findFirstOrNull(rs -> true, rs -> rs.getString(1))),
                operation("findFirstOrNull(biRowMapper)", q -> q.findFirstOrNull((rs, labels) -> rs.getString(1))),
                operation("findFirstOrNull(biRowFilter,biRowMapper)", q -> q.findFirstOrNull((rs, labels) -> true, (rs, labels) -> rs.getString(1))),
                operation("list(rowFilter,rowMapper,max)", q -> q.list(rs -> true, rs -> rs.getString(1), 10)),
                operation("list(biRowFilter,biRowMapper,max)", q -> q.list((rs, labels) -> true, (rs, labels) -> rs.getString(1), 10)),
                operation("listAllResultSets(class)", q -> q.listAllResultSets(String.class)),
                operation("listAllResultSets(rowMapper)", q -> q.listAllResultSets(rs -> rs.getString(1))),
                operation("listAllResultSets(rowFilter,rowMapper)", q -> q.listAllResultSets(rs -> true, rs -> rs.getString(1))),
                operation("listAllResultSets(biRowMapper)", q -> q.listAllResultSets((rs, labels) -> rs.getString(1))),
                operation("listAllResultSets(biRowFilter,biRowMapper)", q -> q.listAllResultSets((rs, labels) -> true, (rs, labels) -> rs.getString(1))),
                operation("exists", TestQuery::exists), operation("ifExists(rowConsumer)", q -> q.ifExists(rs -> rs.getString(1))),
                operation("ifExists(biRowConsumer)", q -> q.ifExists((rs, labels) -> rs.getString(1))),
                operation("ifExistsOrElse(rowConsumer)", q -> q.ifExistsOrElse(rs -> rs.getString(1), () -> {
                })), operation("ifExistsOrElse(biRowConsumer)", q -> q.ifExistsOrElse((rs, labels) -> rs.getString(1), () -> {
                })), operation("count", TestQuery::count), operation("count(rowFilter)", q -> q.count(rs -> true)),
                operation("count(biRowFilter)", q -> q.count((rs, labels) -> true)), operation("anyMatch(rowFilter)", q -> q.anyMatch(rs -> true)),
                operation("anyMatch(biRowFilter)", q -> q.anyMatch((rs, labels) -> true)), operation("allMatch(rowFilter)", q -> q.allMatch(rs -> true)),
                operation("allMatch(biRowFilter)", q -> q.allMatch((rs, labels) -> true)),
                operation("forEach(rowConsumer)", q -> q.forEach(rs -> rs.getString(1))),
                operation("forEach(rowFilter,rowConsumer)", q -> q.forEach(rs -> true, rs -> rs.getString(1))),
                operation("forEach(biRowConsumer)", q -> q.forEach((rs, labels) -> rs.getString(1))),
                operation("forEach(biRowFilter,biRowConsumer)", q -> q.forEach((rs, labels) -> true, (rs, labels) -> rs.getString(1))),
                operation("insert(rowMapper)", q -> q.insert(rs -> rs.getLong(1))),
                operation("insert(biRowMapper)", q -> q.insert((rs, labels) -> rs.getLong(1))),
                operation("batchInsert(rowMapper)", q -> q.batchInsert(rs -> rs.getLong(1))), operation("update", TestQuery::update),
                operation("updateAndReturnGeneratedKeys(rowMapper)", q -> q.updateAndReturnGeneratedKeys(rs -> rs.getLong(1))),
                operation("updateAndReturnGeneratedKeys(biRowMapper)", q -> q.updateAndReturnGeneratedKeys((rs, labels) -> rs.getLong(1))),
                operation("batchUpdateAndReturnGeneratedKeys(rowMapper)", q -> q.batchUpdateAndReturnGeneratedKeys(rs -> rs.getLong(1))),
                operation("batchUpdateAndReturnGeneratedKeys(biRowMapper)", q -> q.batchUpdateAndReturnGeneratedKeys((rs, labels) -> rs.getLong(1))),
                operation("largeUpdate", TestQuery::largeUpdate), operation("largeBatchUpdate", TestQuery::largeBatchUpdate),
                operation("execute", TestQuery::execute), operation("executeThenApply(function)", q -> q.executeThenApply(stmt -> 1)),
                operation("executeThenApply(biFunction)", q -> q.executeThenApply((stmt, result) -> 1)),
                operation("executeThenAccept(consumer)", q -> q.executeThenAccept(stmt -> stmt.getUpdateCount())),
                operation("executeThenAccept(biConsumer)", q -> q.executeThenAccept((stmt, result) -> stmt.getUpdateCount())));
    }

    private static QueryOperation operation(final String name, final Throwables.Consumer<TestQuery, SQLException> operation) {
        return new QueryOperation(name, operation);
    }

    private record QueryOperation(String name, Throwables.Consumer<TestQuery, SQLException> operation) {
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
