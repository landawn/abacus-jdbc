package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class IsolationLevelTest extends TestBase {

    @Test
    public void testDefaultIsolationLevel() {
        assertEquals(-1, IsolationLevel.DEFAULT.intValue());
    }

    @Test
    public void testNoneIsolationLevel() {
        assertEquals(Connection.TRANSACTION_NONE, IsolationLevel.NONE.intValue());
    }

    @Test
    public void testReadUncommittedIsolationLevel() {
        assertEquals(Connection.TRANSACTION_READ_UNCOMMITTED, IsolationLevel.READ_UNCOMMITTED.intValue());
    }

    @Test
    public void testReadCommittedIsolationLevel() {
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, IsolationLevel.READ_COMMITTED.intValue());
    }

    @Test
    public void testRepeatableReadIsolationLevel() {
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, IsolationLevel.REPEATABLE_READ.intValue());
    }

    @Test
    public void testSerializableIsolationLevel() {
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, IsolationLevel.SERIALIZABLE.intValue());
    }

    @Test
    public void testValueOfDefault() {
        assertEquals(IsolationLevel.DEFAULT, IsolationLevel.valueOf(-1));
    }

    @Test
    public void testValueOfNone() {
        assertEquals(IsolationLevel.NONE, IsolationLevel.valueOf(Connection.TRANSACTION_NONE));
    }

    @Test
    public void testValueOfReadUncommitted() {
        assertEquals(IsolationLevel.READ_UNCOMMITTED, IsolationLevel.valueOf(Connection.TRANSACTION_READ_UNCOMMITTED));
    }

    @Test
    public void testValueOfReadCommitted() {
        assertEquals(IsolationLevel.READ_COMMITTED, IsolationLevel.valueOf(Connection.TRANSACTION_READ_COMMITTED));
    }

    @Test
    public void testValueOfRepeatableRead() {
        assertEquals(IsolationLevel.REPEATABLE_READ, IsolationLevel.valueOf(Connection.TRANSACTION_REPEATABLE_READ));
    }

    @Test
    public void testValueOfSerializable() {
        assertEquals(IsolationLevel.SERIALIZABLE, IsolationLevel.valueOf(Connection.TRANSACTION_SERIALIZABLE));
    }

    @Test
    public void testValueOfInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> IsolationLevel.valueOf(999));
        assertThrows(IllegalArgumentException.class, () -> IsolationLevel.valueOf(-999));
    }

    @Test
    public void testAllEnumValues() {
        IsolationLevel[] values = IsolationLevel.values();
        assertEquals(6, values.length);
        
        assertTrue(contains(values, IsolationLevel.DEFAULT));
        assertTrue(contains(values, IsolationLevel.NONE));
        assertTrue(contains(values, IsolationLevel.READ_UNCOMMITTED));
        assertTrue(contains(values, IsolationLevel.READ_COMMITTED));
        assertTrue(contains(values, IsolationLevel.REPEATABLE_READ));
        assertTrue(contains(values, IsolationLevel.SERIALIZABLE));
    }

    @Test
    public void testEnumName() {
        assertEquals("DEFAULT", IsolationLevel.DEFAULT.name());
        assertEquals("NONE", IsolationLevel.NONE.name());
        assertEquals("READ_UNCOMMITTED", IsolationLevel.READ_UNCOMMITTED.name());
        assertEquals("READ_COMMITTED", IsolationLevel.READ_COMMITTED.name());
        assertEquals("REPEATABLE_READ", IsolationLevel.REPEATABLE_READ.name());
        assertEquals("SERIALIZABLE", IsolationLevel.SERIALIZABLE.name());
    }

    @Test
    public void testEnumOrdinal() {
        assertEquals(0, IsolationLevel.DEFAULT.ordinal());
        assertEquals(1, IsolationLevel.NONE.ordinal());
        assertEquals(2, IsolationLevel.READ_UNCOMMITTED.ordinal());
        assertEquals(3, IsolationLevel.READ_COMMITTED.ordinal());
        assertEquals(4, IsolationLevel.REPEATABLE_READ.ordinal());
        assertEquals(5, IsolationLevel.SERIALIZABLE.ordinal());
    }

    private boolean contains(IsolationLevel[] values, IsolationLevel level) {
        for (IsolationLevel value : values) {
            if (value == level) {
                return true;
            }
        }
        return false;
    }
}