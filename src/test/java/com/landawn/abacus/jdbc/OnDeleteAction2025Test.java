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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

/**
 * Comprehensive unit tests for OnDeleteAction enum.
 * Tests all public methods including value(), get(String), and inherited enum methods.
 */
@Tag("2025")
@SuppressWarnings("deprecation")
public class OnDeleteAction2025Test extends TestBase {

    // Test value() method for each enum constant
    @Test
    public void testValueNoAction() {
        assertEquals(0, OnDeleteAction.NO_ACTION.value());
    }

    @Test
    public void testValueSetNull() {
        assertEquals(1, OnDeleteAction.SET_NULL.value());
    }

    @Test
    public void testValueCascade() {
        assertEquals(2, OnDeleteAction.CASCADE.value());
    }

    // Test get(String) method - case insensitive
    @Test
    public void testGetNoAction() {
        assertEquals(OnDeleteAction.NO_ACTION, OnDeleteAction.get("noAction"));
        assertEquals(OnDeleteAction.NO_ACTION, OnDeleteAction.get("NOACTION"));
        assertEquals(OnDeleteAction.NO_ACTION, OnDeleteAction.get("NoAction"));
    }

    @Test
    public void testGetSetNull() {
        assertEquals(OnDeleteAction.SET_NULL, OnDeleteAction.get("setNull"));
        assertEquals(OnDeleteAction.SET_NULL, OnDeleteAction.get("SETNULL"));
        assertEquals(OnDeleteAction.SET_NULL, OnDeleteAction.get("SetNull"));
    }

    @Test
    public void testGetCascade() {
        assertEquals(OnDeleteAction.CASCADE, OnDeleteAction.get("cascade"));
        assertEquals(OnDeleteAction.CASCADE, OnDeleteAction.get("CASCADE"));
        assertEquals(OnDeleteAction.CASCADE, OnDeleteAction.get("Cascade"));
    }

    @Test
    public void testGetInvalid() {
        assertThrows(IllegalArgumentException.class, () -> OnDeleteAction.get("invalid"));
        assertThrows(IllegalArgumentException.class, () -> OnDeleteAction.get(""));
        assertThrows(IllegalArgumentException.class, () -> OnDeleteAction.get("delete"));
    }

    // Test inherited enum methods
    @Test
    public void testValues() {
        OnDeleteAction[] values = OnDeleteAction.values();
        assertNotNull(values);
        assertEquals(3, values.length);
    }

    @Test
    public void testValueOfString() {
        assertEquals(OnDeleteAction.NO_ACTION, OnDeleteAction.valueOf("NO_ACTION"));
        assertEquals(OnDeleteAction.SET_NULL, OnDeleteAction.valueOf("SET_NULL"));
        assertEquals(OnDeleteAction.CASCADE, OnDeleteAction.valueOf("CASCADE"));
    }

    @Test
    public void testName() {
        assertEquals("NO_ACTION", OnDeleteAction.NO_ACTION.name());
        assertEquals("SET_NULL", OnDeleteAction.SET_NULL.name());
        assertEquals("CASCADE", OnDeleteAction.CASCADE.name());
    }

    @Test
    public void testOrdinal() {
        assertEquals(0, OnDeleteAction.NO_ACTION.ordinal());
        assertEquals(1, OnDeleteAction.SET_NULL.ordinal());
        assertEquals(2, OnDeleteAction.CASCADE.ordinal());
    }

    @Test
    public void testToString() {
        assertEquals("NO_ACTION", OnDeleteAction.NO_ACTION.toString());
        assertEquals("SET_NULL", OnDeleteAction.SET_NULL.toString());
        assertEquals("CASCADE", OnDeleteAction.CASCADE.toString());
    }

    @Test
    public void testCompareTo() {
        assertTrue(OnDeleteAction.NO_ACTION.compareTo(OnDeleteAction.SET_NULL) < 0);
        assertTrue(OnDeleteAction.CASCADE.compareTo(OnDeleteAction.NO_ACTION) > 0);
        assertEquals(0, OnDeleteAction.SET_NULL.compareTo(OnDeleteAction.SET_NULL));
    }

    @Test
    public void testEquals() {
        assertEquals(OnDeleteAction.NO_ACTION, OnDeleteAction.valueOf("NO_ACTION"));
        assertEquals(OnDeleteAction.CASCADE, OnDeleteAction.get("cascade"));
    }

    @Test
    public void testHashCode() {
        assertEquals(OnDeleteAction.NO_ACTION.hashCode(), OnDeleteAction.valueOf("NO_ACTION").hashCode());
    }

    @Test
    public void testDeclaringClass() {
        assertEquals(OnDeleteAction.class, OnDeleteAction.NO_ACTION.getDeclaringClass());
    }

    // Round-trip tests
    @Test
    public void testRoundTripName() {
        for (OnDeleteAction action : OnDeleteAction.values()) {
            assertEquals(action, OnDeleteAction.valueOf(action.name()));
        }
    }
}
