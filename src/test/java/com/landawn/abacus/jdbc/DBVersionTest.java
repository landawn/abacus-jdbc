package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class DBVersionTest extends TestBase {

    @Test
    public void testIsMySQL() {
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
        
        assertFalse(DBVersion.H2.isMySQL());
        assertFalse(DBVersion.HSQLDB.isMySQL());
        assertFalse(DBVersion.PostgreSQL_9_2.isMySQL());
        assertFalse(DBVersion.Oracle.isMySQL());
        assertFalse(DBVersion.DB2.isMySQL());
        assertFalse(DBVersion.SQL_Server.isMySQL());
        assertFalse(DBVersion.OTHERS.isMySQL());
    }

    @Test
    public void testIsPostgreSQL() {
        assertTrue(DBVersion.PostgreSQL_9_2.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_9_3.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_9_4.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_9_5.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_9_6.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_10.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_11.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_12.isPostgreSQL());
        assertTrue(DBVersion.PostgreSQL_OTHERS.isPostgreSQL());
        
        assertFalse(DBVersion.H2.isPostgreSQL());
        assertFalse(DBVersion.HSQLDB.isPostgreSQL());
        assertFalse(DBVersion.MySQL_8.isPostgreSQL());
        assertFalse(DBVersion.Oracle.isPostgreSQL());
        assertFalse(DBVersion.DB2.isPostgreSQL());
        assertFalse(DBVersion.SQL_Server.isPostgreSQL());
        assertFalse(DBVersion.OTHERS.isPostgreSQL());
    }

    @Test
    public void testEnumValues() {
        DBVersion[] values = DBVersion.values();
        assertTrue(values.length > 0);
        
        // Test some specific values exist
        assertTrue(contains(values, DBVersion.H2));
        assertTrue(contains(values, DBVersion.HSQLDB));
        assertTrue(contains(values, DBVersion.MySQL_8));
        assertTrue(contains(values, DBVersion.PostgreSQL_12));
        assertTrue(contains(values, DBVersion.Oracle));
        assertTrue(contains(values, DBVersion.OTHERS));
    }

    @Test
    public void testEnumName() {
        assertEquals("H2", DBVersion.H2.name());
        assertEquals("HSQLDB", DBVersion.HSQLDB.name());
        assertEquals("MySQL_8", DBVersion.MySQL_8.name());
        assertEquals("PostgreSQL_12", DBVersion.PostgreSQL_12.name());
        assertEquals("Oracle", DBVersion.Oracle.name());
        assertEquals("DB2", DBVersion.DB2.name());
        assertEquals("SQL_Server", DBVersion.SQL_Server.name());
        assertEquals("OTHERS", DBVersion.OTHERS.name());
    }

    @Test
    public void testValueOf() {
        assertEquals(DBVersion.H2, DBVersion.valueOf("H2"));
        assertEquals(DBVersion.MySQL_8, DBVersion.valueOf("MySQL_8"));
        assertEquals(DBVersion.PostgreSQL_12, DBVersion.valueOf("PostgreSQL_12"));
        assertEquals(DBVersion.Oracle, DBVersion.valueOf("Oracle"));
        
        assertThrows(IllegalArgumentException.class, () -> DBVersion.valueOf("INVALID_DB"));
    }

    @Test
    public void testToString() {
        assertEquals("H2", DBVersion.H2.toString());
        assertEquals("MySQL_8", DBVersion.MySQL_8.toString());
        assertEquals("PostgreSQL_12", DBVersion.PostgreSQL_12.toString());
    }

    private boolean contains(DBVersion[] values, DBVersion version) {
        for (DBVersion value : values) {
            if (value == version) {
                return true;
            }
        }
        return false;
    }
}