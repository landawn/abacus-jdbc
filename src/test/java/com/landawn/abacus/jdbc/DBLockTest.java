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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

import sun.misc.Unsafe;

@Tag("2025")
public class DBLockTest extends TestBase {

    private static final String REMOVE_SQL = "DELETE FROM test_lock WHERE target = ?";
    private static final String LOCK_SQL = "INSERT INTO test_lock(target, code) VALUES (?, ?)";
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
    public void testUnlock() throws Exception {
        final LockFixture fixture = newLockFixture(0, 1, 1);
        final String code = fixture.lock.lock("resource-5", 200L, 50L, 1L);

        final boolean unlocked = fixture.lock.unlock("resource-5", code);

        assertTrue(unlocked);
        assertEquals(0, targetCodePool(fixture.lock).size());
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

    // Test scheduled executor is properly initialized
    @Test
    public void testScheduledExecutorNotNull() {
        assertNotNull(DBLock.scheduledExecutor);
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

    private static Unsafe unsafe() throws Exception {
        final Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
