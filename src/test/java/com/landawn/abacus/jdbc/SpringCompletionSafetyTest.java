package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.Throwables;

/** Regressions for work and cache observations during Spring's completion callbacks. */
class SpringCompletionSafetyTest extends TestBase {

    // Callback work gets its own outcome and cache invalidation with both Spring and DAO REQUIRES_NEW. The original
    // transaction's rollback must not undo that work; no statement-level guard enforces this application responsibility.
    @ParameterizedTest(name = "afterCommit={0}, tracked={1}, proxy={2}, rollback={3}")
    @CsvSource({ "true,true,false,false", "false,true,false,false", "true,false,false,false", "false,false,false,false", "true,true,true,false",
            "false,true,true,false", "false,true,false,true", "false,false,false,true", "false,true,true,true" })
    void independentCallbackTransactionsCommitAndRefreshCaches(final boolean afterCommit, final boolean tracked, final boolean proxy, final boolean rollback)
            throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection();
             ExecutorService reader = Executors.newSingleThreadExecutor()) {
            initialize(keeper);
            final var dao = cachedDao(proxy ? new TransactionAwareDataSourceProxy(ds) : ds);
            final var manager = new DataSourceTransactionManager(ds);
            if (tracked) {
                SpringTransactionTracker.register(manager);
            }
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                insert(dao, 2);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    private void work() {
                        capture(failure, () -> {
                            dao.addItemOnItsOwn(3);
                            final TransactionTemplate independent = new TransactionTemplate(manager);
                            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                            independent.executeWithoutResult(inner -> insert(dao, 4));
                            assertEquals(rollback ? 3 : 4, reader.submit(dao::cachedCount).get(10, TimeUnit.SECONDS));
                        });
                    }

                    @Override
                    public void afterCommit() {
                        if (afterCommit) {
                            work();
                        }
                    }

                    @Override
                    public void afterCompletion(final int outcome) {
                        if (!afterCommit) {
                            work();
                        }
                    }
                });
                if (rollback) {
                    status.setRollbackOnly();
                }
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(rollback ? 3 : 4, count(keeper));
            assertEquals(rollback ? 3 : 4, reader.submit(dao::cachedCount).get(10, TimeUnit.SECONDS));
        }
    }

    // Unsupported callback work on the original connection reaches JDBC. With H2 and a prebound connection, it can remain
    // pending beyond cleanup and commit after old rows have been cached. Immediate invalidation does not prevent that race.
    // Exercise both no auto-commit restoration and failed restoration; neither outcome is a portable JDBC guarantee.
    @ParameterizedTest(name = "afterCommit={0}, failedReset={1}")
    @CsvSource({ "true,false", "false,false", "true,true", "false,true" })
    void callbackWorkOnAPreboundConnectionCanOutliveCompletionAndLeaveStaleCache(final boolean afterCommit, final boolean failedReset) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection();
             Connection physical = ds.getConnection();
             ExecutorService reader = Executors.newSingleThreadExecutor()) {
            initialize(keeper);
            Connection bound = physical;
            if (failedReset) {
                bound = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                    if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                        throw new SQLException("reset failed");
                    }
                    try {
                        return method.invoke(physical, args);
                    } catch (final InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
            } else {
                physical.setAutoCommit(false);
            }
            TransactionSynchronizationManager.bindResource(ds, new ConnectionHolder(bound));
            try {
                final var dao = cachedDao(ds);
                final var manager = new DataSourceTransactionManager(ds);
                SpringTransactionTracker.register(manager);
                final AtomicReference<Throwable> failure = new AtomicReference<>();
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    insert(dao, 2);
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        private void work() {
                            capture(failure, () -> dao.addItem(3));
                        }

                        @Override
                        public void afterCommit() {
                            if (afterCommit) {
                                work();
                            }
                        }

                        @Override
                        public void afterCompletion(final int outcome) {
                            if (!afterCommit) {
                                work();
                            }
                        }
                    });
                });
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertEquals(2, reader.submit(dao::cachedCount).get(10, TimeUnit.SECONDS));
                physical.commit();
                assertEquals(3, count(keeper), "the unsupported callback write remained pending after Spring completed");
                assertEquals(2, reader.submit(dao::cachedCount).get(10, TimeUnit.SECONDS),
                        "the original completion report cannot invalidate a refill made before the callback write's later commit");
            } finally {
                TransactionSynchronizationManager.unbindResourceIfPossible(ds);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void executionListenerSqlReachesJdbcWithoutSynchronization(final boolean rollback) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final var dao = cachedDao(ds);
            final var manager = new DataSourceTransactionManager(ds);
            manager.setTransactionSynchronization(org.springframework.transaction.support.AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
            SpringTransactionTracker.register(manager);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            manager.addListener(new org.springframework.transaction.TransactionExecutionListener() {
                private void work() {
                    capture(failure, () -> {
                        dao.addItem(3); // Unsupported reuse: H2 commits this additional work during JDBC cleanup.
                        dao.addItemOnItsOwn(4);
                    });
                }

                @Override
                public void afterCommit(final org.springframework.transaction.TransactionExecution transaction, final Throwable commitFailure) {
                    work();
                }

                @Override
                public void afterRollback(final org.springframework.transaction.TransactionExecution transaction, final Throwable rollbackFailure) {
                    work();
                }
            });
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                insert(dao, 2);
                if (rollback) {
                    status.setRollbackOnly();
                }
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(rollback ? 3 : 4, count(keeper));
            assertEquals(rollback ? 3 : 4, dao.cachedCount());
        }
    }

    // Statement execution on active A is unaffected by B's callbacks, with or without a tracker. Participating scopes
    // require tracking and follow their recorded owner's lifetime, covered below.
    @ParameterizedTest(name = "tracked={0}, afterCommit={1}, innerRollback={2}")
    @CsvSource({ "false,true,false", "true,true,false", "false,false,false", "true,false,false", "false,false,true", "true,false,true" })
    void anotherManagersCallbacksDoNotRejectSqlOnAnActiveOwner(final boolean tracked, final boolean afterCommit, final boolean innerRollback) throws Exception {
        final JdbcDataSource a = dataSource();
        final JdbcDataSource b = dataSource();
        try (Connection keeper = a.getConnection()) {
            initialize(keeper);
            final var outerManager = new DataSourceTransactionManager(a);
            final var innerManager = new DataSourceTransactionManager(b);
            if (tracked) {
                SpringTransactionTracker.register(outerManager, innerManager);
            }
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicInteger callbackCalls = new AtomicInteger();
            new TransactionTemplate(outerManager).executeWithoutResult(outer -> {
                new TransactionTemplate(innerManager).executeWithoutResult(inner -> {
                    JdbcUtil.getConnection(b); // Observe B's completion without imposing it on A's statement execution.
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        private void work() {
                            capture(failure, () -> {
                                assertEquals(1, JdbcUtil.executeUpdate(a, "INSERT INTO cache_tx_item VALUES (2, 'outer')"));
                                callbackCalls.incrementAndGet();
                            });
                        }

                        @Override
                        public void afterCommit() {
                            if (afterCommit) {
                                work();
                            }
                        }

                        @Override
                        public void afterCompletion(final int status) {
                            if (!afterCommit) {
                                work();
                            }
                        }
                    });
                    if (innerRollback) {
                        inner.setRollbackOnly();
                    }
                });
                capture(failure, () -> JdbcUtil.executeUpdate(a, "INSERT INTO cache_tx_item VALUES (3, 'after B')"));
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertEquals(1, callbackCalls.get(), "B's selected completion callback must successfully write to active A exactly once");
                outer.setRollbackOnly();
            });
            assertEquals(1, count(keeper), "the callback's write still belongs to A and rolls back with it");
        }
    }

    // Spring's beforeCommit/beforeCompletion synchronizations run before the physical commit, so their SQL remains part
    // of the original transaction, as does an execution listener's beforeCommit work (covered separately below).
    @ParameterizedTest(name = "beforeCompletion={0}, tracked={1}")
    @CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void synchronizationWorkBeforeTheCommitRemainsAllowed(final boolean beforeCompletion, final boolean tracked) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final var dao = cachedDao(ds);
            final var manager = new DataSourceTransactionManager(ds);
            if (tracked) {
                SpringTransactionTracker.register(manager);
            }
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    private void work() {
                        capture(failure, () -> {
                            dao.addItem(2);
                            assertEquals(1, JdbcUtil.executeQuery(ds, "SELECT * FROM FINAL TABLE (INSERT INTO cache_tx_item VALUES (3, 'early'))").size());
                        });
                    }

                    @Override
                    public void beforeCommit(final boolean readOnly) {
                        if (!beforeCompletion) {
                            work();
                        }
                    }

                    @Override
                    public void beforeCompletion() {
                        if (beforeCompletion) {
                            work();
                        }
                    }
                });
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(3, count(keeper));
            assertEquals(3, dao.cachedCount());
        }
    }

    // An execution listener runs before the physical commit/rollback but after the tracker's deciding notification.
    // This notification still limits participating scopes; it no longer prevents statement execution in the original transaction.
    @ParameterizedTest(name = "rollback={0}, synchronization={1}")
    @CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void executionListenerSqlBeforeThePhysicalOutcomeJoinsTheOriginalTransaction(final boolean rollback, final boolean synchronization) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final var dao = cachedDao(ds);
            final AtomicBoolean physicalOutcomeStarted = new AtomicBoolean();
            final AtomicBoolean checked = new AtomicBoolean();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final var manager = new DataSourceTransactionManager(ds) {
                @Override
                protected void doCommit(final DefaultTransactionStatus status) {
                    physicalOutcomeStarted.set(true);
                    super.doCommit(status);
                }

                @Override
                protected void doRollback(final DefaultTransactionStatus status) {
                    physicalOutcomeStarted.set(true);
                    super.doRollback(status);
                }
            };
            if (!synchronization) {
                manager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
            }
            SpringTransactionTracker.register(manager);
            manager.addListener(new TransactionExecutionListener() {
                @Override
                public void afterBegin(final TransactionExecution transaction, final Throwable beginFailure) {
                    capture(failure, () -> dao.addItem(2));
                }

                private void check() {
                    capture(failure, () -> {
                        assertFalse(physicalOutcomeStarted.get());
                        assertEquals(1, count(keeper), "the transaction's writes have not committed");
                        dao.addItem(4);
                        assertEquals(1, JdbcUtil.executeQuery(ds, "SELECT 1").size());
                        checked.set(true);
                    });
                }

                @Override
                public void beforeCommit(final TransactionExecution transaction) {
                    check();
                }

                @Override
                public void beforeRollback(final TransactionExecution transaction) {
                    check();
                }
            });
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                insert(dao, 3);
                if (rollback) {
                    status.setRollbackOnly();
                }
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(checked.get());
            assertTrue(physicalOutcomeStarted.get());
            assertEquals(rollback ? 1 : 4, count(keeper));
            assertEquals(rollback ? 1 : 4, dao.cachedCount());
        }
    }

    // Regression: an end marker reset the completion observation before a callback registered later in beforeCompletion.
    // In the late-scope variant the initial-marker fix made this false COMMITTED result newly reachable after a database rollback.
    @ParameterizedTest(name = "tracked={0}, lateScope={1}, rollback={2}, rollbackScope={3}")
    @CsvSource({ "true,false,false,false", "true,false,true,false", "true,true,false,false", "true,true,true,false", "true,false,false,true",
            "true,true,false,true" })
    void aLaterCompletionCallbackCannotCompleteAScopeOnTheCompletedTransaction(final boolean tracked, final boolean lateScope, final boolean rollback,
            final boolean rollbackScope) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final var manager = new DataSourceTransactionManager(ds);
            if (tracked) {
                SpringTransactionTracker.register(manager);
            }
            final SqlTransaction[] scope = new SqlTransaction[1];
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean checked = new AtomicBoolean();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                if (!lateScope) {
                    scope[0] = JdbcUtil.beginTransaction(ds);
                }
                // Do not use JdbcUtil for this write: the late case must first register its library synchronization inside
                // beforeCompletion, after Spring has already taken that phase's callback snapshot.
                capture(failure, () -> {
                    try (Statement statement = DataSourceUtils.getConnection(ds).createStatement()) {
                        statement.executeUpdate("INSERT INTO cache_tx_item VALUES (2, 'original')");
                    }
                });
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCompletion() {
                        capture(failure, () -> {
                            if (lateScope) {
                                scope[0] = JdbcUtil.beginTransaction(ds);
                            }
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override
                                public void afterCompletion(final int outcome) {
                                    capture(failure, () -> {
                                        assertEquals(rollback ? 1 : 2, count(keeper));
                                        if (rollbackScope) {
                                            // A rollback-only mark after Spring's commit cannot undo the committed row either.
                                            assertThrows(IllegalStateException.class, scope[0]::rollbackIfNotCommitted);
                                            assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, scope[0].status());
                                        } else {
                                            assertThrows(IllegalStateException.class, scope[0]::commit);
                                            assertEquals(SqlTransaction.Status.FAILED_COMMIT, scope[0].status());
                                        }
                                        checked.set(true);
                                    });
                                }
                            });
                        });
                    }
                });
                if (rollback) {
                    status.setRollbackOnly();
                }
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(checked.get());
            assertEquals(rollback ? 1 : 2, count(keeper));
        }
    }

    // All six statement helpers delegate to JDBC during completion, including result-producing SQL that writes. This H2
    // fixture commits the callback row when Spring restores auto-commit, even after rollback. It demonstrates the removed
    // protection, not a portable outcome or a supported way to perform callback work; use REQUIRES_NEW instead.
    @ParameterizedTest(name = "operation={0}, phase={1}")
    @MethodSource("completionExecutions")
    void previouslyPreparedStatementsReachJdbcDuringCompletion(final String operation, final String phase) throws Exception {
        final boolean rollback = phase.equals("afterCompletionRollback");
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final var manager = new DataSourceTransactionManager(ds);
            SpringTransactionTracker.register(manager);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean executed = new AtomicBoolean();
            final PreparedStatement[] statement = new PreparedStatement[1];
            try {
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    try {
                        JdbcUtil.executeUpdate(ds, "INSERT INTO cache_tx_item VALUES (2, 'original')");
                        final String insert = "INSERT INTO cache_tx_item VALUES (3, 'late')";
                        statement[0] = JdbcUtil.getConnection(ds)
                                .prepareStatement(operation.equals("query") ? "SELECT * FROM FINAL TABLE (" + insert + ")" : insert);
                        if (operation.equals("batch") || operation.equals("largeBatch")) {
                            statement[0].addBatch();
                        }
                    } catch (final SQLException ex) {
                        throw new IllegalStateException(ex);
                    }
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        private void work() {
                            capture(failure, () -> {
                                assertEquals(rollback ? 1 : 2, count(keeper));
                                switch (operation) {
                                    case "update" -> assertEquals(1, JdbcUtil.executeUpdate(statement[0]));
                                    case "largeUpdate" -> assertEquals(1L, JdbcUtil.executeLargeUpdate(statement[0]));
                                    case "batch" -> assertEquals(1, JdbcUtil.executeBatch(statement[0])[0]);
                                    case "largeBatch" -> assertEquals(1L, JdbcUtil.executeLargeBatch(statement[0])[0]);
                                    case "execute" -> {
                                        assertFalse(JdbcUtil.execute(statement[0]));
                                        assertEquals(1, statement[0].getUpdateCount());
                                    }
                                    case "query" -> {
                                        try (ResultSet result = JdbcUtil.executeQuery(statement[0])) {
                                            assertTrue(result.next());
                                            assertEquals(3, result.getInt(1));
                                            assertFalse(result.next());
                                        }
                                    }
                                    default -> throw new AssertionError(operation);
                                }
                                executed.set(true);
                            });
                        }

                        @Override
                        public void afterCommit() {
                            if (phase.equals("afterCommit")) {
                                work();
                            }
                        }

                        @Override
                        public void afterCompletion(final int outcome) {
                            if (!phase.equals("afterCommit")) {
                                work();
                            }
                        }
                    });
                    if (rollback) {
                        status.setRollbackOnly();
                    }
                });
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertTrue(executed.get(), "the completion callback must have executed SQL");
                assertEquals(rollback ? 2 : 3, count(keeper));
            } finally {
                if (statement[0] != null) {
                    statement[0].close();
                }
            }
        }
    }

    private static Stream<Arguments> completionExecutions() {
        return Stream.of("update", "largeUpdate", "batch", "largeBatch", "execute", "query")
                .flatMap(
                        operation -> Stream.of("afterCommit", "afterCompletionCommit", "afterCompletionRollback").map(phase -> Arguments.of(operation, phase)));
    }

    // A scope opened on A during B's callbacks follows A's recorded owner. B's completion must leave that scope and its
    // associated queries usable while A remains active.
    @ParameterizedTest(name = "registerInAfterCommit={0}, innerRollback={1}")
    @CsvSource({ "false,false", "false,true", "true,false" })
    void aLateScopeOnAnActiveOuterTransactionRemainsUsableAfterTheInnerCompletion(final boolean registerInAfterCommit, final boolean innerRollback)
            throws Exception {
        final JdbcDataSource a = dataSource();
        final JdbcDataSource b = dataSource();
        final PreparedQuery[] query = new PreparedQuery[1];
        final SqlTransaction[] scope = new SqlTransaction[1];
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final var outerManager = new DataSourceTransactionManager(a);
        final var innerManager = new DataSourceTransactionManager(b);
        SpringTransactionTracker.register(outerManager, innerManager);
        try {
            new TransactionTemplate(outerManager).executeWithoutResult(outer -> {
                new TransactionTemplate(innerManager).executeWithoutResult(inner -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        private void prepare() {
                            capture(failure, () -> {
                                scope[0] = JdbcUtil.beginTransaction(a);
                                query[0] = JdbcUtil.prepareQuery(a, "SELECT 1").closeAfterExecution(false);
                            });
                        }

                        @Override
                        public void beforeCompletion() {
                            if (!registerInAfterCommit) {
                                prepare();
                            }
                        }

                        @Override
                        public void afterCommit() {
                            if (registerInAfterCommit) {
                                prepare();
                            }
                        }

                        @Override
                        public void afterCompletion(final int status) {
                            capture(failure, () -> {
                                assertEquals(1, query[0].queryForInt().orElseThrow());
                            });
                        }
                    });
                    if (innerRollback) {
                        inner.setRollbackOnly();
                    }
                });
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                try {
                    assertEquals(1, query[0].queryForInt().orElseThrow(), "A's own record remains active after B's callbacks");
                    scope[0].commit();
                } catch (final SQLException ex) {
                    throw new IllegalStateException(ex);
                } finally {
                    scope[0].close();
                }
            });
        } finally {
            if (query[0] != null) {
                query[0].close();
            }
        }
    }

    // A completion observation must remain set after all callbacks too; queries can otherwise revive a completed connection.
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void aLateSynchronizationKeepsItsCompletionObservation(final boolean rollback) {
        final JdbcDataSource ds = dataSource();
        final AtomicReference<Object> synchronization = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final PreparedQuery[] query = new PreparedQuery[1];
        try {
            new TransactionTemplate(SpringTestTransactions.manager(ds)).executeWithoutResult(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCompletion() {
                        capture(failure, () -> {
                            try (SqlTransaction scope = JdbcUtil.beginTransaction(ds)) {
                                query[0] = JdbcUtil.prepareQuery(ds, "SELECT 1").closeAfterExecution(false);
                                synchronization.set(JdbcUtil.participationSynchronization());
                                scope.commit();
                            }
                        });
                    }

                    @Override
                    public void afterCompletion(final int outcome) {
                        capture(failure, () -> {
                            assertTrue(JdbcUtil.hasSpringSynchronizationCompletionStarted(synchronization.get()));
                            assertThrows(IllegalStateException.class, () -> query[0].queryForInt());
                        });
                    }
                });
                if (rollback) {
                    status.setRollbackOnly();
                }
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(JdbcUtil.hasSpringSynchronizationCompletionStarted(synchronization.get()));
            assertThrows(IllegalStateException.class, () -> query[0].queryForInt());
        } finally {
            if (query[0] != null) {
                query[0].close();
            }
        }
    }

    // Spring catches beforeCompletion failures individually. An earlier throwing callback must not prevent completion from
    // being observed, including after the callbacks have all returned.
    @ParameterizedTest(name = "rollback={0}, error={1}")
    @CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void aThrowingBeforeCompletionDoesNotHideCompletion(final boolean rollback, final boolean error) {
        final AtomicReference<Object> synchronization = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource())).executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public int getOrder() {
                    return Ordered.HIGHEST_PRECEDENCE;
                }

                @Override
                public void beforeCompletion() {
                    if (error) {
                        throw new AssertionError("beforeCompletion failed");
                    }
                    throw new IllegalStateException("beforeCompletion failed");
                }
            });
            synchronization.set(JdbcUtil.participationSynchronization());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(final int outcome) {
                    capture(failure, () -> assertTrue(JdbcUtil.hasSpringSynchronizationCompletionStarted(synchronization.get())));
                }
            });
            if (rollback) {
                status.setRollbackOnly();
            }
        });
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertTrue(JdbcUtil.hasSpringSynchronizationCompletionStarted(synchronization.get()));
    }

    // A logger can block or reenter a DAO. Assert in the actual appender, not just outside Owner's own synchronized block.
    @Test
    void aMissingReportWarningRunsOutsideThePendingMapMonitor() throws Exception {
        final Class<?> pending = Class.forName(DaoImpl.class.getName() + "$PendingCommits");
        final Class<?> generations = Class.forName(DaoImpl.class.getName() + "$CacheGenerations");
        final Method add = pending.getDeclaredMethod("add", String.class, Object.class);
        final Method isPending = pending.getDeclaredMethod("isPending", int.class);
        final Method slotOf = generations.getDeclaredMethod("slotOf", String.class);
        final Field holders = pending.getDeclaredField("holders");
        add.setAccessible(true);
        isPending.setAccessible(true);
        slotOf.setAccessible(true);
        holders.setAccessible(true);
        final AtomicBoolean ended = new AtomicBoolean();
        final var owner = SpringTransactionOwners.began(new Object(), new Object(), ended::get, "warning-lock-test");
        final String table = "warning_lock_test";
        add.invoke(null, table, owner);
        final int slot = (int) slotOf.invoke(null, table);
        final Object map = ((AtomicReferenceArray<?>) holders.get(null)).get(slot);
        final AtomicBoolean logged = new AtomicBoolean();
        final AtomicBoolean locked = new AtomicBoolean();
        final AbstractAppender appender = new AbstractAppender("warning-lock-test", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY) {
            @Override
            public void append(final LogEvent event) {
                logged.set(true);
                locked.set(Thread.holdsLock(map));
            }
        };
        final String loggerName = SpringTransactionOwners.class.getName();
        final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        final var config = context.getConfiguration();
        final LoggerConfig previous = config.getLoggers().get(loggerName);
        final LoggerConfig logger = new LoggerConfig(loggerName, Level.WARN, false);
        appender.start();
        logger.addAppender(appender, Level.WARN, null);
        config.addLogger(loggerName, logger);
        context.updateLoggers();
        try {
            ended.set(true);
            assertTrue((boolean) isPending.invoke(null, slot));
            assertTrue(logged.get());
            assertFalse(locked.get(), "external logging must not hold the pending-writes map monitor");
        } finally {
            owner.completed();
            isPending.invoke(null, slot);
            config.removeLogger(loggerName);
            if (previous != null) {
                config.addLogger(loggerName, previous);
            }
            context.updateLoggers();
            appender.stop();
        }
    }

    private static JdbcDataSource dataSource() {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:completion_safety_" + UUID.randomUUID());
        return ds;
    }

    private static DaoCacheTransactionTest.CachedItemDao cachedDao(final javax.sql.DataSource ds) {
        return JdbcUtil.createDao(DaoCacheTransactionTest.CachedItemDao.class, ds,
                JdbcUtil.DaoCreationOptions.builder().cache(Jdbc.DaoCache.createByMap()).build());
    }

    private static void initialize(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE cache_tx_item (id BIGINT PRIMARY KEY, name VARCHAR(20))");
            statement.execute("INSERT INTO cache_tx_item VALUES (1, 'initial')");
        }
    }

    private static int count(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM cache_tx_item")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void insert(final DaoCacheTransactionTest.CachedItemDao dao, final long id) {
        try {
            dao.addItem(id);
        } catch (final SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // Spring logs and swallows afterCompletion failures, including assertion errors; always inspect them after it returns.
    private static void capture(final AtomicReference<Throwable> failure, final Throwables.Runnable<? extends Exception> action) {
        try {
            action.run();
        } catch (final Throwable ex) {
            failure.compareAndSet(null, ex);
        }
    }
}
