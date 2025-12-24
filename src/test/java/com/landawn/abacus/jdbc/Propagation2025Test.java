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
 * Comprehensive unit tests for Propagation enum.
 * Tests all public methods including inherited enum methods.
 */
@Tag("2025")
public class Propagation2025Test extends TestBase {

    // Test values() method
    @Test
    public void testValues() {
        Propagation[] values = Propagation.values();
        assertNotNull(values);
        assertEquals(6, values.length);
    }

    // Test valueOf(String) method
    @Test
    public void testValueOfString() {
        assertEquals(Propagation.REQUIRED, Propagation.valueOf("REQUIRED"));
        assertEquals(Propagation.SUPPORTS, Propagation.valueOf("SUPPORTS"));
        assertEquals(Propagation.MANDATORY, Propagation.valueOf("MANDATORY"));
        assertEquals(Propagation.REQUIRES_NEW, Propagation.valueOf("REQUIRES_NEW"));
        assertEquals(Propagation.NOT_SUPPORTED, Propagation.valueOf("NOT_SUPPORTED"));
        assertEquals(Propagation.NEVER, Propagation.valueOf("NEVER"));
    }

    @Test
    public void testValueOfStringInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Propagation.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> Propagation.valueOf("required"));
        assertThrows(IllegalArgumentException.class, () -> Propagation.valueOf(""));
    }

    // Test name() method
    @Test
    public void testName() {
        assertEquals("REQUIRED", Propagation.REQUIRED.name());
        assertEquals("SUPPORTS", Propagation.SUPPORTS.name());
        assertEquals("MANDATORY", Propagation.MANDATORY.name());
        assertEquals("REQUIRES_NEW", Propagation.REQUIRES_NEW.name());
        assertEquals("NOT_SUPPORTED", Propagation.NOT_SUPPORTED.name());
        assertEquals("NEVER", Propagation.NEVER.name());
    }

    // Test ordinal() method
    @Test
    public void testOrdinal() {
        assertEquals(0, Propagation.REQUIRED.ordinal());
        assertEquals(1, Propagation.SUPPORTS.ordinal());
        assertEquals(2, Propagation.MANDATORY.ordinal());
        assertEquals(3, Propagation.REQUIRES_NEW.ordinal());
        assertEquals(4, Propagation.NOT_SUPPORTED.ordinal());
        assertEquals(5, Propagation.NEVER.ordinal());
    }

    // Test toString() method
    @Test
    public void testToString() {
        assertEquals("REQUIRED", Propagation.REQUIRED.toString());
        assertEquals("SUPPORTS", Propagation.SUPPORTS.toString());
        assertEquals("MANDATORY", Propagation.MANDATORY.toString());
        assertEquals("REQUIRES_NEW", Propagation.REQUIRES_NEW.toString());
        assertEquals("NOT_SUPPORTED", Propagation.NOT_SUPPORTED.toString());
        assertEquals("NEVER", Propagation.NEVER.toString());
    }

    // Test compareTo() method
    @Test
    public void testCompareTo() {
        assertTrue(Propagation.REQUIRED.compareTo(Propagation.SUPPORTS) < 0);
        assertTrue(Propagation.NEVER.compareTo(Propagation.REQUIRED) > 0);
        assertEquals(0, Propagation.MANDATORY.compareTo(Propagation.MANDATORY));
    }

    // Test equals() method
    @Test
    public void testEquals() {
        assertEquals(Propagation.REQUIRED, Propagation.valueOf("REQUIRED"));
        assertEquals(Propagation.SUPPORTS, Propagation.valueOf("SUPPORTS"));
        assertEquals(Propagation.MANDATORY, Propagation.valueOf("MANDATORY"));
    }

    // Test hashCode() method
    @Test
    public void testHashCode() {
        assertEquals(Propagation.REQUIRED.hashCode(), Propagation.valueOf("REQUIRED").hashCode());
        assertEquals(Propagation.NEVER.hashCode(), Propagation.valueOf("NEVER").hashCode());
    }

    // Test getDeclaringClass() method
    @Test
    public void testDeclaringClass() {
        assertEquals(Propagation.class, Propagation.REQUIRED.getDeclaringClass());
        assertEquals(Propagation.class, Propagation.NEVER.getDeclaringClass());
    }

    // Round-trip tests
    @Test
    public void testRoundTripName() {
        for (Propagation propagation : Propagation.values()) {
            assertEquals(propagation, Propagation.valueOf(propagation.name()));
        }
    }

    // Test all enum constants exist
    @Test
    public void testAllEnumConstantsExist() {
        assertNotNull(Propagation.REQUIRED);
        assertNotNull(Propagation.SUPPORTS);
        assertNotNull(Propagation.MANDATORY);
        assertNotNull(Propagation.REQUIRES_NEW);
        assertNotNull(Propagation.NOT_SUPPORTED);
        assertNotNull(Propagation.NEVER);
    }
}
