package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.Throwables;

class SpringScopeLifecycleTest extends TestBase {
    // An equal-priority application callback can precede the scope's synchronization. The recorded owner must still
    // reject scope completion and scope-bound queries once Spring has committed or rolled back the transaction.
    @ParameterizedTest(name = "rollback={0}, operation={1}")
    @CsvSource({ "false,commit", "true,commit", "false,rollback", "true,rollback", "false,query", "true,query" })
    void anEarlierCompletionCannotUseAParticipatingScope(final boolean rollback, final String operation) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final SqlTransaction[] scope = new SqlTransaction[1];
            final PreparedQuery[] query = new PreparedQuery[1];
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicInteger checks = new AtomicInteger();
            try {
                new TransactionTemplate(manager(ds, true)).executeWithoutResult(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public int getOrder() {
                            return Ordered.HIGHEST_PRECEDENCE;
                        }

                        private void check() {
                            capture(failure, () -> {
                                assertEquals(rollback ? 0 : 1, count(keeper));
                                if (operation.equals("rollback")) {
                                    assertThrows(IllegalStateException.class, scope[0]::rollbackIfNotCommitted);
                                    assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, scope[0].status());
                                } else {
                                    if (operation.equals("query")) {
                                        assertThrows(IllegalStateException.class, () -> query[0].queryForInt());
                                    }
                                    assertThrows(IllegalStateException.class, scope[0]::commit);
                                    assertEquals(SqlTransaction.Status.FAILED_COMMIT, scope[0].status());
                                }
                                checks.incrementAndGet();
                            });
                        }

                        @Override
                        public void afterCommit() {
                            if (!rollback) {
                                check();
                            }
                        }

                        @Override
                        public void afterCompletion(final int outcome) {
                            if (rollback) {
                                check();
                            }
                        }
                    });
                    scope[0] = JdbcUtil.beginTransaction(ds);
                    capture(failure, () -> query[0] = JdbcUtil.prepareQuery(ds, "SELECT COUNT(*) FROM item").closeAfterExecution(false));
                    write(ds, 1);
                    if (rollback) {
                        status.setRollbackOnly();
                    }
                });
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertEquals(1, checks.get(), "the earlier completion callback must exercise the scope exactly once");
                assertEquals(rollback ? 0 : 1, count(keeper));
            } finally {
                if (query[0] != null) {
                    query[0].close();
                }
                if (scope[0] != null) {
                    scope[0].close();
                }
            }
        }
    }

    // A scope first created in beforeCompletion misses that phase's synchronization snapshot. It must nevertheless
    // follow its tracked owner's outcome, even before the scope's own afterCompletion notification arrives.
    @Test
    void anEarlierAfterCompletionRejectsALateScopeWithoutItsBeforeCompletionNotification() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final SqlTransaction[] scope = new SqlTransaction[1];
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicInteger checks = new AtomicInteger();
            try {
                new TransactionTemplate(manager(ds, true)).executeWithoutResult(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public int getOrder() {
                            return Ordered.HIGHEST_PRECEDENCE;
                        }

                        @Override
                        public void beforeCompletion() {
                            capture(failure, () -> scope[0] = JdbcUtil.beginTransaction(ds));
                        }

                        @Override
                        public void afterCompletion(final int outcome) {
                            capture(failure, () -> {
                                assertEquals(STATUS_ROLLED_BACK, outcome);
                                assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
                                assertEquals(0, count(keeper));
                                assertThrows(IllegalStateException.class, scope[0]::commit);
                                assertEquals(SqlTransaction.Status.FAILED_COMMIT, scope[0].status());
                                checks.incrementAndGet();
                            });
                        }
                    });
                    // Raw JDBC keeps the first library participation inside beforeCompletion.
                    capture(failure, () -> {
                        final Connection connection = DataSourceUtils.getConnection(ds);
                        try (Statement statement = connection.createStatement()) {
                            statement.executeUpdate("INSERT INTO item VALUES (1)");
                        } finally {
                            DataSourceUtils.releaseConnection(connection, ds);
                        }
                    });
                    status.setRollbackOnly();
                });
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertEquals(1, checks.get(), "the earlier afterCompletion callback must exercise the late scope");
                assertEquals(0, count(keeper));
            } finally {
                if (scope[0] != null) {
                    scope[0].close();
                }
            }
        }
    }

    // beforeCompletion still precedes the physical commit: opening and completing a scope must join the active owner
    // without switching connections or committing the enclosing transaction's work early.
    @Test
    void beforeCompletionWorkStillJoinsItsActiveTrackedOwner() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicInteger checks = new AtomicInteger();
            new TransactionTemplate(manager(ds, true)).executeWithoutResult(status -> {
                write(ds, 1);
                final Connection original = JdbcUtil.getConnection(ds);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCompletion() {
                        capture(failure, () -> {
                            try (SqlTransaction scope = JdbcUtil.beginTransaction(ds)) {
                                assertSame(original, scope.connection());
                                scope.commit();
                                assertEquals(SqlTransaction.Status.COMMITTED, scope.status());
                            }
                            assertEquals(0, count(keeper), "the participating scope must leave the physical commit to Spring");
                            checks.incrementAndGet();
                        });
                    }
                });
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, checks.get(), "beforeCompletion must open and complete the participating scope");
            assertEquals(1, count(keeper));
        }
    }

    @ParameterizedTest(name = "rollback={0}")
    @ValueSource(booleans = { false, true })
    void trackedCompletionDoesNotRunCommitInvalidationsEarly(final boolean rollback) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicInteger invalidations = new AtomicInteger();
            final AtomicBoolean checked = new AtomicBoolean();
            new TransactionTemplate(manager(ds, true)).executeWithoutResult(status -> {
                write(ds, 1);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCompletion() {
                        capture(failure, () -> {
                            JdbcUtil.runAfterCommit(ds, "probe", invalidations::incrementAndGet);
                            assertEquals(0, count(keeper));
                            assertEquals(0, invalidations.get());
                            checked.set(true);
                        });
                    }
                });
                if (rollback) {
                    status.setRollbackOnly();
                }
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(checked.get());
            assertEquals(rollback ? 0 : 1, invalidations.get());
            assertEquals(rollback ? 0 : 1, count(keeper));
        }
    }

    @Test
    void deferredQueryChecksIgnoreAnotherThreadsSpringPhase() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (var worker = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(manager(ds, true)).executeWithoutResult(status -> {
                try (SqlTransaction scope = JdbcUtil.beginTransaction(ds)) {
                    final TransactionContext context = TransactionContext.getTransaction(ds, SqlTransaction.CreatedBy.JDBC_UTIL);
                    try {
                        worker.submit(() -> {
                            TransactionSynchronizationManager.setActualTransactionActive(true);
                            try {
                                context.assertDeferredQueryCanRun();
                            } finally {
                                TransactionSynchronizationManager.clear();
                            }
                        }).get(10, TimeUnit.SECONDS);
                    } catch (final Exception e) {
                        throw new AssertionError(e);
                    }
                    scope.commit();
                }
            });
        }
    }

    @ParameterizedTest(name = "late={0}")
    @ValueSource(booleans = { false, true })
    void missingTrackingRejectsOnlyParticipationEvenWithLateFirstUse(final boolean late) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean checked = new AtomicBoolean();
            new TransactionTemplate(manager(ds, false)).executeWithoutResult(status -> {
                final Runnable check = () -> capture(failure, () -> {
                    final Connection spring = DataSourceUtils.getConnection(ds);
                    final IllegalStateException missing = assertThrows(IllegalStateException.class, () -> JdbcUtil.beginTransaction(ds));
                    assertTrue(missing.getMessage().contains("SpringTransactionTracker"));
                    assertFalse(spring.getAutoCommit());
                    assertFalse(spring.isClosed());
                    assertEquals(1, JdbcUtil.prepareQuery(ds, "SELECT 1").queryForInt().orElseThrow(), "scope-free queries do not require tracking");
                    write(ds, 1);
                    checked.set(true);
                });
                if (late) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public int getOrder() {
                            return Ordered.HIGHEST_PRECEDENCE;
                        }

                        @Override
                        public void beforeCompletion() {
                            check.run();
                        }
                    });
                } else {
                    check.run();
                }
                status.setRollbackOnly();
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(checked.get());
            assertEquals(0, count(keeper), "ordinary SQL still belongs to the original Spring transaction");
        }
    }

    @Test
    void registeringTheTrackerAfterBeginDoesNotInventAnOwner() {
        final JdbcDataSource ds = dataSource();
        final DataSourceTransactionManager manager = manager(ds, false);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            SpringTransactionTracker.register(manager);
            assertThrows(IllegalStateException.class, () -> JdbcUtil.beginTransaction(ds));
        });
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            try (SqlTransaction scope = JdbcUtil.beginTransaction(ds)) {
                scope.commit();
            }
        });
    }

    private static JdbcDataSource dataSource() {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:scope_lifecycle_" + UUID.randomUUID());
        return ds;
    }

    private static DataSourceTransactionManager manager(JdbcDataSource ds, boolean tracked) {
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
        if (tracked)
            SpringTransactionTracker.register(manager);
        return manager;
    }

    private static void initialize(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE item(id INT PRIMARY KEY)");
        }
    }

    private static void write(JdbcDataSource ds, int id) {
        try {
            JdbcUtil.executeUpdate(ds, "INSERT INTO item VALUES (?)", id);
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private static int count(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM item")) {
            r.next();
            return r.getInt(1);
        }
    }

    private static void capture(AtomicReference<Throwable> failure, Throwables.Runnable<? extends Exception> work) {
        try {
            work.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }
}
