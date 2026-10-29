package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

@Tag("2025")
class DBLockPerformanceTest {
    @Test
    void acquisitionAndContentionUseOneConnectionPerAttempt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.resetCounts();
            final String code = fixture.lock.tryLock("new-resource", 60_000L, 0L);
            assertNotNull(code);
            assertEquals(1, fixture.connections.get());
            assertEquals(2, fixture.prepares.get());
            assertEquals(2, fixture.updates.get());
            assertEquals(1, fixture.connectionCloses.get());
            assertEquals(0, fixture.activeConnections.get());
            assertEquals(21, fixture.rowCount(), "The insertion must already be committed on another connection");

            fixture.resetCounts();
            assertNull(fixture.lock.tryLock("new-resource", 60_000L, 0L));
            assertEquals(1, fixture.connections.get());
            assertEquals(2, fixture.prepares.get());
            assertEquals(2, fixture.updates.get());
            assertEquals(1, fixture.connectionCloses.get());
            assertEquals(0, fixture.activeConnections.get());
            assertEquals(21, fixture.rowCount());
            assertTrue(fixture.lock.unlock("new-resource", code), "A failed contending attempt must preserve the original ownership");
        }
    }

    @Test
    void cleanupFailureDiscardsConnectionBeforeIndependentInsertion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.resetCounts();
            fixture.failNextUpdate.set(true);
            assertNotNull(fixture.lock.tryLock("cleanup-failure", 60_000L, 0L));
            assertEquals(2, fixture.connections.get(), "The INSERT must obtain a fresh connection after cleanup fails");
            assertEquals(2, fixture.prepares.get());
            assertEquals(2, fixture.updates.get());
            assertEquals(2, fixture.connectionCloses.get());
            assertEquals(0, fixture.activeConnections.get());
            assertEquals(21, fixture.rowCount());
        }
    }

    @Test
    void cleanupConnectionFailureStillAllowsIndependentInsertion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.resetCounts();
            fixture.failNextConnection.set(true);
            assertNotNull(fixture.lock.tryLock("cleanup-connection-failure", 60_000L, 0L));
            assertEquals(2, fixture.connections.get());
            assertEquals(1, fixture.prepares.get());
            assertEquals(1, fixture.updates.get());
            assertEquals(1, fixture.connectionCloses.get());
            assertEquals(0, fixture.activeConnections.get());
            assertEquals(21, fixture.rowCount());
        }
    }

    @Test
    void acquisitionClosesConnectionWhenCleanupRaisesError() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.resetCounts();
            fixture.failNextStatementClose.set(true);
            assertThrows(AssertionError.class, () -> fixture.lock.tryLock("cleanup-error", 60_000L, 0L));
            assertEquals(1, fixture.connections.get());
            assertEquals(1, fixture.updates.get(), "An Error must propagate without attempting the INSERT");
            assertEquals(1, fixture.connectionCloses.get());
            assertEquals(0, fixture.activeConnections.get());
            assertEquals(20, fixture.rowCount());
        }
    }

    @Test
    void acquisitionReleasesConnectionBeforeRetrySleepAndPreservesInterruption() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.resetCounts();
            fixture.connectionClosed = new CountDownLatch(1);
            final AtomicReference<String> result = new AtomicReference<>();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final AtomicBoolean interrupted = new AtomicBoolean();
            final Thread contender = new Thread(() -> {
                try {
                    result.set(fixture.lock.tryLock("resource-0", 60_000L, 30_000L, 30_000L));
                    interrupted.set(Thread.currentThread().isInterrupted());
                } catch (final Throwable e) {
                    failure.set(e);
                }
            }, "dblock-retry-resource-release");
            contender.start();

            try {
                assertTrue(fixture.connectionClosed.await(5, TimeUnit.SECONDS), "The first failed attempt must release its connection");
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (contender.isAlive() && contender.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }

                assertEquals(Thread.State.TIMED_WAITING, contender.getState(), "The contender should be sleeping between attempts");
                assertEquals(1, fixture.connections.get());
                assertEquals(1, fixture.connectionCloses.get());
                assertEquals(0, fixture.activeConnections.get(), "Retry sleep must not retain a connection");
            } finally {
                contender.interrupt();
                contender.join(5_000L);
            }

            assertFalse(contender.isAlive());
            assertNull(failure.get());
            assertNull(result.get());
            assertTrue(interrupted.get(), "Cancellation must preserve the interrupt flag");
            assertEquals(0, fixture.activeConnections.get());
        }
    }

    @Test
    void acquisitionStillReclaimsEitherExpiredOrStaleRows() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final String expiredCode = fixture.lock.tryLock("expired-resource", 60_000L, 0L);
            final String staleCode = fixture.lock.tryLock("stale-resource", 60_000L, 0L);
            assertNotNull(expiredCode);
            assertNotNull(staleCode);
            JdbcUtil.executeUpdate(fixture.observer,
                    "UPDATE maintenance_locks SET expiry_time = TIMESTAMP '2000-01-01 00:00:00' WHERE target = 'expired-resource'");
            JdbcUtil.executeUpdate(fixture.observer,
                    "UPDATE maintenance_locks SET update_time = TIMESTAMP '2000-01-01 00:00:00' WHERE target = 'stale-resource'");

            fixture.resetCounts();
            final String renewedExpiredCode = fixture.lock.tryLock("expired-resource", 60_000L, 0L);
            final String renewedStaleCode = fixture.lock.tryLock("stale-resource", 60_000L, 0L);
            assertNotNull(renewedExpiredCode);
            assertNotNull(renewedStaleCode);
            assertEquals(2, fixture.connections.get());
            assertEquals(2, fixture.connectionCloses.get());
            assertEquals(0, fixture.activeConnections.get());
            assertFalse(fixture.lock.unlock("expired-resource", expiredCode));
            assertFalse(fixture.lock.unlock("stale-resource", staleCode));
            assertTrue(fixture.lock.unlock("expired-resource", renewedExpiredCode));
            assertTrue(fixture.lock.unlock("stale-resource", renewedStaleCode));
        }
    }

    @Test
    void refreshAndCloseReuseOneConnectionAndStatement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.resetCounts();
            fixture.lock.refreshLocks();
            assertEquals(1, fixture.connections.get());
            assertEquals(1, fixture.prepares.get());
            assertEquals(20, fixture.updates.get());
            assertEquals(20, fixture.rowCount());

            fixture.resetCounts();
            fixture.lock.close();
            assertEquals(1, fixture.connections.get());
            assertEquals(1, fixture.prepares.get());
            assertEquals(20, fixture.updates.get());
            assertEquals(0, fixture.rowCount());
            fixture.lock.close();
            assertEquals(1, fixture.connections.get());
        }
    }

    @Test
    void refreshFailureReopensResourcesAndContinuesLaterLocks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JdbcUtil.executeUpdate(fixture.observer, "UPDATE maintenance_locks SET expiry_time = TIMESTAMP '2000-01-01 00:00:00'");
            fixture.resetCounts();
            fixture.failNextUpdate.set(true);
            fixture.lock.refreshLocks();
            assertEquals(2, fixture.connections.get());
            assertEquals(2, fixture.prepares.get());
            assertEquals(20, fixture.updates.get());
            assertEquals(19,
                    JdbcUtil.prepareQuery(fixture.observer, "SELECT COUNT(*) FROM maintenance_locks WHERE expiry_time > CURRENT_TIMESTAMP")
                            .queryForInt()
                            .orElse(-1));

            // The failed entry remains locally owned and can be renewed on the next cycle.
            fixture.lock.refreshLocks();
            assertEquals(20,
                    JdbcUtil.prepareQuery(fixture.observer, "SELECT COUNT(*) FROM maintenance_locks WHERE expiry_time > CURRENT_TIMESTAMP")
                            .queryForInt()
                            .orElse(-1));
        }
    }

    @Test
    void closeFailureStillAttemptsEveryLaterLock() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.resetCounts();
            fixture.failNextUpdate.set(true);
            fixture.lock.close();
            assertEquals(2, fixture.connections.get());
            assertEquals(2, fixture.prepares.get());
            assertEquals(20, fixture.updates.get());
            assertEquals(1, fixture.rowCount());
        }
    }

    @Test
    void unavailableConnectionDoesNotRetryForEveryHeldLockInOneRefresh() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.resetCounts();
            fixture.failNextConnection.set(true);
            fixture.lock.refreshLocks();
            assertEquals(1, fixture.connections.get());
            assertEquals(0, fixture.prepares.get());
            assertEquals(0, fixture.updates.get());
            assertEquals(20, fixture.rowCount());
        }
    }

    @Test
    void unavailableConnectionDoesNotRetryForEveryHeldLockInClose() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.resetCounts();
            fixture.failNextConnection.set(true);
            fixture.lock.close();
            assertEquals(1, fixture.connections.get());
            assertEquals(0, fixture.prepares.get());
            assertEquals(0, fixture.updates.get());
            // The rows that were not released stay until their leases expire.
            assertEquals(20, fixture.rowCount());
        }
    }

    @Test
    void statementCleanupErrorStillClosesConnectionAndPreservesExecutionFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.resetCounts();
            fixture.failNextUpdate.set(true);
            fixture.failNextStatementClose.set(true);
            fixture.lock.refreshLocks();
            assertEquals(20, fixture.updates.get());
            assertEquals(2, fixture.connections.get());
            assertEquals(2, fixture.connectionCloses.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger prepares = new AtomicInteger();
        private final AtomicInteger updates = new AtomicInteger();
        private final AtomicInteger connectionCloses = new AtomicInteger();
        private final AtomicInteger activeConnections = new AtomicInteger();
        private final AtomicBoolean failNextUpdate = new AtomicBoolean();
        private final AtomicBoolean failNextConnection = new AtomicBoolean();
        private final AtomicBoolean failNextStatementClose = new AtomicBoolean();
        private final Connection observer;
        private final DBLock lock;
        private volatile CountDownLatch connectionClosed;

        private Fixture() throws Exception {
            final JdbcDataSource database = new JdbcDataSource();
            database.setURL("jdbc:h2:mem:lock_maintenance_" + System.nanoTime());
            observer = database.getConnection();
            final DataSource observed = mock(DataSource.class);
            when(observed.getConnection()).thenAnswer(call -> {
                connections.incrementAndGet();
                if (failNextConnection.compareAndSet(true, false)) {
                    throw new SQLException("injected acquisition failure");
                }
                final Connection connection = database.getConnection();
                activeConnections.incrementAndGet();
                final AtomicBoolean closed = new AtomicBoolean();
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                    try {
                        if (method.getName().equals("close")) {
                            connectionCloses.incrementAndGet();
                            try {
                                return method.invoke(connection, args);
                            } finally {
                                if (closed.compareAndSet(false, true)) {
                                    activeConnections.decrementAndGet();
                                }
                                final CountDownLatch signal = connectionClosed;
                                if (signal != null) {
                                    signal.countDown();
                                }
                            }
                        }
                        final Object result = method.invoke(connection, args);
                        if (method.getName().equals("prepareStatement")) {
                            prepares.incrementAndGet();
                            final PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] { PreparedStatement.class },
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        try {
                                            if (statementMethod.getName().equals("executeUpdate")) {
                                                updates.incrementAndGet();
                                                if (failNextUpdate.compareAndSet(true, false)) {
                                                    throw new SQLException("injected maintenance failure");
                                                }
                                            }
                                            final Object value = statementMethod.invoke(statement, statementArgs);
                                            if (statementMethod.getName().equals("close") && failNextStatementClose.compareAndSet(true, false)) {
                                                throw new AssertionError("injected statement close error");
                                            }
                                            return value;
                                        } catch (final InvocationTargetException failure) {
                                            throw failure.getCause();
                                        }
                                    });
                        }
                        return result;
                    } catch (final InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            });
            lock = new DBLock(observed, "maintenance_locks");
            final Field scheduled = DBLock.class.getDeclaredField("scheduledFuture");
            scheduled.setAccessible(true);
            ((ScheduledFuture<?>) scheduled.get(lock)).cancel(false);
            for (int i = 0; i < 20; i++) {
                assertNotNull(lock.tryLock("resource-" + i, 60_000L, 0L));
            }
        }

        private void resetCounts() {
            connections.set(0);
            prepares.set(0);
            updates.set(0);
            connectionCloses.set(0);
        }

        private int rowCount() throws SQLException {
            return JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM maintenance_locks").queryForInt().orElse(-1);
        }

        @Override
        public void close() throws SQLException {
            try {
                lock.close();
            } finally {
                observer.close();
            }
        }
    }
}
