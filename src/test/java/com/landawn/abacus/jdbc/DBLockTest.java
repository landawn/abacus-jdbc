/*
 * Copyright (c) 2025, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.landawn.abacus.TestBase;

import sun.misc.Unsafe;

@Tag("2025")
public class DBLockTest extends TestBase {

    private static final String REMOVE_SQL = "DELETE FROM test_lock WHERE target = ?";
    private static final String LOCK_SQL = "INSERT INTO test_lock(host_name, target, code, status, expiry_time, update_time, create_time) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String UNLOCK_SQL = "DELETE FROM test_lock WHERE target = ? AND code = ?";

    private static final class LockFixture {
        final DBLock lock;
        final DataSource dataSource;
        final Connection connection;
        final PreparedStatement preparedStatement;
        final ScheduledFuture<?> scheduledFuture;

        LockFixture(final DBLock lock, final DataSource dataSource, final Connection connection, final PreparedStatement preparedStatement,
                final ScheduledFuture<?> scheduledFuture) {
            this.lock = lock;
            this.dataSource = dataSource;
            this.connection = connection;
            this.preparedStatement = preparedStatement;
            this.scheduledFuture = scheduledFuture;
        }
    }

    // Verifies all lock overloads populate the in-memory lock pool after a successful JDBC insert.
    @Test
    public void testLock() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);

        final String code = fixture.lock.lock("resource-1");

        assertNotNull(code);
        assertEquals(1, targetCodePool(fixture.lock).size());
        verify(fixture.connection, times(2)).prepareStatement(anyString());
    }

    @Test
    public void testLock_Timeout() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);

        final String code = fixture.lock.lock("resource-2", 50L);

        assertNotNull(code);
        assertEquals(1, targetCodePool(fixture.lock).size());
    }

    @Test
    public void testLock_LiveTimeAndTimeout() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);

        final String code = fixture.lock.lock("resource-3", 200L, 50L);

        assertNotNull(code);
        assertEquals(1, targetCodePool(fixture.lock).size());
    }

    @Test
    public void testLock_RetryInterval() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);

        final String code = fixture.lock.lock("resource-4", 200L, 50L, 1L);

        assertNotNull(code);
        assertEquals(1, targetCodePool(fixture.lock).size());
    }

    @Test
    public void testLock_TimeoutReturnsNull() throws Exception {
        final LockFixture fixture = newLockFixture(0, 0, 0);

        final String code = fixture.lock.lock("resource-timeout", 200L, 1L, 0L);

        assertNull(code);
        assertEquals(0, targetCodePool(fixture.lock).size());
    }

    @Test
    public void testLock_DoesNotSleepPastTimeoutBudget() throws Exception {
        final LockFixture fixture = newLockFixture(0, 0);

        final long start = System.nanoTime();
        final String code = fixture.lock.lock("resource-timeout-budget", 200L, 20L, 250L);
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertNull(code);
        assertTrue(elapsedMillis < 150L, "lock() slept past timeout budget; elapsedMillis=" + elapsedMillis);
        assertEquals(0, targetCodePool(fixture.lock).size());
    }

    @Test
    public void testLock_LiveTimeOverflowSaturatesExpiryTimestamp() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);

        final String code = fixture.lock.lock("resource-expiry-overflow", Long.MAX_VALUE, 50L, 0L);

        assertNotNull(code);
        final ArgumentCaptor<java.sql.Timestamp> expiryCaptor = ArgumentCaptor.forClass(java.sql.Timestamp.class);
        verify(fixture.preparedStatement, atLeastOnce()).setTimestamp(eq(5), expiryCaptor.capture());
        assertEquals(Long.MAX_VALUE, expiryCaptor.getValue().getTime());
    }

    @Test
    public void testLockRejectsEmptyTarget() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);

        assertThrows(IllegalArgumentException.class, () -> fixture.lock.lock("", 200L, 50L, 1L));
    }

    @Test
    public void testLockRejectsNonPositiveLiveTime() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);

        assertThrows(IllegalArgumentException.class, () -> fixture.lock.lock("resource-live-time", 0L, 50L, 1L));
        assertThrows(IllegalArgumentException.class, () -> fixture.lock.lock("resource-live-time", -1L, 50L, 1L));
    }

    @Test
    public void testLockRejectsNegativeTimeoutAndRetryInterval() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);

        assertThrows(IllegalArgumentException.class, () -> fixture.lock.lock("resource-timeout", 200L, -1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> fixture.lock.lock("resource-retry", 200L, 50L, -1L));
    }

    @Test
    public void testUnlock() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1, 1);
        final String code = fixture.lock.lock("resource-5", 200L, 50L, 1L);

        final boolean unlocked = fixture.lock.unlock("resource-5", code);

        assertTrue(unlocked);
        assertEquals(0, targetCodePool(fixture.lock).size());
    }

    @Test
    public void testUnlockRejectsInvalidArguments() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);

        assertThrows(IllegalArgumentException.class, () -> fixture.lock.unlock("", "code"));
        assertThrows(IllegalArgumentException.class, () -> fixture.lock.unlock("resource-5", ""));
    }

    @Test
    public void testUnlock_WrapsSQLException() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);
        when(fixture.connection.prepareStatement(anyString())).thenThrow(new java.sql.SQLException("broken"));

        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, () -> fixture.lock.unlock("resource-6", "code"));
    }

    @Test
    public void testClose() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1, 1);
        fixture.lock.lock("resource-6");

        fixture.lock.close();

        verify(fixture.scheduledFuture).cancel(true);
        assertEquals(0, targetCodePool(fixture.lock).size());
        assertThrows(IllegalStateException.class, () -> fixture.lock.lock("resource-6"));
    }

    @Test
    public void testSuccessfulLockAcquisitionIsLinearizedWithClose() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1, 1);
        final java.util.concurrent.CountDownLatch insertCompleted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger executeCount = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Object> outcome = new java.util.concurrent.atomic.AtomicReference<>();

        when(fixture.preparedStatement.executeUpdate()).thenAnswer(invocation -> {
            final int call = executeCount.incrementAndGet();

            if (call == 2) {
                insertCompleted.countDown();
            }

            return call == 1 ? 0 : 1;
        });

        final Thread locker = new Thread(() -> {
            try {
                outcome.set(fixture.lock.lock("resource-close-race", 5_000L, 1_000L, 1L));
            } catch (final Throwable e) {
                outcome.set(e);
            }
        }, "dblock-close-race");

        // Holding the same monitor used by close() lets the test deterministically pause a
        // successful INSERT at the acquisition/close linearization point.
        synchronized (fixture.lock) {
            locker.start();
            assertTrue(insertCompleted.await(2, TimeUnit.SECONDS));

            final long deadline = System.currentTimeMillis() + 2_000L;
            while (locker.getState() != Thread.State.BLOCKED && locker.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.yield();
            }

            assertEquals(Thread.State.BLOCKED, locker.getState(), "lock() must synchronize successful publication with close()");
            fixture.lock.close();
        }

        locker.join(2_000L);
        assertTrue(outcome.get() instanceof IllegalStateException);
        assertEquals(0, targetCodePool(fixture.lock).size());
    }

    @Test
    public void testClose_AlreadyClosed() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);
        setField(fixture.lock, "isClosed", true);

        fixture.lock.close();

        verify(fixture.scheduledFuture, never()).cancel(true);
        assertEquals(0, targetCodePool(fixture.lock).size());
    }

    // Test public constants

    @Test
    public void testLockedConstant() {
        assertEquals("locked", DBLock.LOCKED);
        assertNotNull(DBLock.LOCKED);
    }

    @Test
    public void testUnlockedConstant() {
        assertEquals("unlocked", DBLock.UNLOCKED);
        assertNotNull(DBLock.UNLOCKED);
    }

    // Test scheduled executor is properly initialized and usable
    @Test
    public void testScheduledExecutorNotNull() {
        assertNotNull(DBLock.scheduledExecutor);
        assertInstanceOf(ScheduledExecutorService.class, DBLock.scheduledExecutor);
        // The shared executor should not be shut down during normal operation
        assertFalse(DBLock.scheduledExecutor.isShutdown(), "scheduledExecutor should not be shut down");
    }

    // Test constant values are appropriate
    @Test
    public void testDefaultLockLiveTimeIsThreeMinutes() {
        final int threeMinutes = 3 * 60 * 1000;
        assertEquals(threeMinutes, DBLock.DEFAULT_LOCK_LIVE_TIME);
    }

    @Test
    public void testDefaultTimeoutIsThreeSeconds() {
        final int threeSeconds = 3 * 1000;
        assertEquals(threeSeconds, DBLock.DEFAULT_TIMEOUT);
    }

    @Test
    public void testStatusStringsAreLowercase() {
        assertEquals("locked", DBLock.LOCKED.toLowerCase());
        assertEquals("unlocked", DBLock.UNLOCKED.toLowerCase());
    }

    @Test
    public void testStatusStringsAreDistinct() {
        assertNotNull(DBLock.LOCKED);
        assertNotNull(DBLock.UNLOCKED);
        assertEquals(false, DBLock.LOCKED.equals(DBLock.UNLOCKED));
    }

    private static LockFixture newLockFixture(final int... executeUpdateResults) throws Exception {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement preparedStatement = mock(PreparedStatement.class);
        final ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        final DBLock dbLock = (DBLock) unsafe().allocateInstance(DBLock.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        if (executeUpdateResults.length == 0) {
            when(preparedStatement.executeUpdate()).thenReturn(1);
        } else {
            final Integer[] boxed = new Integer[executeUpdateResults.length];

            for (int i = 0; i < executeUpdateResults.length; i++) {
                boxed[i] = executeUpdateResults[i];
            }

            when(preparedStatement.executeUpdate()).thenReturn(boxed[0], java.util.Arrays.copyOfRange(boxed, 1, boxed.length));
        }

        setField(dbLock, "ds", dataSource);
        setField(dbLock, "scheduledFuture", scheduledFuture);
        setField(dbLock, "targetCodePool", new ConcurrentHashMap<>());
        setField(dbLock, "removeExpiredLockSQL", REMOVE_SQL);
        setField(dbLock, "lockSQL", LOCK_SQL);
        setField(dbLock, "unlockSQL", UNLOCK_SQL);
        setField(dbLock, "refreshSQL", "UPDATE test_lock SET expiry_time = ? WHERE target = ? AND code = ?");
        setField(dbLock, "isClosed", false);

        return new LockFixture(dbLock, dataSource, connection, preparedStatement, scheduledFuture);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> targetCodePool(final DBLock dbLock) throws Exception {
        final Field field = DBLock.class.getDeclaredField("targetCodePool");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(dbLock);
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = DBLock.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // removeExpiredLock throws → catch at L450 entered, lock still proceeds (L450)
    @Test
    public void testLock_RemoveExpiredLock_ThrowsException_LockStillSucceeds() throws Exception {
        final LockFixture fixture = newLockFixture(1);
        when(fixture.preparedStatement.executeUpdate()).thenThrow(new RuntimeException("remove expired failed")).thenReturn(1); // lockSQL succeeds on retry
        final String code = fixture.lock.lock("resource-exc-remove", 200L, 100L, 0L);
        assertNotNull(code);
    }

    // lockSQL throws on first attempt then succeeds on retry → L474, L478, L482 (retryInterval > 0)
    @Test
    public void testLock_LockAcquire_ExceptionThenSuccess_WithRetryInterval() throws Exception {
        final LockFixture fixture = newLockFixture(1);
        when(fixture.preparedStatement.executeUpdate()).thenReturn(0) // removeExpiredLock: no expired
                .thenThrow(new RuntimeException("lock failed")) // lockSQL first attempt: throws
                .thenReturn(1); // lockSQL second attempt: succeeds
        final String code = fixture.lock.lock("resource-exc-retry", 200L, 100L, 1L);
        assertNotNull(code);
    }

    // Passing Long.MAX_VALUE as timeout must not overflow endTime to a negative value.
    // Before the fix, now.getTime() + Long.MAX_VALUE overflowed to a large negative number,
    // making the do-while condition false immediately so the lock was never acquired.
    @Test
    public void testLock_MaxValueTimeout_DoesNotOverflow_LockAcquired() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);

        final String code = fixture.lock.lock("resource-maxvalue-timeout", 200L, Long.MAX_VALUE, 0L);

        assertNotNull(code);
        assertEquals(1, targetCodePool(fixture.lock).size());
    }

    // Lock: removeExpiredLockSQL returns > 0, covering L465 info log
    @Test
    public void testLock_ExpiredLockRemoved() throws Exception {
        final LockFixture fixture = newLockFixture(1, 1);

        final String code = fixture.lock.lock("resource-expired", 200L, 50L, 0L);

        assertNotNull(code);
        assertEquals(1, targetCodePool(fixture.lock).size());
    }

    @Test
    public void testLock_RemovesExpiredLockOnRetry() throws Exception {
        final LockFixture fixture = newLockFixture(0, 0, 1, 1);

        final String code = fixture.lock.lock("resource-expired-on-retry", 200L, 100L, 1L);

        assertNotNull(code);
        assertEquals(1, targetCodePool(fixture.lock).size());
        verify(fixture.connection, times(4)).prepareStatement(anyString());
    }

    // Lock: exception during acquire, timeout expires, covering L522-L523
    @Test
    public void testLock_ExceptionThenTimeoutReturnsNull() throws Exception {
        final LockFixture fixture = newLockFixture(0);
        when(fixture.preparedStatement.executeUpdate()).thenReturn(0).thenThrow(new RuntimeException("lock failed")).thenReturn(0);

        final String code = fixture.lock.lock("resource-exc-timeout", 200L, 1L, 0L);

        assertNull(code);
    }

    // Unlock: wrong code — lockInfo exists but code mismatch, covering L576 branch, L580 false, L593
    @Test
    public void testUnlock_WrongCode() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1, 0);
        final String code = fixture.lock.lock("resource-wrong-code", 200L, 50L, 0L);

        assertNotNull(code);
        final boolean unlocked = fixture.lock.unlock("resource-wrong-code", "wrong-code");

        assertFalse(unlocked);
    }

    // Close: scheduledFuture.get() throws exception, covering L644-L645
    @Test
    public void testClose_ScheduledFutureGetThrowsException() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);
        when(fixture.scheduledFuture.get()).thenThrow(new java.util.concurrent.ExecutionException(new RuntimeException("test")));

        fixture.lock.close();

        assertEquals(0, targetCodePool(fixture.lock).size());
    }

    // Close: unlockSQL throws during close loop, covering L654-L656
    @Test
    public void testClose_UnlockThrowsExceptionDuringClose() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);
        fixture.lock.lock("resource-close-exc", 200L, 50L, 0L);
        when(fixture.preparedStatement.executeUpdate()).thenThrow(new RuntimeException("unlock failed during close"));

        fixture.lock.close();

        assertEquals(0, targetCodePool(fixture.lock).size());
    }

    // Close: when scheduledFuture.get() throws InterruptedException, the thread's interrupt
    // flag must be re-set so callers up the stack can detect cancellation. Before the fix the
    // exception was caught by a generic catch(Exception) that silently swallowed it.
    @Test
    public void testClose_ScheduledFutureGetThrowsInterruptedException_PreservesInterruptFlag() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);
        when(fixture.scheduledFuture.get()).thenThrow(new InterruptedException("interrupted during close"));

        // Make sure the flag is clear before calling close().
        assertFalse(Thread.interrupted(), "precondition: interrupt flag must be clear");

        try {
            fixture.lock.close();
            assertTrue(Thread.currentThread().isInterrupted(), "close() must restore interrupt flag when scheduledFuture.get() throws InterruptedException");
        } finally {
            // Always clear the interrupt flag for subsequent tests, regardless of assertion outcome.
            Thread.interrupted();
        }
    }

    // Close with null scheduledFuture, covering the null branch at L640
    @Test
    public void testClose_ScheduledFutureNull() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1);
        setField(fixture.lock, "scheduledFuture", null);

        assertDoesNotThrow(() -> fixture.lock.close());
        assertEquals(0, targetCodePool(fixture.lock).size());
    }

    // Unlock where code matches but DB delete returns 0 rows (shouldRemoveFromLocal=true, unLocked=false)
    // Covers the L586 branch where both operands are evaluated and unLocked is false
    @Test
    public void testUnlock_CodeMatchesButDBAffectReturnsZero() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1, 0);
        final String code = fixture.lock.lock("resource-no-db", 200L, 50L, 0L);

        assertNotNull(code);
        assertEquals(1, targetCodePool(fixture.lock).size());

        final boolean unlocked = fixture.lock.unlock("resource-no-db", code);

        assertFalse(unlocked);
        assertEquals(1, targetCodePool(fixture.lock).size());
    }

    // Interrupt-flag preservation in lock(): an interrupt that arrives while the retry loop is blocked in
    // N.sleep must NOT escape lock() as a RuntimeException. N.sleep restores the interrupt flag and rethrows
    // an UncheckedInterruptedException on interruption; lock() now catches it and routes it through the same
    // clean-cancellation path that returns null with the flag preserved. Before the fix this exception escaped
    // lock() uncaught, so the deliberate Thread.interrupted() handling block was dead code for that (dominant)
    // timing. This is made deterministic by pre-setting the interrupt flag so the first N.sleep throws at once.
    @Test
    public void testLock_InterruptedDuringSleep_ReturnsNullAndPreservesInterruptFlag() throws Exception {
        // removeExpiredLock -> 0, lockSQL -> 0 so the lock is never acquired and the loop reaches N.sleep.
        final LockFixture fixture = newLockFixture(0, 0);

        assertFalse(Thread.interrupted(), "precondition: interrupt flag must be clear");
        Thread.currentThread().interrupt();

        try {
            final String code = fixture.lock.lock("resource-interrupt", 10_000L, 5_000L, 1_000L);

            assertNull(code, "lock() must return null when interrupted during the retry sleep, not throw");
            assertTrue(Thread.currentThread().isInterrupted(), "lock() must preserve the interrupt flag on cancellation");
            assertEquals(0, targetCodePool(fixture.lock).size());
        } finally {
            // Always clear the interrupt flag for subsequent tests, regardless of assertion outcome.
            Thread.interrupted();
        }
    }

    // Live in-memory H2 DB exercises the real constructor (table creation, dead-lock cleanup,
    // refresh-task scheduling — DBLock L188-221) and the scheduled refresh task body
    // (L223-262 normal refresh + L246-254 stale-lock eviction), which the Unsafe-based fixtures
    // above cannot reach because they bypass the constructor.
    @Test
    public void testConstructorAndRefreshTask_LiveDb() throws Exception {
        final DataSource ds = JdbcUtil.createHikariDataSource("jdbc:h2:mem:dblock_live_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        DBLock lock = null;
        try {
            lock = new DBLock(ds, "live_lock_tbl");

            final String code = lock.lock("res-live", 60_000L, 2_000L, 50L);
            assertNotNull(code);
            assertEquals(1, targetCodePool(lock).size());

            // Let the scheduled refresh (1s fixed delay) run and keep the live lock in the pool.
            Thread.sleep(1_500L);
            assertEquals(1, targetCodePool(lock).size());

            // Delete the backing row behind the lock's back: the next refresh sees 0 rows updated
            // and evicts the now-stale entry from the in-memory pool.
            // DBLock quotes the table identifier (case-sensitive lowercase in H2), so quote it here too.
            try (Connection c = ds.getConnection()) {
                JdbcUtil.executeUpdate(c, "DELETE FROM \"live_lock_tbl\" WHERE target = ?", "res-live");
            }

            final long deadline = System.currentTimeMillis() + 6_000L;
            while (targetCodePool(lock).size() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100L);
            }
            assertEquals(0, targetCodePool(lock).size());
        } finally {
            if (lock != null) {
                lock.close();
            }
        }
    }

    // TODO: L207 — tableExists returns false after creation (defensive; createTableIfNotExists +
    //       tableExists make it practically unreachable)
    // TODO: L217-L218 — SQLException in constructor catch (requires a DataSource that connects but
    //       fails the CREATE TABLE / metadata calls)
    // TODO: L263-266 / L270-273 — refresh-task SQLException / generic catch branches (require the
    //       refresh connection to fail mid-cycle)
    // TODO: L516 — attempts >= maxAttempts loop exit (requires impractical number of iterations)

    private static Unsafe unsafe() throws Exception {
        final Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
