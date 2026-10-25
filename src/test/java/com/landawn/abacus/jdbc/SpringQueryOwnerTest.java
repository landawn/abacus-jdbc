package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.Throwables;

/** Query ownership survives deferred execution, worker handoff, callback ordering and holder reuse. */
class SpringQueryOwnerTest extends TestBase {
    static Stream<Arguments> queryCases() {
        return Stream.of("prepared", "named", "callable")
                .flatMap(family -> Stream.of(false, true)
                        .flatMap(connection -> Stream.of("active", "commit", "rollback")
                                .flatMap(phase -> Stream.of(false, true).map(late -> Arguments.of(family, connection, phase, late)))));
    }

    // Without an explicit SqlTransaction the query used to retain no owner at all. SQL returning rows can write, too.
    @ParameterizedTest
    @MethodSource("queryCases")
    void scopeFreeParallelQueriesCheckTheirCapturedOwner(final String family, final boolean connectionOverload, final String phase, final boolean late)
            throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicReference<com.landawn.abacus.util.stream.Stream<Integer>> stream = new AtomicReference<>();
            final AtomicBoolean prepared = new AtomicBoolean();
            final AtomicBoolean consumed = new AtomicBoolean();
            final Runnable prepare = () -> capture(failure, () -> {
                final AbstractQuery<?, ?> query = query(ds, family, connectionOverload, "SELECT * FROM FINAL TABLE (INSERT INTO item VALUES (2))");
                assertNull(query.transaction, "test the scope-free path");
                stream.set(query.stream(Integer.class));
                prepared.set(true);
            });
            final Runnable consume = () -> capture(failure, () -> {
                assertNotNull(stream.get());
                if (phase.equals("active")) {
                    assertEquals(List.of(2), stream.get().parallel(4).map(id -> id).toList());
                } else {
                    assertThrows(IllegalStateException.class, () -> stream.get().parallel(4).map(id -> id).toList());
                }
                consumed.set(true);
            });
            new TransactionTemplate(tracked(ds)).executeWithoutResult(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public int getOrder() {
                        return Ordered.HIGHEST_PRECEDENCE;
                    }

                    @Override
                    public void beforeCompletion() {
                        if (late) {
                            prepare.run(); // Registered after Spring took its beforeCompletion snapshot.
                            if (phase.equals("active"))
                                consume.run();
                        }
                    }

                    @Override
                    public void afterCommit() {
                        if (phase.equals("commit"))
                            consume.run();
                    }

                    @Override
                    public void afterCompletion(final int outcome) {
                        if (phase.equals("rollback"))
                            consume.run();
                    }
                });
                capture(failure, () -> raw(bound(ds), "INSERT INTO item VALUES (1)"));
                if (!late) {
                    prepare.run();
                    if (phase.equals("active"))
                        consume.run();
                }
                if (phase.equals("rollback"))
                    status.setRollbackOnly();
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(prepared.get());
            assertTrue(consumed.get());
            assertEquals(phase.equals("rollback") ? 0 : phase.equals("active") ? 2 : 1, count(keeper));
        }
    }

    @ParameterizedTest(name = "tracked={0}")
    @ValueSource(booleans = { false, true })
    void lateFirstUseCannotWriteFromAnEarlierAfterCommit(final boolean tracked) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean prepared = new AtomicBoolean();
            final AtomicBoolean checked = new AtomicBoolean();
            final DataSourceTransactionManager manager = tracked ? tracked(ds) : new DataSourceTransactionManager(ds);
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public int getOrder() {
                        return Ordered.HIGHEST_PRECEDENCE;
                    }

                    @Override
                    public void beforeCompletion() {
                        // Spring logs beforeCompletion failures and continues: capture them and prove setup ran.
                        capture(failure, () -> {
                            if (tracked) {
                                try (SqlTransaction scope = JdbcUtil.beginTransaction(ds)) {
                                    scope.commit();
                                }
                            } else {
                                assertThrows(IllegalStateException.class, () -> JdbcUtil.beginTransaction(ds));
                                assertThrows(IllegalStateException.class, () -> JdbcUtil.prepareQuery(ds, "SELECT 1"));
                            }
                            prepared.set(true);
                        });
                    }

                    @Override
                    public void afterCommit() {
                        capture(failure, () -> {
                            assertThrows(IllegalStateException.class, () -> JdbcUtil.executeUpdate(ds, "INSERT INTO item VALUES (2)"));
                            checked.set(true);
                        });
                    }
                });
                capture(failure, () -> raw(bound(ds), "INSERT INTO item VALUES (1)"));
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(prepared.get());
            assertTrue(checked.get());
            assertEquals(1, count(keeper));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "update", "largeUpdate", "batch", "largeBatch", "execute", "list", "allResults", "streamAllResults" })
    void immediateAndMultipleResultExecutionOnAWorkerAlsoChecksTheOwner(final String operation) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection();
             var worker = Executors.newSingleThreadExecutor()) {
            initialize(keeper);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean checked = new AtomicBoolean();
            new TransactionTemplate(tracked(ds)).executeWithoutResult(status -> {
                capture(failure, () -> {
                    final String sql = operation.equals("list") || operation.contains("Results") ? "SELECT * FROM FINAL TABLE (INSERT INTO item VALUES (2))"
                            : "INSERT INTO item VALUES (2)";
                    final PreparedQuery query = JdbcUtil.prepareQuery(ds, sql);
                    if (operation.equals("batch") || operation.equals("largeBatch"))
                        query.addBatch();
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(final int outcome) {
                            capture(failure, () -> worker.submit(() -> {
                                try (query) {
                                    assertThrows(IllegalStateException.class, () -> {
                                        switch (operation) {
                                            case "update" -> query.update();
                                            case "largeUpdate" -> query.largeUpdate();
                                            case "batch" -> query.batchUpdate();
                                            case "largeBatch" -> query.largeBatchUpdate();
                                            case "execute" -> query.execute();
                                            case "list" -> query.list(Integer.class);
                                            case "allResults" -> query.queryAllResultSets();
                                            default -> query.streamAllResultSets().parallel(4).map(result -> result).toList();
                                        }
                                    });
                                    checked.set(true);
                                }
                            }).get(10, TimeUnit.SECONDS));
                        }
                    });
                });
                status.setRollbackOnly();
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(checked.get());
            assertEquals(0, count(keeper));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void explicitParticipatingScopesKeepParallelExecutionInsideTheirOwnerLifetime(final boolean completed) throws Exception {
        final JdbcDataSource ds = dataSource();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean checked = new AtomicBoolean();
        new TransactionTemplate(tracked(ds)).executeWithoutResult(status -> capture(failure, () -> {
            final SqlTransaction scope = JdbcUtil.beginTransaction(ds);
            final var stream = JdbcUtil.prepareQuery(ds, "SELECT 1").stream(Integer.class);
            if (!completed) {
                assertEquals(List.of(1), stream.parallel(4).map(id -> id).toList());
                scope.commit();
                checked.set(true);
            } else {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(final int outcome) {
                        capture(failure, () -> {
                            assertThrows(IllegalStateException.class, () -> stream.parallel(4).map(id -> id).toList());
                            assertThrows(IllegalStateException.class, scope::commit);
                            assertEquals(SqlTransaction.Status.FAILED_COMMIT, scope.status());
                            checked.set(true);
                        });
                    }
                });
                status.setRollbackOnly();
            }
        }));
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertTrue(checked.get());
    }

    @Test
    void aScopeAndWritingParallelQueryFirstCreatedDuringBeforeCompletionCannotOutliveRollback() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicReference<SqlTransaction> scope = new AtomicReference<>();
            final AtomicReference<com.landawn.abacus.util.stream.Stream<Integer>> stream = new AtomicReference<>();
            final AtomicBoolean prepared = new AtomicBoolean();
            final AtomicBoolean checked = new AtomicBoolean();
            try {
                new TransactionTemplate(tracked(ds)).executeWithoutResult(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public int getOrder() {
                            return Ordered.HIGHEST_PRECEDENCE;
                        }

                        @Override
                        public void beforeCompletion() {
                            capture(failure, () -> {
                                scope.set(JdbcUtil.beginTransaction(ds));
                                final PreparedQuery query = JdbcUtil.prepareQuery(ds, "SELECT * FROM FINAL TABLE (INSERT INTO item VALUES (2))");
                                assertNotNull(query.transaction, "exercise the explicit participating context");
                                stream.set(query.stream(Integer.class));
                                prepared.set(true);
                            });
                        }

                        @Override
                        public void afterCompletion(final int outcome) {
                            capture(failure, () -> {
                                assertEquals(STATUS_ROLLED_BACK, outcome);
                                assertNotNull(stream.get());
                                assertThrows(IllegalStateException.class, () -> stream.get().parallel(4).map(id -> id).toList());
                                assertThrows(IllegalStateException.class, scope.get()::commit);
                                assertEquals(SqlTransaction.Status.FAILED_COMMIT, scope.get().status());
                                checked.set(true);
                            });
                        }
                    });
                    capture(failure, () -> raw(bound(ds), "INSERT INTO item VALUES (1)"));
                    status.setRollbackOnly();
                });
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                assertTrue(prepared.get());
                assertTrue(checked.get());
                assertEquals(0, count(keeper));
            } finally {
                if (stream.get() != null)
                    stream.get().close();
                if (scope.get() != null)
                    scope.get().close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void missingTrackingRejectsOnlyTheMatchingTransactionConnection(final boolean registeredAfterBegin) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection independent = ds.getConnection()) {
            initialize(independent);
            final DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                if (registeredAfterBegin)
                    SpringTransactionTracker.register(manager);
                final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> JdbcUtil.beginTransaction(ds));
                assertTrue(failure.getMessage().contains("before transactions begin"));
                assertThrows(IllegalStateException.class, () -> JdbcUtil.prepareQuery(ds, "SELECT 1"));
                assertThrows(IllegalStateException.class, () -> JdbcUtil.prepareQuery(bound(ds), "SELECT 1"));
                assertThrows(IllegalStateException.class, () -> JdbcUtil.executeUpdate(ds, "INSERT INTO item VALUES (1)"));
                try (PreparedStatement stmt = bound(ds).prepareStatement("INSERT INTO item VALUES (2)")) {
                    assertThrows(IllegalStateException.class, () -> JdbcUtil.executeUpdate(stmt));
                    assertEquals(1, JdbcUtil.prepareQuery(independent, "INSERT INTO item VALUES (3)").update());
                    JdbcUtil.callOutsideTransaction(ds, () -> JdbcUtil.executeUpdate(ds, "INSERT INTO item VALUES (4)"));
                } catch (final SQLException e) {
                    throw new AssertionError(e);
                }
                status.setRollbackOnly();
            });
            assertEquals(2, count(independent));
        }
    }

    @Test
    void independentTrackedRequiresNewWorkIsAllowedDuringCompletion() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection keeper = ds.getConnection()) {
            initialize(keeper);
            final DataSourceTransactionManager manager = tracked(ds);
            final TransactionTemplate inner = new TransactionTemplate(manager);
            inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(final int outcome) {
                        capture(failure,
                                () -> inner.executeWithoutResult(nested -> capture(failure,
                                        () -> assertEquals(List.of(2),
                                                JdbcUtil.prepareQuery(ds, "SELECT * FROM FINAL TABLE (INSERT INTO item VALUES (2))")
                                                        .stream(Integer.class)
                                                        .parallel(4)
                                                        .map(id -> id)
                                                        .toList()))));
                    }
                });
                status.setRollbackOnly();
            });
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, count(keeper));
        }
    }

    @Test
    void synchronizationOnlyBindingsNeedNoTracker() throws Exception {
        final JdbcDataSource ds = dataSource();
        final TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(ds));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
        template.executeWithoutResult(status -> {
            try {
                assertEquals(List.of(1), JdbcUtil.prepareQuery(ds, "SELECT 1").stream(Integer.class).parallel(4).map(id -> id).toList());
            } catch (final SQLException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void unrelatedWorkerStateCannotRejectAnActiveCapturedOwner() throws Exception {
        final JdbcDataSource ds = dataSource();
        try (var worker = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(tracked(ds)).executeWithoutResult(status -> {
                try (var stream = JdbcUtil.prepareQuery(ds, "SELECT 1").stream(Integer.class)) {
                    assertEquals(List.of(1), worker.submit(() -> {
                        TransactionSynchronizationManager.setActualTransactionActive(true);
                        try {
                            return stream.toList();
                        } finally {
                            TransactionSynchronizationManager.clear();
                        }
                    }).get(10, TimeUnit.SECONDS));
                } catch (final Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "call", "run", "callExecutor", "runExecutor" })
    void scopeFreeSpringQueriesRejectAsyncSubmission(final String action) {
        final JdbcDataSource ds = dataSource();
        new TransactionTemplate(tracked(ds)).executeWithoutResult(status -> {
            try (PreparedQuery query = JdbcUtil.prepareQuery(ds, "SELECT 1")) {
                assertThrows(IllegalStateException.class, () -> {
                    switch (action) {
                        case "call" -> query.callAsync(q -> q.queryForInt());
                        case "run" -> query.runAsync(q -> q.queryForInt());
                        case "callExecutor" -> query.callAsync(q -> q.queryForInt(), Runnable::run);
                        default -> query.runAsync(q -> q.queryForInt(), Runnable::run);
                    }
                });
                assertTrue(query.isClosed);
            } catch (final SQLException e) {
                throw new AssertionError(e);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void captureFailureClosesOnlyTheStatementAndPreservesTheFailure(final boolean identityFailure) throws Exception {
        final JdbcDataSource ds = dataSource();
        new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(status -> {
            try {
                final PreparedStatement stmt = mock(PreparedStatement.class);
                final SQLException original = new SQLException("identity failed");
                if (identityFailure)
                    when(stmt.getConnection()).thenThrow(original);
                else
                    when(stmt.getConnection()).thenReturn(bound(ds));
                final SQLException closeFailure = new SQLException("close failed");
                doThrow(closeFailure).when(stmt).close();
                final RuntimeException failure = identityFailure ? assertThrows(UncheckedSQLException.class, () -> new PreparedQuery(stmt))
                        : assertThrows(IllegalStateException.class, () -> new PreparedQuery(stmt));
                if (identityFailure)
                    assertSame(original, failure.getCause());
                assertArrayEquals(new Throwable[] { closeFailure }, failure.getSuppressed());
                verify(stmt).close();
                assertFalse(bound(ds).isClosed());
                verify(stmt, never()).executeQuery();
            } catch (final SQLException e) {
                throw new AssertionError(e);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void aliasesAndHolderReuseCannotReplaceACapturedOwner(final boolean firstCompletes) throws Exception {
        final JdbcDataSource ds = dataSource();
        try (Connection connection = ds.getConnection();
             var worker = Executors.newSingleThreadExecutor()) {
            final ActiveHolder first = new ActiveHolder(connection);
            final ActiveHolder alias = new ActiveHolder(connection);
            final Object firstKey = new Object();
            final Object aliasKey = new Object();
            final SpringTransactionOwners.Owner original = SpringTransactionOwners.began(new Object(), first, () -> false, "original");
            final SpringTransactionOwners.Owner other = SpringTransactionOwners.began(new Object(), alias, () -> false, "alias");
            TransactionSynchronizationManager.bindResource(firstKey, first);
            TransactionSynchronizationManager.bindResource(aliasKey, alias);
            try (PreparedQuery query = JdbcUtil.prepareQuery(connection, "SELECT 1").closeAfterExecution(false)) {
                (firstCompletes ? original : other).deciding();
                // No holder bindings on the worker: selecting only one arbitrary owner would miss one of these variants.
                worker.submit(() -> assertThrows(IllegalStateException.class, query::queryForInt, "either matching owner must veto execution"))
                        .get(10, TimeUnit.SECONDS);
                SpringTransactionOwners.began(new Object(), alias, () -> false, "replacement alias");
                original.completed();
                SpringTransactionOwners.began(new Object(), first, () -> false, "replacement original");
                worker.submit(() -> assertThrows(IllegalStateException.class, query::queryForInt, "old query must retain its original identities"))
                        .get(10, TimeUnit.SECONDS);
                assertEquals(1, JdbcUtil.prepareQuery(connection, "SELECT 1").queryForInt().get());
            } finally {
                TransactionSynchronizationManager.unbindResource(firstKey);
                TransactionSynchronizationManager.unbindResource(aliasKey);
            }
        }
    }

    private static final class ActiveHolder extends ConnectionHolder {
        ActiveHolder(final Connection connection) {
            super(connection);
            setTransactionActive(true);
        }
    }

    private static AbstractQuery<?, ?> query(final JdbcDataSource ds, final String family, final boolean connection, final String sql) throws SQLException {
        return switch (family) {
            case "named" -> connection ? JdbcUtil.prepareNamedQuery(bound(ds), sql) : JdbcUtil.prepareNamedQuery(ds, sql);
            case "callable" -> connection ? JdbcUtil.prepareCallableQuery(bound(ds), sql) : JdbcUtil.prepareCallableQuery(ds, sql);
            default -> connection ? JdbcUtil.prepareQuery(bound(ds), sql) : JdbcUtil.prepareQuery(ds, sql);
        };
    }

    private static Connection bound(final JdbcDataSource ds) {
        return ((ConnectionHolder) TransactionSynchronizationManager.getResource(ds)).getConnection();
    }

    private static DataSourceTransactionManager tracked(final JdbcDataSource ds) {
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
        SpringTransactionTracker.register(manager);
        return manager;
    }

    private static JdbcDataSource dataSource() {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:query_owner_" + UUID.randomUUID());
        return ds;
    }

    private static void initialize(final Connection connection) throws SQLException {
        raw(connection, "CREATE TABLE item(id INT PRIMARY KEY)");
    }

    private static void raw(final Connection connection, final String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
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
