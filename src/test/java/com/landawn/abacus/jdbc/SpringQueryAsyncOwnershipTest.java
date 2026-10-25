package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.landawn.abacus.TestBase;

class SpringQueryAsyncOwnershipTest extends TestBase {
    static Stream<Arguments> queries() {
        return Stream.of("prepared", "named", "callable", "largePrepared", "largeNamed")
                .flatMap(family -> Stream.of(false, true)
                        .flatMap(connection -> Stream.of(false, true).map(tracked -> Arguments.of(family, connection, tracked))));
    }

    @ParameterizedTest(name = "family={0}, connection={1}, tracked={2}")
    @MethodSource("queries")
    void scopeFreeSpringQueriesAllowAllAsyncSubmissionOverloads(final String family, final boolean connection, final boolean tracked) {
        final JdbcDataSource ds = dataSource();
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
        if (tracked)
            SpringTransactionTracker.register(manager);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            final Connection original = DataSourceUtils.getConnection(ds);
            for (String action : new String[] { "call", "run", "callExecutor", "runExecutor" }) {
                try (AbstractQuery<?, ?> query = query(family, connection, ds, original).closeAfterExecution(false)) {
                    final AtomicBoolean invoked = new AtomicBoolean();
                    final AtomicBoolean submitted = new AtomicBoolean();
                    // No JDBC work on the worker: this asserts submission policy, not cross-thread connection safety.
                    final var future = switch (action) {
                        case "call" -> query.callAsync(q -> {
                            invoked.set(true);
                            return 1;
                        });
                        case "run" -> query.runAsync(q -> invoked.set(true));
                        case "callExecutor" -> query.callAsync(q -> {
                            invoked.set(true);
                            return 1;
                        }, task -> {
                            submitted.set(true);
                            task.run();
                        });
                        default -> query.runAsync(q -> invoked.set(true), task -> {
                            submitted.set(true);
                            task.run();
                        });
                    };
                    future.get(10, TimeUnit.SECONDS);
                    assertTrue(invoked.get());
                    assertEquals(action.endsWith("Executor"), submitted.get());
                    assertFalse(query.isClosed, "automatic closing is disabled; cleanup stays on the owner thread");
                    assertFalse(original.isClosed());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            try {
                assertEquals(1, JdbcUtil.prepareQuery(ds, "SELECT 1").queryForInt().orElseThrow(),
                        "synchronous execution remains available without a scope or tracker");
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void scopeFreeSubmissionDoesNotCheckTheOriginalSpringTransactionAfterCleanup() throws Exception {
        final JdbcDataSource ds = dataSource();
        final AtomicReference<PreparedQuery> query = new AtomicReference<>();
        try {
            new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(status -> {
                try {
                    query.set(JdbcUtil.prepareQuery(ds, "SELECT 1").closeAfterExecution(false));
                } catch (SQLException e) {
                    throw new AssertionError(e);
                }
            });
            // Spring may have closed the statement already. Acceptance does not promise that SQL could still run.
            assertEquals(1, query.get().callAsync(q -> 1).get(10, TimeUnit.SECONDS));
        } finally {
            if (query.get() != null)
                query.get().close();
        }
    }

    @Test
    void unrelatedSpringTransactionsDoNotPreventIndependentAsyncQueries() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection independent = ds.getConnection()) {
            new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(status -> {
                try (PreparedQuery query = JdbcUtil.prepareQuery(independent, "SELECT 1")) {
                    assertEquals(1, query.callAsync(q -> q.queryForInt().orElseThrow()).get(10, TimeUnit.SECONDS));
                    assertFalse(independent.isClosed());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    @Test
    void synchronizationOnlyBindingsAllowAsyncQueries() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection connection = ds.getConnection()) {
            TransactionSynchronizationManager.bindResource(ds, new ConnectionHolder(connection));
            try (PreparedQuery query = JdbcUtil.prepareQuery(connection, "SELECT 1")) {
                assertEquals(1, query.callAsync(q -> q.queryForInt().orElseThrow()).get(10, TimeUnit.SECONDS));
            } finally {
                TransactionSynchronizationManager.unbindResource(ds);
            }
            assertFalse(connection.isClosed());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void transactionAwareProxiesDoNotAddAnAsyncOwnershipCheck(final boolean connectionOverload) {
        final JdbcDataSource ds = dataSource();
        final DataSource proxy = new TransactionAwareDataSourceProxy(ds);
        new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(status -> {
            try {
                final Connection c = JdbcUtil.getConnection(proxy);
                try (PreparedQuery query = (connectionOverload ? JdbcUtil.prepareQuery(c, "SELECT 1") : JdbcUtil.prepareQuery(proxy, "SELECT 1"))
                        .closeAfterExecution(false)) {
                    query.runAsync(q -> {
                    }).get(10, TimeUnit.SECONDS);
                } finally {
                    JdbcUtil.releaseConnection(c, proxy);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void queryConstructionDoesNotReadStatementConnectionIdentity() {
        final JdbcDataSource ds = dataSource();
        new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(status -> {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            try {
                when(stmt.getConnection()).thenThrow(new SQLException("identity must not be inspected"));
                final Connection connection = DataSourceUtils.getConnection(ds);
                try (PreparedQuery query = JdbcUtil.prepareQuery(connection, "SELECT 1", (ignored, sql) -> stmt)) {
                    assertFalse(query.isClosed);
                    verify(stmt, never()).getConnection();
                    verify(stmt, never()).close();
                }
                verify(stmt).close();
                assertFalse(connection.isClosed());
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void queryConstructionDoesNotReadSpringHolderOwnership() throws SQLException {
        final JdbcDataSource ds = dataSource();
        final AtomicInteger reads = new AtomicInteger();
        try (Connection connection = ds.getConnection()) {
            final ConnectionHolder holder = new ConnectionHolder(connection) {
                @Override
                protected boolean isTransactionActive() {
                    reads.incrementAndGet();
                    throw new IllegalStateException("holder ownership must not be inspected");
                }
            };
            TransactionSynchronizationManager.bindResource(ds, holder);
            try (PreparedQuery query = JdbcUtil.prepareQuery(connection, "SELECT 1")) {
                assertEquals(1, query.queryForInt().orElseThrow());
                assertEquals(0, reads.get());
                assertFalse(connection.isClosed());
            } finally {
                TransactionSynchronizationManager.unbindResource(ds);
            }
        }
    }

    private static AbstractQuery<?, ?> query(String family, boolean connection, DataSource ds, Connection c) throws SQLException {
        return switch (family) {
            case "named" -> connection ? JdbcUtil.prepareNamedQuery(c, "SELECT 1") : JdbcUtil.prepareNamedQuery(ds, "SELECT 1");
            case "callable" -> connection ? JdbcUtil.prepareCallableQuery(c, "SELECT 1") : JdbcUtil.prepareCallableQuery(ds, "SELECT 1");
            case "largePrepared" -> connection ? JdbcUtil.prepareQueryForLargeResult(c, "SELECT 1") : JdbcUtil.prepareQueryForLargeResult(ds, "SELECT 1");
            case "largeNamed" -> connection ? JdbcUtil.prepareNamedQueryForLargeResult(c, "SELECT 1")
                    : JdbcUtil.prepareNamedQueryForLargeResult(ds, "SELECT 1");
            default -> connection ? JdbcUtil.prepareQuery(c, "SELECT 1") : JdbcUtil.prepareQuery(ds, "SELECT 1");
        };
    }

    private static JdbcDataSource dataSource() {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:async_owner_" + UUID.randomUUID());
        return ds;
    }
}
