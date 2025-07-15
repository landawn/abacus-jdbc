package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class PropagationTest extends TestBase {

    @Test
    public void testEnumValues() {
        Propagation[] values = Propagation.values();
        assertEquals(4, values.length);
        
        assertTrue(contains(values, Propagation.REQUIRED));
        assertTrue(contains(values, Propagation.SUPPORTS));
        assertTrue(contains(values, Propagation.REQUIRES_NEW));
        assertTrue(contains(values, Propagation.NOT_SUPPORTED));
    }

    @Test
    public void testEnumName() {
        assertEquals("REQUIRED", Propagation.REQUIRED.name());
        assertEquals("SUPPORTS", Propagation.SUPPORTS.name());
        assertEquals("REQUIRES_NEW", Propagation.REQUIRES_NEW.name());
        assertEquals("NOT_SUPPORTED", Propagation.NOT_SUPPORTED.name());
    }

    @Test
    public void testEnumOrdinal() {
        assertEquals(0, Propagation.REQUIRED.ordinal());
        assertEquals(1, Propagation.SUPPORTS.ordinal());
        assertEquals(2, Propagation.REQUIRES_NEW.ordinal());
        assertEquals(3, Propagation.NOT_SUPPORTED.ordinal());
    }

    @Test
    public void testEnumToString() {
        assertEquals("REQUIRED", Propagation.REQUIRED.toString());
        assertEquals("SUPPORTS", Propagation.SUPPORTS.toString());
        assertEquals("REQUIRES_NEW", Propagation.REQUIRES_NEW.toString());
        assertEquals("NOT_SUPPORTED", Propagation.NOT_SUPPORTED.toString());
    }

    @Test
    public void testEnumValueOf() {
        assertEquals(Propagation.REQUIRED, Propagation.valueOf("REQUIRED"));
        assertEquals(Propagation.SUPPORTS, Propagation.valueOf("SUPPORTS"));
        assertEquals(Propagation.REQUIRES_NEW, Propagation.valueOf("REQUIRES_NEW"));
        assertEquals(Propagation.NOT_SUPPORTED, Propagation.valueOf("NOT_SUPPORTED"));
        
        assertThrows(IllegalArgumentException.class, () -> Propagation.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> Propagation.valueOf(""));
        assertThrows(NullPointerException.class, () -> Propagation.valueOf(null));
    }

    @Test
    public void testEnumComparison() {
        assertTrue(Propagation.REQUIRED != Propagation.SUPPORTS);
        assertTrue(Propagation.REQUIRED != Propagation.REQUIRES_NEW);
        assertTrue(Propagation.REQUIRED != Propagation.NOT_SUPPORTED);
        
        assertTrue(Propagation.REQUIRED == Propagation.REQUIRED);
        assertTrue(Propagation.SUPPORTS == Propagation.SUPPORTS);
        assertTrue(Propagation.REQUIRES_NEW == Propagation.REQUIRES_NEW);
        assertTrue(Propagation.NOT_SUPPORTED == Propagation.NOT_SUPPORTED);
    }

    private boolean contains(Propagation[] values, Propagation propagation) {
        for (Propagation value : values) {
            if (value == propagation) {
                return true;
            }
        }
        return false;
    }
}