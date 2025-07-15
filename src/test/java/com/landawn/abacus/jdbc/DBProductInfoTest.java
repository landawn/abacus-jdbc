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
        DBVersion version = DBVersion.MYSQL_8;
        
        DBProductInfo dbInfo = new DBProductInfo(productName, productVersion, version);
        
        assertEquals(productName, dbInfo.productName());
        assertEquals(productVersion, dbInfo.productVersion());
        assertEquals(version, dbInfo.version());
    }

    @Test
    public void testNoArgsConstructor() {
        DBProductInfo dbInfo = new DBProductInfo(null, null, null);
        
        assertNull(dbInfo.productName());
        assertNull(dbInfo.productVersion());
        assertNull(dbInfo.version());
    }

    @Test
    public void testRecordComponents() {
        DBProductInfo dbInfo = new DBProductInfo("PostgreSQL", "15.3", DBVersion.POSTGRESQL_OTHERS);
        
        assertEquals("PostgreSQL", dbInfo.productName());
        assertEquals("15.3", dbInfo.productVersion());
        assertEquals(DBVersion.POSTGRESQL_OTHERS, dbInfo.version());
    }

    @Test
    public void testEqualsAndHashCode() {
        DBProductInfo dbInfo1 = new DBProductInfo("Oracle", "19c", DBVersion.ORACLE);
        DBProductInfo dbInfo2 = new DBProductInfo("Oracle", "19c", DBVersion.ORACLE);
        DBProductInfo dbInfo3 = new DBProductInfo("MySQL", "8.0", DBVersion.MYSQL_8);
        
        assertEquals(dbInfo1, dbInfo2);
        assertNotEquals(dbInfo1, dbInfo3);
        assertEquals(dbInfo1.hashCode(), dbInfo2.hashCode());
        assertNotEquals(dbInfo1.hashCode(), dbInfo3.hashCode());
    }

    @Test
    public void testToString() {
        DBProductInfo dbInfo = new DBProductInfo("H2", "2.1.214", DBVersion.H2);
        String toString = dbInfo.toString();
        
        assertNotNull(toString);
        assertTrue(toString.contains("H2"));
        assertTrue(toString.contains("2.1.214"));
        assertTrue(toString.contains("H2"));
    }

    @Test
    public void testNullValues() {
        DBProductInfo dbInfo = new DBProductInfo(null, null, null);
        
        assertNull(dbInfo.productName());
        assertNull(dbInfo.productVersion());
        assertNull(dbInfo.version());
    }
}