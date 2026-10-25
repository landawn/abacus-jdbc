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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
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

    // A clean outcome described the original write only. Callback writes were committed by cleanup after another thread
    // cached the old rows. Both a query method and an application default method must fail before writing; REQUIRES_NEW works.
    @ParameterizedTest(name = "afterCommit={0}, tracked={1}, proxy={2}")
    @CsvSource({ "true,true,false", "false,true,false", "true,true,true", "false,true,true" })
    void callbackWritesRequireAnIndependentTransaction(final boolean afterCommit, final boolean tracked, final boolean proxy) throws Exception {
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
                            assertThrows(IllegalStateException.class, () -> dao.addItem(3));
                            assertThrows(IllegalStateException.class, () -> dao.addItemThroughJdbcUtil(3));
                            dao.addItemOnItsOwn(3);
                            assertEquals(3, reader.submit(dao::cachedCount).get(10, TimeUnit.SECONDS));
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
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(3, count(keeper));
            assertEquals(3, reader.submit(dao::cachedCount).get(10, TimeUnit.SECONDS));
        }
    }

    // Neither a cleared holder nor the original transaction's clean report settles new callback work. Exercise both no
    // auto-commit restoration and failed restoration on a prebound connection, which Spring also does not release.
    @ParameterizedTest(name = "afterCommit={0}, failedReset={1}")
    @CsvSource({ "true,false", "false,false", "true,true", "false,true" })
    void aPreboundConnectionCannotKeepARejectedCallbackWritePending(final boolean afterCommit, final boolean failedReset) throws Exception {
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
                            capture(failure, () -> assertThrows(IllegalStateException.class, () -> dao.addItem(3)));
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
                assertEquals(2, count(keeper), "no callback write was left for a later physical commit");
                assertEquals(2, reader.submit(dao::cachedCount).get(10, TimeUnit.SECONDS));
            } finally {
                TransactionSynchronizationManager.unbindResourceIfPossible(ds);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void executionListenerWritesAreGuardedWithoutSynchronization(final boolean rollback) throws Exception {
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
                        assertThrows(IllegalStateException.class, () -> dao.addItem(3));
                        dao.addItemOnItsOwn(3);
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
            assertEquals(rollback ? 2 : 3, count(keeper));
            assertEquals(rollback ? 2 : 3, dao.cachedCount());
        }
    }

    // Missing tracking refuses A's SQL regardless of B's phase. With tracking, active A remains usable during B's callbacks.
    @ParameterizedTest(name = "tracked={0}, afterCommit={1}, innerRollback={2}")
    @CsvSource({ "false,true,false", "true,true,false", "false,false,false", "true,false,false", "false,false,true", "true,false,true" })
    void anotherManagersCallbacksRequireTrackingToIdentifyAnActiveOwner(final boolean tracked, final boolean afterCommit, final boolean innerRollback)
            throws Exception {
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
            new TransactionTemplate(outerManager).executeWithoutResult(outer -> {
                new TransactionTemplate(innerManager).executeWithoutResult(inner -> {
                    JdbcUtil.getConnection(b); // Register the library's action synchronization on B.
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        private void work() {
                            capture(failure, () -> {
                                if (tracked) {
                                    JdbcUtil.executeUpdate(a, "INSERT INTO cache_tx_item VALUES (2, 'outer')");
                                } else {
                                    assertThrows(IllegalStateException.class, () -> JdbcUtil.executeUpdate(a, "INSERT INTO cache_tx_item VALUES (2, 'outer')"));
                                }
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
                capture(failure, () -> {
                    if (tracked)
                        JdbcUtil.executeUpdate(a, "INSERT INTO cache_tx_item VALUES (3, 'after B')");
                    else
                        assertThrows(IllegalStateException.class, () -> JdbcUtil.executeUpdate(a, "INSERT INTO cache_tx_item VALUES (3, 'after B')"));
                });
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                outer.setRollbackOnly();
            });
            assertEquals(1, count(keeper), "the callback's write still belongs to A and rolls back with it");
        }
    }

    // Tracked owners permit both synchronization pre-commit phases. Missing tracking is refused in either phase,
    // regardless of when the library first registered its action synchronization.
    @ParameterizedTest(name = "beforeCompletion={0}, tracked={1}, alreadyWatching={2}")
    @CsvSource({ "false,false,false", "false,true,false", "true,false,false", "true,true,false", "false,false,true", "false,true,true", "true,false,true",
            "true,true,true" })
    void synchronizationWorkBeforeTheCommitHonorsTheTrackingBoundary(final boolean beforeCompletion, final boolean tracked, final boolean alreadyWatching)
            throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final var dao = cachedDao(ds);
            final var manager = new DataSourceTransactionManager(ds);
            if (tracked) {
                SpringTransactionTracker.register(manager);
            }
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final boolean refused = !tracked;
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                if (alreadyWatching) {
                    JdbcUtil.releaseConnection(JdbcUtil.getConnection(ds), ds);
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    private void work() {
                        capture(failure, () -> {
                            if (refused) {
                                assertThrows(IllegalStateException.class, () -> dao.addItem(2));
                                assertThrows(IllegalStateException.class,
                                        () -> JdbcUtil.executeQuery(ds, "SELECT * FROM FINAL TABLE (INSERT INTO cache_tx_item VALUES (3, 'early'))"));
                            } else {
                                dao.addItem(2);
                                assertEquals(1, JdbcUtil.executeQuery(ds, "SELECT * FROM FINAL TABLE (INSERT INTO cache_tx_item VALUES (3, 'early'))").size());
                            }
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
            assertEquals(refused ? 1 : 3, count(keeper));
            assertEquals(refused ? 1 : 3, dao.cachedCount());
        }
    }

    // An execution listener runs before the physical commit/rollback but after the tracker's deciding notification.
    // Refusal is a conservative library boundary, not a claim that the connection's original transaction has already ended.
    @ParameterizedTest(name = "rollback={0}, synchronization={1}")
    @CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void executionListenerSqlIsRefusedBeforeThePhysicalOutcomeButAfterBeginSqlIsAllowed(final boolean rollback, final boolean synchronization)
            throws Exception {
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
                        assertThrows(IllegalStateException.class, () -> dao.addItem(4));
                        assertThrows(IllegalStateException.class, () -> JdbcUtil.executeQuery(ds, "SELECT 1"));
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
            assertEquals(rollback ? 1 : 3, count(keeper));
            assertEquals(rollback ? 1 : 3, dao.cachedCount());
        }
    }

    // Regression: an end marker reset the guard before a callback registered later in beforeCompletion. In the late-scope
    // variant the initial-marker fix made this false COMMITTED result newly reachable after a database rollback.
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

    // Checking connection acquisition alone misses a statement prepared before completion. Queries can write too:
    // H2's FINAL TABLE returns rows from an insert. Every execution variant must check before the JDBC statement runs.
    @ParameterizedTest
    @ValueSource(strings = { "update", "largeUpdate", "batch", "largeBatch", "execute", "query" })
    void aPreviouslyPreparedStatementCannotWriteDuringCompletion(final String operation) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final var manager = new DataSourceTransactionManager(ds);
            SpringTransactionTracker.register(manager);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final PreparedStatement[] statement = new PreparedStatement[1];
            try {
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    try {
                        final String insert = "INSERT INTO cache_tx_item VALUES (2, 'late')";
                        statement[0] = JdbcUtil.getConnection(ds)
                                .prepareStatement(operation.equals("query") ? "SELECT * FROM FINAL TABLE (" + insert + ")" : insert);
                        if (operation.equals("batch") || operation.equals("largeBatch")) {
                            statement[0].addBatch();
                        }
                    } catch (final SQLException ex) {
                        throw new IllegalStateException(ex);
                    }
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            capture(failure, () -> assertThrows(IllegalStateException.class, () -> {
                                switch (operation) {
                                    case "update" -> JdbcUtil.executeUpdate(statement[0]);
                                    case "largeUpdate" -> JdbcUtil.executeLargeUpdate(statement[0]);
                                    case "batch" -> JdbcUtil.executeBatch(statement[0]);
                                    case "largeBatch" -> JdbcUtil.executeLargeBatch(statement[0]);
                                    case "execute" -> JdbcUtil.execute(statement[0]);
                                    case "query" -> JdbcUtil.executeQuery(statement[0]);
                                    default -> throw new AssertionError(operation);
                                }
                            }));
                        }
                    });
                });
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertEquals(1, count(keeper));
            } finally {
                if (statement[0] != null) {
                    statement[0].close();
                }
            }
        }
    }

    // The action synchronization belongs to B while the scope belongs to A. The required owner record keeps A usable
    // throughout B's callbacks; synchronization ordering cannot distinguish these owners.
    @ParameterizedTest(name = "registerInAfterCommit={0}, innerRollback={1}, tracked={2}")
    @CsvSource({ "false,false,true", "false,true,true", "true,false,true" })
    void aLateScopeOnAnActiveOuterTransactionRequiresTrackingAfterTheInnerCompletion(final boolean registerInAfterCommit, final boolean innerRollback,
            final boolean tracked) throws Exception {
        final JdbcDataSource a = dataSource();
        final JdbcDataSource b = dataSource();
        final PreparedQuery[] query = new PreparedQuery[1];
        final SqlTransaction[] scope = new SqlTransaction[1];
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final var outerManager = new DataSourceTransactionManager(a);
        final var innerManager = new DataSourceTransactionManager(b);
        if (tracked) {
            SpringTransactionTracker.register(outerManager, innerManager);
        }
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
                                if (tracked) {
                                    assertEquals(1, query[0].queryForInt().orElseThrow());
                                } else {
                                    assertThrows(IllegalStateException.class, () -> query[0].queryForInt());
                                }
                            });
                        }
                    });
                    if (innerRollback) {
                        inner.setRollbackOnly();
                    }
                });
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                try {
                    if (tracked) {
                        assertEquals(1, query[0].queryForInt().orElseThrow(), "A's own record remains active after B's callbacks");
                        scope[0].commit();
                    } else {
                        assertThrows(IllegalStateException.class, () -> query[0].queryForInt());
                        final IllegalStateException rejected = assertThrows(IllegalStateException.class, scope[0]::commit);
                        assertTrue(rejected.getMessage().contains("SpringTransactionTracker"));
                        assertEquals(SqlTransaction.Status.FAILED_COMMIT, scope[0].status());
                    }
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
