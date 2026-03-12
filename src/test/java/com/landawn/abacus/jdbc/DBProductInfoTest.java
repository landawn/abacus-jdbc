package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class DBProductInfoTest extends TestBase {

    @Test
    public void testConstructor() {
        String productName = "MySQL";
        String productVersion = "8.0.33";
        DBVersion version = DBVersion.MySQL_8;

        DBProductInfo dbInfo = new DBProductInfo(productName, productVersion, version);

        assertEquals(productName, dbInfo.productName());
        assertEquals(productVersion, dbInfo.productVersion());
        assertEquals(version, dbInfo.version());
    }

    @Test
    public void testNullValues() {
        DBProductInfo dbInfo = new DBProductInfo(null, null, null);

        assertNull(dbInfo.productName());
        assertNull(dbInfo.productVersion());
        assertNull(dbInfo.version());
    }

    @Test
    public void testRecordComponents() {
        DBProductInfo dbInfo = new DBProductInfo("PostgreSQL", "15.3", DBVersion.PostgreSQL_OTHERS);

        assertEquals("PostgreSQL", dbInfo.productName());
        assertEquals("15.3", dbInfo.productVersion());
        assertEquals(DBVersion.PostgreSQL_OTHERS, dbInfo.version());
    }

    @Test
    public void testEqualsAndHashCode() {
        DBProductInfo dbInfo1 = new DBProductInfo("Oracle", "19c", DBVersion.Oracle);
        DBProductInfo dbInfo2 = new DBProductInfo("Oracle", "19c", DBVersion.Oracle);
        DBProductInfo dbInfo3 = new DBProductInfo("MySQL", "8.0", DBVersion.MySQL_8);

        assertEquals(dbInfo1, dbInfo2);
        assertNotEquals(dbInfo1, dbInfo3);
        assertEquals(dbInfo1.hashCode(), dbInfo2.hashCode());
        assertNotEquals(dbInfo1.hashCode(), dbInfo3.hashCode());
    }

    @Test
    public void testEqualsDetectsDifferentComponents() {
        DBProductInfo mysqlInfo = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);

        assertNotEquals(mysqlInfo, new DBProductInfo("PostgreSQL", "8.0.33", DBVersion.MySQL_8));
        assertNotEquals(mysqlInfo, new DBProductInfo("MySQL", "8.0.34", DBVersion.MySQL_8));
        assertNotEquals(mysqlInfo, new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_9));
        assertNotEquals(null, mysqlInfo);
        assertEquals(mysqlInfo, mysqlInfo);
    }

    @Test
    public void testToString() {
        DBProductInfo dbInfo = new DBProductInfo("MySQL", "8.0.33", DBVersion.MySQL_8);
        String toString = dbInfo.toString();

        assertNotNull(toString);
        assertTrue(toString.contains("MySQL"));
        assertTrue(toString.contains("8.0.33"));
        assertTrue(toString.contains("MySQL_8"));
    }

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
    }
}
