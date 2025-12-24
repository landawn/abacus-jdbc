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

import java.sql.ResultSet;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

/**
 * Comprehensive unit tests for FetchDirection enum.
 * Tests all public methods including intValue(), valueOf(int), and inherited enum methods.
 */
@Tag("2025")
public class FetchDirection2025Test extends TestBase {

    // Test intValue() method for each enum constant
    @Test
    public void testIntValueForward() {
        assertEquals(ResultSet.FETCH_FORWARD, FetchDirection.FORWARD.intValue());
    }

    @Test
    public void testIntValueReverse() {
        assertEquals(ResultSet.FETCH_REVERSE, FetchDirection.REVERSE.intValue());
    }

    @Test
    public void testIntValueUnknown() {
        assertEquals(ResultSet.FETCH_UNKNOWN, FetchDirection.UNKNOWN.intValue());
    }

    // Test valueOf(int) method for each enum constant
    @Test
    public void testValueOfIntForward() {
        assertEquals(FetchDirection.FORWARD, FetchDirection.valueOf(ResultSet.FETCH_FORWARD));
    }

    @Test
    public void testValueOfIntReverse() {
        assertEquals(FetchDirection.REVERSE, FetchDirection.valueOf(ResultSet.FETCH_REVERSE));
    }

    @Test
    public void testValueOfIntUnknown() {
        assertEquals(FetchDirection.UNKNOWN, FetchDirection.valueOf(ResultSet.FETCH_UNKNOWN));
    }

    @Test
    public void testValueOfIntInvalid() {
        assertThrows(IllegalArgumentException.class, () -> FetchDirection.valueOf(999));
        assertThrows(IllegalArgumentException.class, () -> FetchDirection.valueOf(-999));
        assertThrows(IllegalArgumentException.class, () -> FetchDirection.valueOf(100));
    }

    // Test inherited enum methods
    @Test
    public void testValues() {
        FetchDirection[] values = FetchDirection.values();
        assertNotNull(values);
        assertEquals(3, values.length);
    }

    @Test
    public void testValueOfString() {
        assertEquals(FetchDirection.FORWARD, FetchDirection.valueOf("FORWARD"));
        assertEquals(FetchDirection.REVERSE, FetchDirection.valueOf("REVERSE"));
        assertEquals(FetchDirection.UNKNOWN, FetchDirection.valueOf("UNKNOWN"));
    }

    @Test
    public void testName() {
        assertEquals("FORWARD", FetchDirection.FORWARD.name());
        assertEquals("REVERSE", FetchDirection.REVERSE.name());
        assertEquals("UNKNOWN", FetchDirection.UNKNOWN.name());
    }

    @Test
    public void testOrdinal() {
        assertEquals(0, FetchDirection.FORWARD.ordinal());
        assertEquals(1, FetchDirection.REVERSE.ordinal());
        assertEquals(2, FetchDirection.UNKNOWN.ordinal());
    }

    @Test
    public void testToString() {
        assertEquals("FORWARD", FetchDirection.FORWARD.toString());
        assertEquals("REVERSE", FetchDirection.REVERSE.toString());
        assertEquals("UNKNOWN", FetchDirection.UNKNOWN.toString());
    }

    @Test
    public void testCompareTo() {
        assertTrue(FetchDirection.FORWARD.compareTo(FetchDirection.REVERSE) < 0);
        assertTrue(FetchDirection.UNKNOWN.compareTo(FetchDirection.FORWARD) > 0);
        assertEquals(0, FetchDirection.REVERSE.compareTo(FetchDirection.REVERSE));
    }

    @Test
    public void testEquals() {
        assertEquals(FetchDirection.FORWARD, FetchDirection.valueOf("FORWARD"));
        assertEquals(FetchDirection.REVERSE, FetchDirection.valueOf(ResultSet.FETCH_REVERSE));
    }

    @Test
    public void testHashCode() {
        assertEquals(FetchDirection.FORWARD.hashCode(), FetchDirection.valueOf("FORWARD").hashCode());
    }

    @Test
    public void testDeclaringClass() {
        assertEquals(FetchDirection.class, FetchDirection.FORWARD.getDeclaringClass());
    }

    // Round-trip tests
    @Test
    public void testRoundTripIntValue() {
        for (FetchDirection direction : FetchDirection.values()) {
            assertEquals(direction, FetchDirection.valueOf(direction.intValue()));
        }
    }

    @Test
    public void testRoundTripName() {
        for (FetchDirection direction : FetchDirection.values()) {
            assertEquals(direction, FetchDirection.valueOf(direction.name()));
        }
    }
}
