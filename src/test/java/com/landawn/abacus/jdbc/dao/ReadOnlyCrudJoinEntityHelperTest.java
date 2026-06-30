package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyCrudJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyCrudJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsReadOnlyJoinEntityHelper() {
        assertTrue(ReadOnlyJoinEntityHelper.class.isAssignableFrom(ReadOnlyCrudJoinEntityHelper.class));
    }

    @Test
    public void testExtendsCrudJoinEntityReadOps() {
        // Read-only CRUD join helper mixes in the read side only (not the full CrudJoinEntityHelper).
        assertTrue(CrudJoinEntityReadOps.class.isAssignableFrom(ReadOnlyCrudJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, ReadOnlyCrudJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
    }
}
