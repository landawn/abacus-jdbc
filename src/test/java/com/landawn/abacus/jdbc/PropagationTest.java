
package com.landawn.abacus.jdbc;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class PropagationTest extends TestBase {

    @Test
    public void testEnumValues() {
        // Test that all expected enum values exist
        Propagation[] values = Propagation.values();
        Assertions.assertEquals(6, values.length);

        // Verify each enum constant exists
        Assertions.assertNotNull(Propagation.REQUIRED);
        Assertions.assertNotNull(Propagation.SUPPORTS);
        Assertions.assertNotNull(Propagation.MANDATORY);
        Assertions.assertNotNull(Propagation.REQUIRES_NEW);
        Assertions.assertNotNull(Propagation.NOT_SUPPORTED);
        Assertions.assertNotNull(Propagation.NEVER);
    }

    @Test
    public void testEnumValueOf() {
        // Test valueOf method for each enum constant
        Assertions.assertEquals(Propagation.REQUIRED, Propagation.valueOf("REQUIRED"));
        Assertions.assertEquals(Propagation.SUPPORTS, Propagation.valueOf("SUPPORTS"));
        Assertions.assertEquals(Propagation.MANDATORY, Propagation.valueOf("MANDATORY"));
        Assertions.assertEquals(Propagation.REQUIRES_NEW, Propagation.valueOf("REQUIRES_NEW"));
        Assertions.assertEquals(Propagation.NOT_SUPPORTED, Propagation.valueOf("NOT_SUPPORTED"));
        Assertions.assertEquals(Propagation.NEVER, Propagation.valueOf("NEVER"));
    }

    @Test
    public void testEnumValueOfInvalidName() {
        // Test that valueOf throws exception for invalid name
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            Propagation.valueOf("INVALID_NAME");
        });

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            Propagation.valueOf("");
        });

        Assertions.assertThrows(NullPointerException.class, () -> {
            Propagation.valueOf(null);
        });
    }

    @Test
    public void testEnumOrdinal() {
        // Test ordinal values
        Assertions.assertEquals(0, Propagation.REQUIRED.ordinal());
        Assertions.assertEquals(1, Propagation.SUPPORTS.ordinal());
        Assertions.assertEquals(2, Propagation.MANDATORY.ordinal());
        Assertions.assertEquals(3, Propagation.REQUIRES_NEW.ordinal());
        Assertions.assertEquals(4, Propagation.NOT_SUPPORTED.ordinal());
        Assertions.assertEquals(5, Propagation.NEVER.ordinal());
    }

    @Test
    public void testEnumName() {
        // Test name() method
        Assertions.assertEquals("REQUIRED", Propagation.REQUIRED.name());
        Assertions.assertEquals("SUPPORTS", Propagation.SUPPORTS.name());
        Assertions.assertEquals("MANDATORY", Propagation.MANDATORY.name());
        Assertions.assertEquals("REQUIRES_NEW", Propagation.REQUIRES_NEW.name());
        Assertions.assertEquals("NOT_SUPPORTED", Propagation.NOT_SUPPORTED.name());
        Assertions.assertEquals("NEVER", Propagation.NEVER.name());
    }

    @Test
    public void testEnumToString() {
        // Test toString() method (should be same as name() for enum)
        Assertions.assertEquals("REQUIRED", Propagation.REQUIRED.toString());
        Assertions.assertEquals("SUPPORTS", Propagation.SUPPORTS.toString());
        Assertions.assertEquals("MANDATORY", Propagation.MANDATORY.toString());
        Assertions.assertEquals("REQUIRES_NEW", Propagation.REQUIRES_NEW.toString());
        Assertions.assertEquals("NOT_SUPPORTED", Propagation.NOT_SUPPORTED.toString());
        Assertions.assertEquals("NEVER", Propagation.NEVER.toString());
    }

    @Test
    public void testEnumCompareTo() {
        // Test compareTo method based on ordinal
        Assertions.assertTrue(Propagation.REQUIRED.compareTo(Propagation.SUPPORTS) < 0);
        Assertions.assertTrue(Propagation.SUPPORTS.compareTo(Propagation.REQUIRED) > 0);
        Assertions.assertEquals(0, Propagation.REQUIRED.compareTo(Propagation.REQUIRED));

        Assertions.assertTrue(Propagation.MANDATORY.compareTo(Propagation.NEVER) < 0);
        Assertions.assertTrue(Propagation.NEVER.compareTo(Propagation.REQUIRED) > 0);
    }

    @Test
    public void testEnumEquals() {
        // Test equals method
        Assertions.assertEquals(Propagation.REQUIRED, Propagation.REQUIRED);
        Assertions.assertNotEquals(Propagation.REQUIRED, Propagation.SUPPORTS);
        Assertions.assertNotEquals(Propagation.REQUIRED, null);
        Assertions.assertNotEquals(Propagation.REQUIRED, "REQUIRED");

        // Test with valueOf
        Assertions.assertEquals(Propagation.REQUIRED, Propagation.valueOf("REQUIRED"));
        Assertions.assertEquals(Propagation.NEVER, Propagation.valueOf("NEVER"));
    }

    @Test
    public void testEnumHashCode() {
        // Test hashCode consistency
        Propagation required1 = Propagation.REQUIRED;
        Propagation required2 = Propagation.valueOf("REQUIRED");

        Assertions.assertEquals(required1.hashCode(), required2.hashCode());

        // Different enum values should have different hash codes (though not guaranteed)
        // Just verify they return consistent values
        int hashRequired = Propagation.REQUIRED.hashCode();
        int hashSupports = Propagation.SUPPORTS.hashCode();

        // Verify consistency
        Assertions.assertEquals(hashRequired, Propagation.REQUIRED.hashCode());
        Assertions.assertEquals(hashSupports, Propagation.SUPPORTS.hashCode());
    }

    @Test
    public void testEnumInSwitch() {
        // Test that enum can be used in switch statements
        for (Propagation prop : Propagation.values()) {
            String description = getDescription(prop);
            Assertions.assertNotNull(description);
            Assertions.assertFalse(description.isEmpty());
        }
    }

    @Test
    public void testEnumValuesArray() {
        // Test that values() returns a new array each time
        Propagation[] values1 = Propagation.values();
        Propagation[] values2 = Propagation.values();

        Assertions.assertNotSame(values1, values2);
        Assertions.assertArrayEquals(values1, values2);

        // Modifying one array should not affect the other
        values1[0] = Propagation.NEVER;
        Assertions.assertEquals(Propagation.REQUIRED, values2[0]);
    }

    @Test
    public void testEnumImmutability() {
        // Test that enum instances are effectively immutable
        Propagation required = Propagation.REQUIRED;
        Propagation alsoRequired = Propagation.valueOf("REQUIRED");

        // Should be the same instance (enum singleton pattern)
        Assertions.assertSame(required, alsoRequired);
    }

    @Test
    public void testEnumClassMethods() {
        // Test getDeclaringClass method
        for (Propagation prop : Propagation.values()) {
            Assertions.assertEquals(Propagation.class, prop.getDeclaringClass());
        }
    }

    // Helper method for switch test
    private String getDescription(Propagation propagation) {
        switch (propagation) {
            case REQUIRED:
                return "Support a current transaction, create a new one if none exists";
            case SUPPORTS:
                return "Support a current transaction, execute non-transactionally if none exists";
            case MANDATORY:
                return "Support a current transaction, throw an exception if none exists";
            case REQUIRES_NEW:
                return "Create a new transaction, and suspend the current transaction if one exists";
            case NOT_SUPPORTED:
                return "Execute non-transactionally, suspend the current transaction if one exists";
            case NEVER:
                return "Execute non-transactionally, throw an exception if a transaction exists";
            default:
                return "Unknown propagation type";
        }
    }
}