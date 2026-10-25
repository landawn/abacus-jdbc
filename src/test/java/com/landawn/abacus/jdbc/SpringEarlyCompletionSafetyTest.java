package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
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

/** Completion callbacks registered before the library, plus execution hidden behind metadata generation. */
class SpringEarlyCompletionSafetyTest extends TestBase {
    // HIGHEST_PRECEDENCE is not unique. Registration order puts this callback before the library's afterCommit.
    @ParameterizedTest(name = "tracked={0}")
    @ValueSource(booleans = { true })
    void anEarlierEqualPriorityAfterCommitCannotWrite(final boolean tracked) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final var manager = manager(ds, tracked);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean checked = new AtomicBoolean();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public int getOrder() {
                        return Ordered.HIGHEST_PRECEDENCE;
                    }

                    @Override
                    public void afterCommit() {
                        capture(failure, () -> {
                            assertThrows(IllegalStateException.class, () -> JdbcUtil.executeUpdate(ds, "INSERT INTO item VALUES (2)"));
                            checked.set(true);
                        });
                    }
                });
                write(ds, 1); // First library use; the earlier callback must already be registered.
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(checked.get());
            assertEquals(1, count(keeper), "cleanup must not commit a callback write");
        }
    }

    // Scope completion can execute no SQL, so an execution guard alone cannot prevent false COMMITTED/ROLLED_BACK.
    @ParameterizedTest(name = "tracked={0}, rollback={1}, operation={2}")
    @CsvSource({ "true,false,commit", "true,true,commit", "true,false,rollback", "true,true,rollback", "true,false,query", "true,true,query" })
    void anEarlierCompletionCannotUseAParticipatingScope(final boolean tracked, final boolean rollback, final String operation) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final SqlTransaction[] scope = new SqlTransaction[1];
            final PreparedQuery[] query = new PreparedQuery[1];
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean checked = new AtomicBoolean();
            try {
                new TransactionTemplate(manager(ds, tracked)).executeWithoutResult(status -> {
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
                                checked.set(true);
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
                assertTrue(checked.get());
                assertEquals(rollback ? 0 : 1, count(keeper));
            } finally {
                if (query[0] != null) {
                    query[0].close();
                }
            }
        }
    }

    // The library's beforeCompletion is absent from Spring's snapshot when first registered inside another callback.
    // Spring has nevertheless cleared synchronization before any afterCompletion, even the earlier one below.
    @ParameterizedTest(name = "tracked={0}")
    @ValueSource(booleans = { true })
    void anEarlierAfterCompletionRejectsALateScopeWithoutItsBeforeCompletionNotification(final boolean tracked) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final SqlTransaction[] scope = new SqlTransaction[1];
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean checked = new AtomicBoolean();
            new TransactionTemplate(manager(ds, tracked)).executeWithoutResult(status -> {
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
                            assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
                            assertEquals(0, count(keeper));
                            assertThrows(IllegalStateException.class, scope[0]::commit);
                            assertEquals(SqlTransaction.Status.FAILED_COMMIT, scope[0].status());
                            checked.set(true);
                        });
                    }
                });
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
            assertTrue(checked.get());
        }
    }

    @Test
    void beforeCompletionWorkStillJoinsItsActiveTrackedOwner() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean checked = new AtomicBoolean();
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
                            }
                            checked.set(true);
                        });
                    }
                });
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(checked.get());
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

    // The public Connection overload may execute unrestricted caller SQL to obtain metadata. A tracked owner must still
    // protect that fallback; a successful description is allowed because it does not execute the statement at all.
    @ParameterizedTest(name = "metadata={0}, duringCompletion={1}")
    @CsvSource({ "normal,false", "null,false", "throws,false", "unusable,false", "normal,true", "null,true", "throws,true", "unusable,true" })
    void codeGenerationGuardsOnlyTheExecutingFallback(final String metadataMode, final boolean duringCompletion) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicInteger executed = new AtomicInteger();
            final AtomicBoolean closed = new AtomicBoolean();
            final AtomicBoolean checked = new AtomicBoolean();
            new TransactionTemplate(manager(ds, true)).executeWithoutResult(status -> {
                write(ds, 1);
                final Connection physical = JdbcUtil.getConnection(ds);
                final Connection connection = metadataConnection(physical, metadataMode, executed, closed);
                final Runnable generate = () -> capture(failure, () -> {
                    final String sql = "SELECT * FROM FINAL TABLE (INSERT INTO item VALUES (2))";
                    if (duringCompletion && !metadataMode.equals("normal")) {
                        assertThrows(IllegalStateException.class, () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "Item", sql));
                    } else {
                        assertTrue(JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "Item", sql).contains("class Item"));
                    }
                    assertTrue(closed.get(), "the prepared statement must close even when execution is refused");
                    assertFalse(physical.isClosed(), "the caller still owns the connection");
                    checked.set(true);
                });
                if (duringCompletion) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            generate.run();
                        }
                    });
                } else {
                    generate.run();
                }
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(checked.get());
            final boolean didExecute = !duringCompletion && !metadataMode.equals("normal");
            assertEquals(didExecute ? 1 : 0, executed.get());
            assertEquals(didExecute ? 2 : 1, count(keeper));
        }
    }

    private static Connection metadataConnection(final Connection connection, final String mode, final AtomicInteger executed, final AtomicBoolean closed) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
            final Object value = invoke(connection, method, args);
            if (!method.getName().equals("prepareStatement")) {
                return value;
            }
            final PreparedStatement statement = (PreparedStatement) value;
            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] { PreparedStatement.class }, (p, m, a) -> {
                if (m.getName().equals("getMetaData")) {
                    if (mode.equals("null")) {
                        return null;
                    }
                    if (mode.equals("throws")) {
                        throw new SQLFeatureNotSupportedException("metadata requires execution");
                    }
                    if (mode.equals("unusable")) {
                        final ResultSetMetaData metadata = statement.getMetaData();
                        return Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(), new Class<?>[] { ResultSetMetaData.class },
                                (mp, mm, ma) -> mm.getName().equals("getColumnCount") ? 0 : invoke(metadata, mm, ma));
                    }
                } else if (m.getName().equals("executeQuery")) {
                    executed.incrementAndGet();
                } else if (m.getName().equals("close")) {
                    closed.set(true);
                }
                return invoke(statement, m, a);
            });
        });
    }

    private static Object invoke(final Object target, final Method method, final Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (final InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static JdbcDataSource dataSource() {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:early_completion_" + UUID.randomUUID());
        return ds;
    }

    private static DataSourceTransactionManager manager(final JdbcDataSource ds, final boolean tracked) {
        final var manager = new DataSourceTransactionManager(ds);
        if (tracked) {
            SpringTransactionTracker.register(manager);
        }
        return manager;
    }

    private static void initialize(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE item(id INT PRIMARY KEY)");
        }
    }

    private static void write(final JdbcDataSource ds, final int id) {
        try {
            JdbcUtil.executeUpdate(ds, "INSERT INTO item VALUES (" + id + ")");
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int count(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM item")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void capture(final AtomicReference<Throwable> failure, final Throwables.Runnable<? extends Exception> action) {
        try {
            action.run();
        } catch (final Throwable e) {
            failure.compareAndSet(null, e);
        }
    }
}
