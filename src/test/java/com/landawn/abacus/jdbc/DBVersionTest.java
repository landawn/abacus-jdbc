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
        assertTrue(DBVersion.MYSQL_5_5.isMySQL());
        assertTrue(DBVersion.MYSQL_5_6.isMySQL());
        assertTrue(DBVersion.MYSQL_5_7.isMySQL());
        assertTrue(DBVersion.MYSQL_5_8.isMySQL());
        assertTrue(DBVersion.MYSQL_5_9.isMySQL());
        assertTrue(DBVersion.MYSQL_6.isMySQL());
        assertTrue(DBVersion.MYSQL_7.isMySQL());
        assertTrue(DBVersion.MYSQL_8.isMySQL());
        assertTrue(DBVersion.MYSQL_9.isMySQL());
        assertTrue(DBVersion.MYSQL_10.isMySQL());
        assertTrue(DBVersion.MYSQL_OTHERS.isMySQL());
        
        assertFalse(DBVersion.H2.isMySQL());
        assertFalse(DBVersion.HSQLDB.isMySQL());
        assertFalse(DBVersion.POSTGRESQL_9_2.isMySQL());
        assertFalse(DBVersion.ORACLE.isMySQL());
        assertFalse(DBVersion.DB2.isMySQL());
        assertFalse(DBVersion.SQL_SERVER.isMySQL());
        assertFalse(DBVersion.OTHERS.isMySQL());
    }

    @Test
    public void testIsPostgreSQL() {
        assertTrue(DBVersion.POSTGRESQL_9_2.isPostgreSQL());
        assertTrue(DBVersion.POSTGRESQL_9_3.isPostgreSQL());
        assertTrue(DBVersion.POSTGRESQL_9_4.isPostgreSQL());
        assertTrue(DBVersion.POSTGRESQL_9_5.isPostgreSQL());
        assertTrue(DBVersion.POSTGRESQL_9_6.isPostgreSQL());
        assertTrue(DBVersion.POSTGRESQL_10.isPostgreSQL());
        assertTrue(DBVersion.POSTGRESQL_11.isPostgreSQL());
        assertTrue(DBVersion.POSTGRESQL_12.isPostgreSQL());
        assertTrue(DBVersion.POSTGRESQL_OTHERS.isPostgreSQL());
        
        assertFalse(DBVersion.H2.isPostgreSQL());
        assertFalse(DBVersion.HSQLDB.isPostgreSQL());
        assertFalse(DBVersion.MYSQL_8.isPostgreSQL());
        assertFalse(DBVersion.ORACLE.isPostgreSQL());
        assertFalse(DBVersion.DB2.isPostgreSQL());
        assertFalse(DBVersion.SQL_SERVER.isPostgreSQL());
        assertFalse(DBVersion.OTHERS.isPostgreSQL());
    }

    @Test
    public void testEnumValues() {
        DBVersion[] values = DBVersion.values();
        assertTrue(values.length > 0);
        
        // Test some specific values exist
        assertTrue(contains(values, DBVersion.H2));
        assertTrue(contains(values, DBVersion.HSQLDB));
        assertTrue(contains(values, DBVersion.MYSQL_8));
        assertTrue(contains(values, DBVersion.POSTGRESQL_12));
        assertTrue(contains(values, DBVersion.ORACLE));
        assertTrue(contains(values, DBVersion.OTHERS));
    }

    @Test
    public void testEnumName() {
        assertEquals("H2", DBVersion.H2.name());
        assertEquals("HSQLDB", DBVersion.HSQLDB.name());
        assertEquals("MYSQL_8", DBVersion.MYSQL_8.name());
        assertEquals("POSTGRESQL_12", DBVersion.POSTGRESQL_12.name());
        assertEquals("ORACLE", DBVersion.ORACLE.name());
        assertEquals("DB2", DBVersion.DB2.name());
        assertEquals("SQL_SERVER", DBVersion.SQL_SERVER.name());
        assertEquals("OTHERS", DBVersion.OTHERS.name());
    }

    @Test
    public void testValueOf() {
        assertEquals(DBVersion.H2, DBVersion.valueOf("H2"));
        assertEquals(DBVersion.MYSQL_8, DBVersion.valueOf("MYSQL_8"));
        assertEquals(DBVersion.POSTGRESQL_12, DBVersion.valueOf("POSTGRESQL_12"));
        assertEquals(DBVersion.ORACLE, DBVersion.valueOf("ORACLE"));
        
        assertThrows(IllegalArgumentException.class, () -> DBVersion.valueOf("INVALID_DB"));
    }

    @Test
    public void testToString() {
        assertEquals("H2", DBVersion.H2.toString());
        assertEquals("MYSQL_8", DBVersion.MYSQL_8.toString());
        assertEquals("POSTGRESQL_12", DBVersion.POSTGRESQL_12.toString());
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