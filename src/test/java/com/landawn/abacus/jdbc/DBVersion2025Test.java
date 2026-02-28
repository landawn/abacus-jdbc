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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

/**
 * Comprehensive unit tests for DBVersion enum.
 * Tests all public methods including isMySQL(), isPostgreSQL(), and inherited enum methods.
 */
@Tag("2025")
public class DBVersion2025Test extends TestBase {

    // Test isMySQL() method
    @Test
    public void testIsMySQLForMySQLVersions() {
        assertTrue(DBVersion.MySQL_5_5.isMySQL());
        assertTrue(DBVersion.MySQL_5_6.isMySQL());
        assertTrue(DBVersion.MySQL_5_7.isMySQL());
        assertTrue(DBVersion.MySQL_5_8.isMySQL());
        assertTrue(DBVersion.MySQL_5_9.isMySQL());
        assertTrue(DBVersion.MySQL_6.isMySQL());
        assertTrue(DBVersion.MySQL_7.isMySQL());
        assertTrue(DBVersion.MySQL_8.isMySQL());
        assertTrue(DBVersion.MySQL_9.isMySQL());
        assertTrue(DBVersion.MySQL_10.isMySQL());
        assertTrue(DBVersion.MySQL_OTHERS.isMySQL());
    }

    @Test
    public void testIsMySQLForNonMySQLVersions() {
        assertFalse(DBVersion.H2.isMySQL());
        assertFalse(DBVersion.HSQLDB.isMySQL());
        assertFalse(DBVersion.MariaDB.isMySQL());
        assertFalse(DBVersion.PostgreSQL_9_2.isMySQL());
        assertFalse(DBVersion.PostgreSQL_12.isMySQL());
        assertFalse(DBVersion.PostgreSQL_OTHERS.isMySQL());
        assertFalse(DBVersion.Oracle.isMySQL());
        assertFalse(DBVersion.DB2.isMySQL());
        assertFalse(DBVersion.SQLServer.isMySQL());
        assertFalse(DBVersion.OTHERS.isMySQL());
    }

    // Test isPostgreSQL() method
    @Test
    public void testIsPostgreSQLForPostgreSQLVersions() {
        assertTrue(DBVersion.PostgreSQL_9_2.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_9_3.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_9_4.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_9_5.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_9_6.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_10.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_11.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_12.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_OTHERS.isPostgreSQL());
    }

    @Test
    public void testIsPostgreSQLForNonPostgreSQLVersions() {
        assertFalse(DBVersion.H2.isPostgreSQL());
        assertFalse(DBVersion.HSQLDB.isPostgreSQL());
        assertFalse(DBVersion.MySQL_5_7.isPostgreSQL());
        assertFalse(DBVersion.MySQL_8.isPostgreSQL());
        assertFalse(DBVersion.MySQL_OTHERS.isPostgreSQL());
        assertFalse(DBVersion.MariaDB.isPostgreSQL());
        assertFalse(DBVersion.Oracle.isPostgreSQL());
        assertFalse(DBVersion.DB2.isPostgreSQL());
        assertFalse(DBVersion.SQLServer.isPostgreSQL());
        assertFalse(DBVersion.OTHERS.isPostgreSQL());
    }

    // Test inherited enum methods
    @Test
    public void testValues() {
        DBVersion[] values = DBVersion.values();
        assertNotNull(values);
        assertEquals(27, values.length);
    }

    @Test
    public void testValueOfString() {
        assertEquals(DBVersion.H2, DBVersion.valueOf("H2"));
        assertEquals(DBVersion.HSQLDB, DBVersion.valueOf("HSQLDB"));
        assertEquals(DBVersion.MySQL_5_7, DBVersion.valueOf("MySQL_5_7"));
        assertEquals(DBVersion.MySQL_8, DBVersion.valueOf("MySQL_8"));
        assertEquals(DBVersion.PostgreSQL_12, DBVersion.valueOf("PostgreSQL_12"));
        assertEquals(DBVersion.Oracle, DBVersion.valueOf("Oracle"));
        assertEquals(DBVersion.DB2, DBVersion.valueOf("DB2"));
        assertEquals(DBVersion.SQLServer, DBVersion.valueOf("SQLServer"));
        assertEquals(DBVersion.OTHERS, DBVersion.valueOf("OTHERS"));
    }

