package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class FetchDirectionTest extends TestBase {

    @Test
    public void testForwardFetchDirection() {
        assertEquals(ResultSet.FETCH_FORWARD, FetchDirection.FORWARD.intValue());
        assertEquals(ResultSet.FETCH_FORWARD, FetchDirection.FORWARD.intValue);
    }

    @Test
    public void testReverseFetchDirection() {
        assertEquals(ResultSet.FETCH_REVERSE, FetchDirection.REVERSE.intValue());
        assertEquals(ResultSet.FETCH_REVERSE, FetchDirection.REVERSE.intValue);
    }

    @Test
    public void testUnknownFetchDirection() {
        assertEquals(ResultSet.FETCH_UNKNOWN, FetchDirection.UNKNOWN.intValue());
        assertEquals(ResultSet.FETCH_UNKNOWN, FetchDirection.UNKNOWN.intValue);
    }

    @Test
    public void testValueOfForward() {
        assertEquals(FetchDirection.FORWARD, FetchDirection.valueOf(ResultSet.FETCH_FORWARD));
    }

    @Test
    public void testValueOfReverse() {
        assertEquals(FetchDirection.REVERSE, FetchDirection.valueOf(ResultSet.FETCH_REVERSE));
    }

    @Test
    public void testValueOfUnknown() {
        assertEquals(FetchDirection.UNKNOWN, FetchDirection.valueOf(ResultSet.FETCH_UNKNOWN));
    }

    @Test
    public void testValueOfInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> FetchDirection.valueOf(999));
        assertThrows(IllegalArgumentException.class, () -> FetchDirection.valueOf(-999));
        assertThrows(IllegalArgumentException.class, () -> FetchDirection.valueOf(123));
    }

    @Test
    public void testAllEnumValues() {
        FetchDirection[] values = FetchDirection.values();
        assertEquals(3, values.length);
        
        assertTrue(contains(values, FetchDirection.FORWARD));
        assertTrue(contains(values, FetchDirection.REVERSE));
        assertTrue(contains(values, FetchDirection.UNKNOWN));
    }

    @Test
    public void testEnumName() {
        assertEquals("FORWARD", FetchDirection.FORWARD.name());
        assertEquals("REVERSE", FetchDirection.REVERSE.name());
        assertEquals("UNKNOWN", FetchDirection.UNKNOWN.name());
    }

    @Test
    public void testEnumOrdinal() {
        assertEquals(0, FetchDirection.FORWARD.ordinal());
        assertEquals(1, FetchDirection.REVERSE.ordinal());
        assertEquals(2, FetchDirection.UNKNOWN.ordinal());
    }

    @Test
    public void testEnumToString() {
        assertEquals("FORWARD", FetchDirection.FORWARD.toString());
        assertEquals("REVERSE", FetchDirection.REVERSE.toString());
        assertEquals("UNKNOWN", FetchDirection.UNKNOWN.toString());
    }

    @Test
    public void testEnumValueOf() {
        assertEquals(FetchDirection.FORWARD, FetchDirection.valueOf("FORWARD"));
        assertEquals(FetchDirection.REVERSE, FetchDirection.valueOf("REVERSE"));
        assertEquals(FetchDirection.UNKNOWN, FetchDirection.valueOf("UNKNOWN"));
        
        assertThrows(IllegalArgumentException.class, () -> FetchDirection.valueOf("INVALID"));
    }

    private boolean contains(FetchDirection[] values, FetchDirection direction) {
        for (FetchDirection value : values) {
            if (value == direction) {
                return true;
            }
        }
        return false;
    }
}