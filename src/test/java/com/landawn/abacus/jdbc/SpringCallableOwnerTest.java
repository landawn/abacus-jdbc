package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.landawn.abacus.TestBase;

class SpringCallableOwnerTest extends TestBase {
    private static final String INSERT_QUERY = "SELECT * FROM FINAL TABLE (INSERT INTO item VALUES (2))";

    static Stream<Arguments> executions() {
        return Stream.of("scalar", "executeQuery", "execute", "apply", "accept", "outParams", "allResults", "allResultsWithColumns",
                "twoResults", "threeResults", "listClass", "listMapper", "listFilter", "listMapperWithColumns", "listFilterWithColumns")
                .flatMap(operation -> Stream.of("active", "commit", "rollback", "cleanup")
                        .flatMap(phase -> Stream.of(false, true).map(connection -> Arguments.of(operation, phase, connection))));
    }

    // CallableQuery overrides the scalar-query executor and has its own OUT-parameter/multiple-result executors.
    // A stream-only test misses those paths. Returning rows does not make the SQL read-only.
    @ParameterizedTest(name = "{0}, phase={1}, connection={2}")
    @MethodSource("executions")
    void directExecutionsKeepTheirOriginalSpringOwner(final String operation, final String phase, final boolean connectionOverload) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection(); Connection reusable = ds.getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            raw(keeper, "CREATE TABLE item(id INT PRIMARY KEY)");
            // A prebound connection remains usable after cleanup: rejection must come from the captured owner,
            // rather than happen accidentally because the transaction manager closed the JDBC statement.
            if (phase.equals("cleanup")) TransactionSynchronizationManager.bindResource(ds, new ConnectionHolder(reusable));
            final AtomicReference<CallableQuery> query = new AtomicReference<>();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean checked = new AtomicBoolean();
            final Runnable execute = () -> {
                try (CallableQuery callable = query.get()) {
                    if (phase.equals("active")) execute(callable, operation);
                    else assertThrows(IllegalStateException.class, () -> execute(callable, operation));
                    checked.set(true);
                } catch (final Throwable e) {
                    failure.compareAndSet(null, e);
                }
            };
            final Runnable executeOnWorker = () -> {
                try {
                    // The owner waits, so execution is sequential, but the worker has no Spring thread-local resources.
                    worker.submit(execute).get(10, TimeUnit.SECONDS);
                } catch (final Throwable e) {
                    failure.compareAndSet(null, e);
                }
            };
            try {
                new TransactionTemplate(tracked(ds)).executeWithoutResult(status -> {
                    try {
                        raw(bound(ds), "INSERT INTO item VALUES (1)");
                        query.set(connectionOverload ? JdbcUtil.prepareCallableQuery(bound(ds), INSERT_QUERY)
                                : JdbcUtil.prepareCallableQuery(ds, INSERT_QUERY));
                        assertNull(query.get().transaction, "exercise captured owners without an explicit SqlTransaction");
                        if (phase.equals("active")) executeOnWorker.run();
                        else TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override public void afterCommit() { if (phase.equals("commit")) executeOnWorker.run(); }
                            @Override public void afterCompletion(final int outcome) { if (phase.equals("rollback")) executeOnWorker.run(); }
                        });
                        if (phase.equals("rollback") || phase.equals("cleanup")) status.setRollbackOnly();
                    } catch (final SQLException e) {
                        throw new AssertionError(e);
                    }
                });
                if (phase.equals("cleanup")) {
                    assertFalse(reusable.isClosed());
                    execute.run();
                }
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertTrue(checked.get());
                assertEquals(phase.equals("active") ? List.of(1, 2) : phase.equals("commit") ? List.of(1) : List.of(), rows(keeper));
            } finally {
                if (query.get() != null) query.get().close();
                if (phase.equals("cleanup")) TransactionSynchronizationManager.unbindResource(ds);
            }
        }
    }

    @Test
    void aCompletedOwnerIsCheckedBeforeFetchDirectionTouchesTheDriver() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection connection = ds.getConnection()) {
            TransactionSynchronizationManager.bindResource(ds, new ConnectionHolder(connection));
            try {
                final AtomicReference<CallableStatement> statement = new AtomicReference<>();
                final AtomicReference<CallableQuery> query = new AtomicReference<>();
                new TransactionTemplate(tracked(ds)).executeWithoutResult(status -> {
                    try {
                        statement.set(spy(connection.prepareCall("SELECT 1")));
                        query.set(new CallableQuery(statement.get()));
                        query.get().closeAfterExecution(false);
                    } catch (final SQLException e) {
                        throw new AssertionError(e);
                    }
                });
                try (CallableQuery callable = query.get()) {
                    clearInvocations(statement.get());
                    assertThrows(IllegalStateException.class, callable::queryForInt);
                    verifyNoInteractions(statement.get());
                }
            } finally {
                TransactionSynchronizationManager.unbindResource(ds);
            }
        }
    }

    private static void execute(final CallableQuery query, final String operation) throws SQLException {
        final Jdbc.ResultExtractor<Integer> extractor = rs -> rs.next() ? rs.getInt(1) : null;
        final Jdbc.BiResultExtractor<Integer> biExtractor = (rs, columns) -> rs.next() ? rs.getInt(1) : null;
        final Jdbc.RowMapper<Integer> mapper = rs -> rs.getInt(1);
        final Jdbc.BiRowMapper<Integer> biMapper = (rs, columns) -> rs.getInt(1);
        switch (operation) {
            case "scalar" -> assertEquals(2, query.queryForInt().get());
            case "executeQuery" -> { try (ResultSet rs = query.executeQuery()) { assertTrue(rs.next()); assertEquals(2, rs.getInt(1)); } }
            case "execute" -> assertTrue(query.execute());
            case "apply" -> assertEquals(Boolean.TRUE, query.executeThenApply((stmt, outParams, resultSet) -> resultSet));
            case "accept" -> query.executeThenAccept((stmt, outParams, resultSet) -> assertTrue(resultSet));
            case "outParams" -> query.executeAndGetOutParameters();
            case "allResults" -> assertEquals(List.of(2), query.queryAllResultSetsAndGetOutParameters(extractor)._1);
            case "allResultsWithColumns" -> assertEquals(List.of(2), query.queryAllResultSetsAndGetOutParameters(biExtractor)._1);
            case "twoResults" -> assertEquals(2, query.query2ResultSetsAndGetOutParameters(biExtractor, biExtractor)._1);
            case "threeResults" -> assertEquals(2, query.query3ResultSetsAndGetOutParameters(biExtractor, biExtractor, biExtractor)._1);
            case "listClass" -> assertEquals(List.of(List.of(2)), query.listAllResultSetsAndGetOutParameters(Integer.class)._1);
            case "listMapper" -> assertEquals(List.of(List.of(2)), query.listAllResultSetsAndGetOutParameters(mapper)._1);
            case "listFilter" -> assertEquals(List.of(List.of(2)), query.listAllResultSetsAndGetOutParameters(rs -> true, mapper)._1);
            case "listMapperWithColumns" -> assertEquals(List.of(List.of(2)), query.listAllResultSetsAndGetOutParameters(biMapper)._1);
            case "listFilterWithColumns" -> assertEquals(List.of(List.of(2)), query.listAllResultSetsAndGetOutParameters((rs, columns) -> true, biMapper)._1);
            default -> throw new AssertionError(operation);
        }
    }

    private static JdbcDataSource dataSource() {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:callable_owner_" + UUID.randomUUID());
        return ds;
    }
    private static DataSourceTransactionManager tracked(final JdbcDataSource ds) {
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
        SpringTransactionTracker.register(manager);
        return manager;
    }
    private static Connection bound(final JdbcDataSource ds) { return ((ConnectionHolder) TransactionSynchronizationManager.getResource(ds)).getConnection(); }
    private static void raw(final Connection connection, final String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) { stmt.executeUpdate(sql); }
    }
    private static List<Integer> rows(final Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id FROM item ORDER BY id")) {
            final java.util.ArrayList<Integer> rows = new java.util.ArrayList<>();
            while (rs.next()) rows.add(rs.getInt(1));
            return rows;
        }
    }
}
