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

import java.sql.Connection;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

/**
 * Comprehensive unit tests for IsolationLevel enum.
 * Tests all public methods including intValue(), valueOf(int), and inherited enum methods.
 */
@Tag("2025")
public class IsolationLevel2025Test extends TestBase {

    // Test intValue() method for each enum constant
    @Test
    public void testIntValueDefault() {
        assertEquals(-1, IsolationLevel.DEFAULT.intValue());
    }

    @Test
    public void testIntValueNone() {
        assertEquals(Connection.TRANSACTION_NONE, IsolationLevel.NONE.intValue());
    }

    @Test
    public void testIntValueReadUncommitted() {
        assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, IsolationLevel.READ_UNCOMMITTED.intValue());
    }

    @Test
    public void testIntValueReadCommitted() {
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, IsolationLevel.READ_COMMITTED.intValue());
    }

    @Test
    public void testIntValueRepeatableRead() {
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, IsolationLevel.REPEATABLE_READ.intValue());
    }

    @Test
    public void testIntValueSerializable() {
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, IsolationLevel.SERIALIZABLE.intValue());
    }

    // Test valueOf(int) method for each enum constant
    @Test
    public void testValueOfIntDefault() {
        assertEquals(IsolationLevel.DEFAULT, IsolationLevel.valueOf(-1));
    }

    @Test
    public void testValueOfIntNone() {
        assertEquals(IsolationLevel.NONE, IsolationLevel.valueOf(Connection.TRANSACTION_NONE));
    }

    @Test
    public void testValueOfIntReadUncommitted() {
        assertEquals(IsolationLevel.READ_UNCOMMITTED, IsolationLevel.valueOf(Connection.TRANSACTION_READ_UNCOMMITTED));
    }

    @Test
    public void testValueOfIntReadCommitted() {
        assertEquals(IsolationLevel.READ_COMMITTED, IsolationLevel.valueOf(Connection.TRANSACTION_READ_COMMITTED));
    }

    @Test
    public void testValueOfIntRepeatableRead() {
        assertEquals(IsolationLevel.REPEATABLE_READ, IsolationLevel.valueOf(Connection.TRANSACTION_REPEATABLE_READ));
    }

    @Test
    public void testValueOfIntSerializable() {
        assertEquals(IsolationLevel.SERIALIZABLE, IsolationLevel.valueOf(Connection.TRANSACTION_SERIALIZABLE));
    }

    @Test
    public void testValueOfIntInvalid() {
        assertThrows(IllegalArgumentException.class, () -> IsolationLevel.valueOf(999));
        assertThrows(IllegalArgumentException.class, () -> IsolationLevel.valueOf(-999));
        assertThrows(IllegalArgumentException.class, () -> IsolationLevel.valueOf(100));
    }

    // Test inherited enum methods
    @Test
    public void testValues() {
        IsolationLevel[] values = IsolationLevel.values();
        assertNotNull(values);
        assertEquals(6, values.length);
    }

    @Test
    public void testValueOfString() {
        assertEquals(IsolationLevel.DEFAULT, IsolationLevel.valueOf("DEFAULT"));
        assertEquals(IsolationLevel.NONE, IsolationLevel.valueOf("NONE"));
        assertEquals(IsolationLevel.READ_UNCOMMITTED, IsolationLevel.valueOf("READ_UNCOMMITTED"));
        assertEquals(IsolationLevel.READ_COMMITTED, IsolationLevel.valueOf("READ_COMMITTED"));
        assertEquals(IsolationLevel.REPEATABLE_READ, IsolationLevel.valueOf("REPEATABLE_READ"));
        assertEquals(IsolationLevel.SERIALIZABLE, IsolationLevel.valueOf("SERIALIZABLE"));
    }

    @Test
    public void testName() {
        assertEquals("DEFAULT", IsolationLevel.DEFAULT.name());
        assertEquals("NONE", IsolationLevel.NONE.name());
        assertEquals("READ_UNCOMMITTED", IsolationLevel.READ_UNCOMMITTED.name());
        assertEquals("READ_COMMITTED", IsolationLevel.READ_COMMITTED.name());
        assertEquals("REPEATABLE_READ", IsolationLevel.REPEATABLE_READ.name());
        assertEquals("SERIALIZABLE", IsolationLevel.SERIALIZABLE.name());
    }

    @Test
    public void testOrdinal() {
        assertEquals(0, IsolationLevel.DEFAULT.ordinal());
        assertEquals(1, IsolationLevel.NONE.ordinal());
        assertEquals(2, IsolationLevel.READ_UNCOMMITTED.ordinal());
        assertEquals(3, IsolationLevel.READ_COMMITTED.ordinal());
        assertEquals(4, IsolationLevel.REPEATABLE_READ.ordinal());
        assertEquals(5, IsolationLevel.SERIALIZABLE.ordinal());
    }

    @Test
    public void testToString() {
        assertEquals("DEFAULT", IsolationLevel.DEFAULT.toString());
        assertEquals("NONE", IsolationLevel.NONE.toString());
        assertEquals("READ_UNCOMMITTED", IsolationLevel.READ_UNCOMMITTED.toString());
        assertEquals("READ_COMMITTED", IsolationLevel.READ_COMMITTED.toString());
        assertEquals("REPEATABLE_READ", IsolationLevel.REPEATABLE_READ.toString());
        assertEquals("SERIALIZABLE", IsolationLevel.SERIALIZABLE.toString());
    }

    @Test
    public void testCompareTo() {
        assertTrue(IsolationLevel.DEFAULT.compareTo(IsolationLevel.NONE) < 0);
        assertTrue(IsolationLevel.SERIALIZABLE.compareTo(IsolationLevel.DEFAULT) > 0);
        assertEquals(0, IsolationLevel.READ_COMMITTED.compareTo(IsolationLevel.READ_COMMITTED));
    }

    @Test
    public void testEquals() {
        assertEquals(IsolationLevel.DEFAULT, IsolationLevel.valueOf("DEFAULT"));
        assertEquals(IsolationLevel.READ_COMMITTED, IsolationLevel.valueOf(Connection.TRANSACTION_READ_COMMITTED));
    }

    @Test
    public void testHashCode() {
        assertEquals(IsolationLevel.DEFAULT.hashCode(), IsolationLevel.valueOf("DEFAULT").hashCode());
    }

    @Test
    public void testDeclaringClass() {
        assertEquals(IsolationLevel.class, IsolationLevel.DEFAULT.getDeclaringClass());
    }

    // Round-trip tests
    @Test
    public void testRoundTripIntValue() {
        for (IsolationLevel level : IsolationLevel.values()) {
            assertEquals(level, IsolationLevel.valueOf(level.intValue()));
        }
    }

    @Test
    public void testRoundTripName() {
        for (IsolationLevel level : IsolationLevel.values()) {
            assertEquals(level, IsolationLevel.valueOf(level.name()));
        }
    }
}
