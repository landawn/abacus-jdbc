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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

/**
 * Comprehensive unit tests for DBLock class.
 * Tests public constants and static fields.
 * Note: Full functional testing requires a database connection.
 */
@Tag("2025")
public class DBLock2025Test extends TestBase {

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

    @Test
    public void testDefaultLockLiveTimeConstant() {
        assertEquals(3 * 60 * 1000, DBLock.DEFAULT_LOCK_LIVE_TIME);
        assertEquals(180000, DBLock.DEFAULT_LOCK_LIVE_TIME);
    }

    @Test
    public void testDefaultTimeoutConstant() {
        assertEquals(3 * 1000, DBLock.DEFAULT_TIMEOUT);
        assertEquals(3000, DBLock.DEFAULT_TIMEOUT);
    }

    // Test scheduled executor is properly initialized
    @Test
    public void testScheduledExecutorNotNull() {
        assertNotNull(DBLock.scheduledExecutor);
    }

    // Test constant values are appropriate
    @Test
    public void testDefaultLockLiveTimeIsThreeMinutes() {
        // 3 minutes in milliseconds
        int threeMinutes = 3 * 60 * 1000;
        assertEquals(threeMinutes, DBLock.DEFAULT_LOCK_LIVE_TIME);
    }

    @Test
    public void testDefaultTimeoutIsThreeSeconds() {
        // 3 seconds in milliseconds
        int threeSeconds = 3 * 1000;
        assertEquals(threeSeconds, DBLock.DEFAULT_TIMEOUT);
    }

    // Test locked/unlocked status strings
    @Test
    public void testStatusStringsAreLowercase() {
        assertEquals("locked", DBLock.LOCKED.toLowerCase());
        assertEquals("unlocked", DBLock.UNLOCKED.toLowerCase());
    }

    @Test
    public void testStatusStringsAreDistinct() {
        assertNotNull(DBLock.LOCKED);
        assertNotNull(DBLock.UNLOCKED);
        // They should be different
        assertEquals(false, DBLock.LOCKED.equals(DBLock.UNLOCKED));
    }
}
