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

@Tag("2025")
@SuppressWarnings("deprecation")
public class QueryOperationTest extends TestBase {

    // Test values() method
    @Test
    public void testValues() {
        QueryOperation[] values = QueryOperation.values();
        assertNotNull(values);
        assertEquals(15, values.length);
    }

    // Test valueOf(String) method
    @Test
    public void testValueOfString() {
        assertEquals(QueryOperation.exists, QueryOperation.valueOf("exists"));
        assertEquals(QueryOperation.findOnlyOne, QueryOperation.valueOf("findOnlyOne"));
        assertEquals(QueryOperation.findFirst, QueryOperation.valueOf("findFirst"));
        assertEquals(QueryOperation.list, QueryOperation.valueOf("list"));
        assertEquals(QueryOperation.query, QueryOperation.valueOf("query"));
        assertEquals(QueryOperation.stream, QueryOperation.valueOf("stream"));
        assertEquals(QueryOperation.queryForSingle, QueryOperation.valueOf("queryForSingle"));
        assertEquals(QueryOperation.queryForUnique, QueryOperation.valueOf("queryForUnique"));
        assertEquals(QueryOperation.listAll, QueryOperation.valueOf("listAll"));
        assertEquals(QueryOperation.queryAll, QueryOperation.valueOf("queryAll"));
        assertEquals(QueryOperation.streamAll, QueryOperation.valueOf("streamAll"));
        assertEquals(QueryOperation.executeAndGetOutParameters, QueryOperation.valueOf("executeAndGetOutParameters"));
        assertEquals(QueryOperation.update, QueryOperation.valueOf("update"));
        assertEquals(QueryOperation.largeUpdate, QueryOperation.valueOf("largeUpdate"));
        assertEquals(QueryOperation.DEFAULT, QueryOperation.valueOf("DEFAULT"));
    }

    @Test
    public void testValueOfStringInvalid() {
        assertThrows(IllegalArgumentException.class, () -> QueryOperation.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> QueryOperation.valueOf("EXISTS"));
        assertThrows(IllegalArgumentException.class, () -> QueryOperation.valueOf(""));
    }

    // Test name() method
    @Test
    public void testName() {
        assertEquals("exists", QueryOperation.exists.name());
        assertEquals("findOnlyOne", QueryOperation.findOnlyOne.name());
        assertEquals("findFirst", QueryOperation.findFirst.name());
        assertEquals("list", QueryOperation.list.name());
        assertEquals("query", QueryOperation.query.name());
        assertEquals("stream", QueryOperation.stream.name());
        assertEquals("queryForSingle", QueryOperation.queryForSingle.name());
        assertEquals("queryForUnique", QueryOperation.queryForUnique.name());
        assertEquals("listAll", QueryOperation.listAll.name());
        assertEquals("queryAll", QueryOperation.queryAll.name());
        assertEquals("streamAll", QueryOperation.streamAll.name());
        assertEquals("executeAndGetOutParameters", QueryOperation.executeAndGetOutParameters.name());
        assertEquals("update", QueryOperation.update.name());
        assertEquals("largeUpdate", QueryOperation.largeUpdate.name());
        assertEquals("DEFAULT", QueryOperation.DEFAULT.name());
    }

    // Test ordinal() method
    @Test
    public void testOrdinal() {
        assertEquals(0, QueryOperation.exists.ordinal());
        assertEquals(1, QueryOperation.findOnlyOne.ordinal());
        assertEquals(2, QueryOperation.findFirst.ordinal());
        assertEquals(3, QueryOperation.list.ordinal());
        assertEquals(4, QueryOperation.query.ordinal());
        assertEquals(5, QueryOperation.stream.ordinal());
        assertEquals(6, QueryOperation.queryForSingle.ordinal());
        assertEquals(7, QueryOperation.queryForUnique.ordinal());
        assertEquals(8, QueryOperation.listAll.ordinal());
        assertEquals(9, QueryOperation.queryAll.ordinal());
        assertEquals(10, QueryOperation.streamAll.ordinal());
        assertEquals(11, QueryOperation.executeAndGetOutParameters.ordinal());
        assertEquals(12, QueryOperation.update.ordinal());
        assertEquals(13, QueryOperation.largeUpdate.ordinal());
        assertEquals(14, QueryOperation.DEFAULT.ordinal());
    }

    // Test toString() method
    @Test
    public void testToString() {
        assertEquals("exists", QueryOperation.exists.toString());
        assertEquals("list", QueryOperation.list.toString());
        assertEquals("update", QueryOperation.update.toString());
        assertEquals("DEFAULT", QueryOperation.DEFAULT.toString());
    }

    // Test compareTo() method
    @Test
    public void testCompareTo() {
        assertTrue(QueryOperation.exists.compareTo(QueryOperation.findOnlyOne) < 0);
        assertTrue(QueryOperation.DEFAULT.compareTo(QueryOperation.exists) > 0);
        assertEquals(0, QueryOperation.list.compareTo(QueryOperation.list));
    }

    // Test hashCode() method
    @Test
    public void testHashCode() {
        assertEquals(QueryOperation.exists.hashCode(), QueryOperation.valueOf("exists").hashCode());
        assertEquals(QueryOperation.DEFAULT.hashCode(), QueryOperation.valueOf("DEFAULT").hashCode());
    }

    // Test getDeclaringClass() method
    @Test
    public void testDeclaringClass() {
        assertEquals(QueryOperation.class, QueryOperation.exists.getDeclaringClass());
        assertEquals(QueryOperation.class, QueryOperation.DEFAULT.getDeclaringClass());
    }

    // Round-trip tests
    @Test
    public void testRoundTripName() {
        for (QueryOperation queryOperation : QueryOperation.values()) {
            assertEquals(queryOperation, QueryOperation.valueOf(queryOperation.name()));
        }
    }
}
