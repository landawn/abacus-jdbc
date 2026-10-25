package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.Throwables;

class SpringBeforeCommitFailureTest extends TestBase {
    static Stream<Arguments> failures() {
        return Stream.of("direct", "parallel", "callable", "scope").flatMap(operation ->
                Stream.of(false, true).flatMap(afterBegin -> Stream.of(Ordered.HIGHEST_PRECEDENCE, 0, Ordered.LOWEST_PRECEDENCE)
                        .flatMap(order -> Stream.of("runtime", "error", "unknown")
                                .map(failure -> Arguments.of(operation, afterBegin, order, failure)))));
    }

    // Spring can skip every beforeCommit execution listener when a synchronization fails. Its fallback must report
    // completion before application callbacks, including equally ordered callbacks registered by afterBegin listeners.
    @ParameterizedTest(name = "{0}, afterBegin={1}, order={2}, failure={3}")
    @MethodSource("failures")
    void aBeforeCommitFailureCannotReopenTheCompletedTransaction(final String operation, final boolean afterBegin,
            final int order, final String failureKind) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            raw(keeper, "CREATE TABLE item(id INT PRIMARY KEY)");
            final DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
            final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            final AtomicReference<SqlTransaction> scope = new AtomicReference<>();
            final AtomicReference<com.landawn.abacus.util.stream.Stream<Integer>> stream = new AtomicReference<>();
            final AtomicReference<CallableQuery> callable = new AtomicReference<>();
            final AtomicBoolean registered = new AtomicBoolean();
            final AtomicBoolean checked = new AtomicBoolean();
            final Throwable expected = switch (failureKind) {
                case "error" -> new AssertionError("beforeCommit failed");
                case "unknown" -> new TransactionSystemException("beforeCommit failed");
                default -> new IllegalArgumentException("beforeCommit failed");
            };
            final Runnable register = () -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public int getOrder() { return order; }
                    @Override public void beforeCommit(final boolean readOnly) {
                        if (expected instanceof Error error) throw error;
                        throw (RuntimeException) expected;
                    }
                    @Override public void afterCompletion(final int status) {
                        capture(callbackFailure, () -> {
                            assertEquals(failureKind.equals("unknown") ? STATUS_UNKNOWN : STATUS_ROLLED_BACK, status);
                            switch (operation) {
                                case "direct" -> assertThrows(IllegalStateException.class,
                                        () -> JdbcUtil.executeUpdate(ds, "INSERT INTO item VALUES (2)"));
                                case "parallel" -> assertThrows(IllegalStateException.class,
                                        () -> stream.get().parallel(4).map(id -> id).toList());
                                case "callable" -> worker.submit(() -> assertThrows(IllegalStateException.class,
                                        callable.get()::queryForInt)).get(10, TimeUnit.SECONDS);
                                default -> {
                                    assertThrows(IllegalStateException.class, scope.get()::commit);
                                    assertEquals(SqlTransaction.Status.FAILED_COMMIT, scope.get().status());
                                }
                            }
                            checked.set(true);
                        });
                    }
                });
                registered.set(true);
            };
            manager.addListener(new TransactionExecutionListener() {
                @Override public void afterBegin(final TransactionExecution transaction, final Throwable beginFailure) {
                    if (afterBegin && beginFailure == null) register.run();
                }
            });
            SpringTransactionTracker.register(manager); // Must move before an already configured application listener.
            try {
                assertSame(expected, assertThrows(expected.getClass(), () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                    if (!afterBegin) register.run();
                    capture(callbackFailure, () -> {
                        raw(bound(ds), "INSERT INTO item VALUES (1)");
                        final String sql = "SELECT * FROM FINAL TABLE (INSERT INTO item VALUES (2))";
                        switch (operation) {
                            case "parallel" -> stream.set(JdbcUtil.prepareQuery(ds, sql).stream(Integer.class));
                            case "callable" -> callable.set(JdbcUtil.prepareCallableQuery(ds, sql));
                            case "scope" -> scope.set(JdbcUtil.beginTransaction(ds));
                            default -> { }
                        }
                    });
                })));
                assertNull(callbackFailure.get(), () -> String.valueOf(callbackFailure.get()));
                assertTrue(registered.get());
                assertTrue(checked.get());
                // With the default rollbackOnCommitFailure=false, a TransactionException reports UNKNOWN and cleanup
                // may commit the original work. The library must still prevent any additional callback write.
                assertEquals(failureKind.equals("unknown") ? 1 : 0, JdbcUtil.prepareQuery(keeper, "SELECT COUNT(*) FROM item").queryForInt().get());
            } finally {
                if (stream.get() != null) stream.get().close();
                if (callable.get() != null) callable.get().close();
                if (scope.get() != null) scope.get().close();
            }
        }
    }

    @Test
    void callbacksInstalledByTheManagerKeepTheirOrderAfterTheOwnersReport() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            raw(keeper, "CREATE TABLE item(id INT PRIMARY KEY)");
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final java.util.List<Integer> callbacks = new java.util.ArrayList<>();
            final IllegalArgumentException expected = new IllegalArgumentException("manager callback failed");
            final DataSourceTransactionManager manager = new DataSourceTransactionManager(ds) {
                @Override protected void prepareSynchronization(final DefaultTransactionStatus status, final TransactionDefinition definition) {
                    super.prepareSynchronization(status, definition);
                    for (int index = 0; index < 2; index++) {
                        final int callback = index;
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
                            @Override public void beforeCommit(final boolean readOnly) { throw expected; }
                            @Override public void afterCompletion(final int outcome) {
                                capture(failure, () -> {
                                    assertThrows(IllegalStateException.class, () -> JdbcUtil.executeUpdate(ds, "INSERT INTO item VALUES (2)"));
                                    callbacks.add(callback);
                                });
                            }
                        });
                    }
                }
            };
            SpringTransactionTracker.register(manager);
            assertSame(expected, assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status ->
                    capture(failure, () -> raw(bound(ds), "INSERT INTO item VALUES (1)")))));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(java.util.List.of(0, 1), callbacks);
            assertEquals(0, JdbcUtil.prepareQuery(keeper, "SELECT COUNT(*) FROM item").queryForInt().get());
        }
    }

    @ParameterizedTest(name = "differentManager={0}")
    @ValueSource(booleans = { false, true })
    void theFailingInnerTransactionDoesNotCompleteItsActiveOuterOwner(final boolean differentManager) throws Exception {
        final JdbcDataSource outerDs = dataSource();
        final JdbcDataSource innerDs = differentManager ? dataSource() : outerDs;
        try (Connection outerKeeper = outerDs.getConnection(); Connection innerKeeper = innerDs.getConnection()) {
            raw(outerKeeper, "CREATE TABLE item(id INT PRIMARY KEY)");
            if (differentManager) raw(innerKeeper, "CREATE TABLE item(id INT PRIMARY KEY)");
            final DataSourceTransactionManager outerManager = SpringTestTransactions.manager(outerDs);
            final TransactionTemplate inner = new TransactionTemplate(differentManager ? SpringTestTransactions.manager(innerDs) : outerManager);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean checked = new AtomicBoolean();
            new TransactionTemplate(outerManager).executeWithoutResult(outerStatus -> capture(failure, () -> {
                try (PreparedQuery outerQuery = JdbcUtil.prepareQuery(outerDs, "INSERT INTO item VALUES (3)")) {
                    final IllegalArgumentException expected = new IllegalArgumentException("inner beforeCommit");
                    assertSame(expected, assertThrows(IllegalArgumentException.class, () -> inner.executeWithoutResult(innerStatus -> {
                        capture(failure, () -> raw(bound(innerDs), "INSERT INTO item VALUES (2)"));
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
                            @Override public void beforeCommit(final boolean readOnly) { throw expected; }
                            @Override public void afterCompletion(final int status) {
                                capture(failure, () -> {
                                    assertThrows(IllegalStateException.class, () -> JdbcUtil.executeUpdate(innerDs, "INSERT INTO item VALUES (4)"));
                                    assertEquals(1, outerQuery.update());
                                    checked.set(true);
                                });
                            }
                        });
                    })));
                    assertEquals(1, JdbcUtil.executeUpdate(outerDs, "INSERT INTO item VALUES (1)"));
                }
            }));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(checked.get());
            assertEquals(2, JdbcUtil.prepareQuery(outerKeeper, "SELECT COUNT(*) FROM item").queryForInt().get());
            if (differentManager) assertEquals(0, JdbcUtil.prepareQuery(innerKeeper, "SELECT COUNT(*) FROM item").queryForInt().get());
        }
    }

    private static JdbcDataSource dataSource() {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:before_commit_failure_" + UUID.randomUUID());
        return ds;
    }
    private static Connection bound(final JdbcDataSource ds) { return ((ConnectionHolder) TransactionSynchronizationManager.getResource(ds)).getConnection(); }
    private static void raw(final Connection connection, final String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.executeUpdate(sql); }
    }
    private static void capture(final AtomicReference<Throwable> failure, final Throwables.Runnable<? extends Exception> action) {
        try { action.run(); } catch (final Throwable e) { failure.compareAndSet(null, e); }
    }
}
