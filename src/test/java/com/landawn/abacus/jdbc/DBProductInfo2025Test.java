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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

/**
 * Comprehensive unit tests for DBProductInfo record.
 * Tests all public methods including record accessors and inherited Object methods.
 */
@Tag("2025")
public class DBProductInfo2025Test extends TestBase {

    // Test constructor
    @Test
    public void testConstructor() {
        DBProductInfo info = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        assertNotNull(info);
    }

    @Test
    public void testConstructorWithNullValues() {
        DBProductInfo info = new DBProductInfo(null, null, null);
        assertNull(info.productName());
        assertNull(info.productVersion());
        assertNull(info.version());
    }

    // Test productName() accessor
    @Test
    public void testProductName() {
        DBProductInfo info = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        assertEquals("MySQL", info.productName());
    }

    @Test
    public void testProductNamePostgreSQL() {
        DBProductInfo info = new DBProductInfo("PostgreSQL", "15.3", DBVersion.PostgreSQL_12);
        assertEquals("PostgreSQL", info.productName());
    }

    @Test
    public void testProductNameH2() {
        DBProductInfo info = new DBProductInfo("H2", "2.1.214", DBVersion.H2);
        assertEquals("H2", info.productName());
    }

    // Test productVersion() accessor
    @Test
    public void testProductVersion() {
        DBProductInfo info = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        assertEquals("8.0.33", info.productVersion());
    }

    @Test
    public void testProductVersionPostgreSQL() {
        DBProductInfo info = new DBProductInfo("PostgreSQL", "15.3", DBVersion.PostgreSQL_12);
        assertEquals("15.3", info.productVersion());
    }

    // Test version() accessor
    @Test
    public void testVersion() {
        DBProductInfo info = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        assertEquals(DBVersion.MySQL_8, info.version());
    }

    @Test
    public void testVersionPostgreSQL() {
        DBProductInfo info = new DBProductInfo("PostgreSQL", "15.3", DBVersion.PostgreSQL_12);
        assertEquals(DBVersion.PostgreSQL_12, info.version());
    }

    @Test
    public void testVersionH2() {
        DBProductInfo info = new DBProductInfo("H2", "2.1.214", DBVersion.H2);
        assertEquals(DBVersion.H2, info.version());
    }

    // Test equals() method
    @Test
    public void testEquals() {
        DBProductInfo info1 = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        DBProductInfo info2 = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        assertEquals(info1, info2);
    }

    @Test
    public void testEqualsNotEqualProductName() {
        DBProductInfo info1 = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        DBProductInfo info2 = new DBProductInfo("PostgreSQL", "8.0.33", DBVersion.MySQL_8);
        assertNotEquals(info1, info2);
    }

    @Test
    public void testEqualsNotEqualProductVersion() {
        DBProductInfo info1 = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        DBProductInfo info2 = new DBProductInfo("MySQL", "8.0.34", DBVersion.MySQL_8);
        assertNotEquals(info1, info2);
    }

    @Test
    public void testEqualsNotEqualVersion() {
        DBProductInfo info1 = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        DBProductInfo info2 = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_9);
        assertNotEquals(info1, info2);
    }

    @Test
    public void testEqualsSameObject() {
        DBProductInfo info = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        assertEquals(info, info);
    }

    @Test
    public void testEqualsNull() {
        DBProductInfo info = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        assertNotEquals(null, info);
    }

    // Test hashCode() method
    @Test
    public void testHashCode() {
        DBProductInfo info1 = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        DBProductInfo info2 = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        assertEquals(info1.hashCode(), info2.hashCode());
    }

    @Test
    public void testHashCodeDifferentObjects() {
        DBProductInfo info1 = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        DBProductInfo info2 = new DBProductInfo("PostgreSQL", "15.3", DBVersion.PostgreSQL_12);
        // Different objects may have different hash codes (not guaranteed, but likely)
        assertNotNull(info1.hashCode());
        assertNotNull(info2.hashCode());
    }

    // Test toString() method
    @Test
    public void testToString() {
        DBProductInfo info = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        String str = info.toString();
        assertNotNull(str);
        assertTrue(str.contains("MySQL"));
        assertTrue(str.contains("8.0.33"));
        assertTrue(str.contains("MySQL_8"));
    }

    @Test
    public void testToStringPostgreSQL() {
        DBProductInfo info = new DBProductInfo("PostgreSQL", "15.3", DBVersion.PostgreSQL_12);
        String str = info.toString();
        assertNotNull(str);
        assertTrue(str.contains("PostgreSQL"));
        assertTrue(str.contains("15.3"));
    }

    // Test record canonical constructor with various database types
    @Test
    public void testVariousDatabaseTypes() {
        DBProductInfo mysqlInfo = new DBProductInfo("MySQL", "5.7.42", DBVersion.MySQL_5_7);
        assertEquals("MySQL", mysqlInfo.productName());
        assertEquals("5.7.42", mysqlInfo.productVersion());
        assertEquals(DBVersion.MySQL_5_7, mysqlInfo.version());

        DBProductInfo oracleInfo = new DBProductInfo("Oracle", "19.3.0", DBVersion.Oracle);
        assertEquals("Oracle", oracleInfo.productName());
        assertEquals("19.3.0", oracleInfo.productVersion());
        assertEquals(DBVersion.Oracle, oracleInfo.version());

        DBProductInfo sqlServerInfo = new DBProductInfo("Microsoft SQL Server", "2019", DBVersion.SQLServer);
        assertEquals("Microsoft SQL Server", sqlServerInfo.productName());
        assertEquals("2019", sqlServerInfo.productVersion());
        assertEquals(DBVersion.SQLServer, sqlServerInfo.version());

        DBProductInfo db2Info = new DBProductInfo("DB2", "11.5", DBVersion.DB2);
        assertEquals("DB2", db2Info.productName());
        assertEquals("11.5", db2Info.productVersion());
        assertEquals(DBVersion.DB2, db2Info.version());

        DBProductInfo hsqldbInfo = new DBProductInfo("HSQLDB", "2.7.1", DBVersion.HSQLDB);
        assertEquals("HSQLDB", hsqldbInfo.productName());
        assertEquals("2.7.1", hsqldbInfo.productVersion());
        assertEquals(DBVersion.HSQLDB, hsqldbInfo.version());
    }
}