    @Test
    public void testName() {
        assertEquals("H2", DBVersion.H2.name());
        assertEquals("HSQLDB", DBVersion.HSQLDB.name());
        assertEquals("MySQL_5_7", DBVersion.MySQL_5_7.name());
        assertEquals("MySQL_8", DBVersion.MySQL_8.name());
        assertEquals("PostgreSQL_12", DBVersion.PostgreSQL_12.name());
        assertEquals("Oracle", DBVersion.Oracle.name());
        assertEquals("DB2", DBVersion.DB2.name());
        assertEquals("SQLServer", DBVersion.SQLServer.name());
        assertEquals("OTHERS", DBVersion.OTHERS.name());
    }

    @Test
    public void testOrdinal() {
        assertEquals(0, DBVersion.H2.ordinal());
        assertEquals(1, DBVersion.HSQLDB.ordinal());
    }

    @Test
    public void testToString() {
        assertEquals("H2", DBVersion.H2.toString());
        assertEquals("MySQL_8", DBVersion.MySQL_8.toString());
        assertEquals("PostgreSQL_12", DBVersion.PostgreSQL_12.toString());
    }

    @Test
    public void testCompareTo() {
        assertTrue(DBVersion.H2.compareTo(DBVersion.HSQLDB) < 0);
        assertTrue(DBVersion.OTHERS.compareTo(DBVersion.H2) > 0);
        assertEquals(0, DBVersion.Oracle.compareTo(DBVersion.Oracle));
    }

    @Test
    public void testEquals() {
        assertEquals(DBVersion.H2, DBVersion.valueOf("H2"));
        assertEquals(DBVersion.MySQL_8, DBVersion.valueOf("MySQL_8"));
    }

    @Test
    public void testHashCode() {
        assertEquals(DBVersion.H2.hashCode(), DBVersion.valueOf("H2").hashCode());
    }

    @Test
    public void testDeclaringClass() {
        assertEquals(DBVersion.class, DBVersion.H2.getDeclaringClass());
    }

    // Round-trip tests
    @Test
    public void testRoundTripName() {
        for (DBVersion version : DBVersion.values()) {
            assertEquals(version, DBVersion.valueOf(version.name()));
        }
    }

    // Test all enum constants exist
    @Test
    public void testAllEnumConstantsExist() {
        assertNotNull(DBVersion.H2);
        assertNotNull(DBVersion.HSQLDB);
        assertNotNull(DBVersion.MySQL_5_5);
        assertNotNull(DBVersion.MySQL_5_6);
        assertNotNull(DBVersion.MySQL_5_7);
        assertNotNull(DBVersion.MySQL_5_8);
        assertNotNull(DBVersion.MySQL_5_9);
        assertNotNull(DBVersion.MySQL_6);
        assertNotNull(DBVersion.MySQL_7);
        assertNotNull(DBVersion.MySQL_8);
        assertNotNull(DBVersion.MySQL_9);
        assertNotNull(DBVersion.MySQL_10);
        assertNotNull(DBVersion.MySQL_OTHERS);
        assertNotNull(DBVersion.MariaDB);
        assertNotNull(DBVersion.PostgreSQL_9_2);
        assertNotNull(DBVersion.PostgreSQL_9_3);
        assertNotNull(DBVersion.PostgreSQL_9_4);
        assertNotNull(DBVersion.PostgreSQL_9_5);
        assertNotNull(DBVersion.PostgreSQL_9_6);
        assertNotNull(DBVersion.PostgreSQL_10);
        assertNotNull(DBVersion.PostgreSQL_11);
        assertNotNull(DBVersion.PostgreSQL_12);
        assertNotNull(DBVersion.PostgreSQL_OTHERS);
        assertNotNull(DBVersion.Oracle);
        assertNotNull(DBVersion.DB2);
        assertNotNull(DBVersion.SQLServer);
        assertNotNull(DBVersion.OTHERS);
    }
}
