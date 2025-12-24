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
 * Comprehensive unit tests for OP enum.
 * Tests all public methods including inherited enum methods.
 */
@Tag("2025")
@SuppressWarnings("deprecation")
public class OP2025Test extends TestBase {

    // Test values() method
    @Test
    public void testValues() {
        OP[] values = OP.values();
        assertNotNull(values);
        assertEquals(15, values.length);
    }

    // Test valueOf(String) method
    @Test
    public void testValueOfString() {
        assertEquals(OP.exists, OP.valueOf("exists"));
        assertEquals(OP.findOnlyOne, OP.valueOf("findOnlyOne"));
        assertEquals(OP.findFirst, OP.valueOf("findFirst"));
        assertEquals(OP.list, OP.valueOf("list"));
        assertEquals(OP.query, OP.valueOf("query"));
        assertEquals(OP.stream, OP.valueOf("stream"));
        assertEquals(OP.queryForSingle, OP.valueOf("queryForSingle"));
        assertEquals(OP.queryForUnique, OP.valueOf("queryForUnique"));
        assertEquals(OP.listAll, OP.valueOf("listAll"));
        assertEquals(OP.queryAll, OP.valueOf("queryAll"));
        assertEquals(OP.streamAll, OP.valueOf("streamAll"));
        assertEquals(OP.executeAndGetOutParameters, OP.valueOf("executeAndGetOutParameters"));
        assertEquals(OP.update, OP.valueOf("update"));
        assertEquals(OP.largeUpdate, OP.valueOf("largeUpdate"));
        assertEquals(OP.DEFAULT, OP.valueOf("DEFAULT"));
    }

    @Test
    public void testValueOfStringInvalid() {
        assertThrows(IllegalArgumentException.class, () -> OP.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> OP.valueOf("EXISTS"));
        assertThrows(IllegalArgumentException.class, () -> OP.valueOf(""));
    }

    // Test name() method
    @Test
    public void testName() {
        assertEquals("exists", OP.exists.name());
        assertEquals("findOnlyOne", OP.findOnlyOne.name());
        assertEquals("findFirst", OP.findFirst.name());
        assertEquals("list", OP.list.name());
        assertEquals("query", OP.query.name());
        assertEquals("stream", OP.stream.name());
        assertEquals("queryForSingle", OP.queryForSingle.name());
        assertEquals("queryForUnique", OP.queryForUnique.name());
        assertEquals("listAll", OP.listAll.name());
        assertEquals("queryAll", OP.queryAll.name());
        assertEquals("streamAll", OP.streamAll.name());
        assertEquals("executeAndGetOutParameters", OP.executeAndGetOutParameters.name());
        assertEquals("update", OP.update.name());
        assertEquals("largeUpdate", OP.largeUpdate.name());
        assertEquals("DEFAULT", OP.DEFAULT.name());
    }

    // Test ordinal() method
    @Test
    public void testOrdinal() {
        assertEquals(0, OP.exists.ordinal());
        assertEquals(1, OP.findOnlyOne.ordinal());
        assertEquals(2, OP.findFirst.ordinal());
        assertEquals(3, OP.list.ordinal());
        assertEquals(4, OP.query.ordinal());
        assertEquals(5, OP.stream.ordinal());
        assertEquals(6, OP.queryForSingle.ordinal());
        assertEquals(7, OP.queryForUnique.ordinal());
        assertEquals(8, OP.listAll.ordinal());
        assertEquals(9, OP.queryAll.ordinal());
        assertEquals(10, OP.streamAll.ordinal());
        assertEquals(11, OP.executeAndGetOutParameters.ordinal());
        assertEquals(12, OP.update.ordinal());
        assertEquals(13, OP.largeUpdate.ordinal());
        assertEquals(14, OP.DEFAULT.ordinal());
    }

    // Test toString() method
    @Test
    public void testToString() {
        assertEquals("exists", OP.exists.toString());
        assertEquals("list", OP.list.toString());
        assertEquals("update", OP.update.toString());
        assertEquals("DEFAULT", OP.DEFAULT.toString());
    }

    // Test compareTo() method
    @Test
    public void testCompareTo() {
        assertTrue(OP.exists.compareTo(OP.findOnlyOne) < 0);
        assertTrue(OP.DEFAULT.compareTo(OP.exists) > 0);
        assertEquals(0, OP.list.compareTo(OP.list));
    }

    // Test equals() method
    @Test
    public void testEquals() {
        assertEquals(OP.exists, OP.valueOf("exists"));
        assertEquals(OP.list, OP.valueOf("list"));
        assertEquals(OP.DEFAULT, OP.valueOf("DEFAULT"));
    }

    // Test hashCode() method
    @Test
    public void testHashCode() {
        assertEquals(OP.exists.hashCode(), OP.valueOf("exists").hashCode());
        assertEquals(OP.DEFAULT.hashCode(), OP.valueOf("DEFAULT").hashCode());
    }

    // Test getDeclaringClass() method
    @Test
    public void testDeclaringClass() {
        assertEquals(OP.class, OP.exists.getDeclaringClass());
        assertEquals(OP.class, OP.DEFAULT.getDeclaringClass());
    }

    // Round-trip tests
    @Test
    public void testRoundTripName() {
        for (OP op : OP.values()) {
            assertEquals(op, OP.valueOf(op.name()));
        }
    }

    // Test all enum constants exist
    @Test
    public void testAllEnumConstantsExist() {
        assertNotNull(OP.exists);
        assertNotNull(OP.findOnlyOne);
        assertNotNull(OP.findFirst);
        assertNotNull(OP.list);
        assertNotNull(OP.query);
        assertNotNull(OP.stream);
        assertNotNull(OP.queryForSingle);
        assertNotNull(OP.queryForUnique);
        assertNotNull(OP.listAll);
        assertNotNull(OP.queryAll);
        assertNotNull(OP.streamAll);
        assertNotNull(OP.executeAndGetOutParameters);
        assertNotNull(OP.update);
        assertNotNull(OP.largeUpdate);
        assertNotNull(OP.DEFAULT);
    }
}
