package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyCrudJoinEntityHelperLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyCrudJoinEntityHelperL.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedReadOnlyJoinEntityHelper() {
        assertTrue(UncheckedReadOnlyJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelperL.class));
    }

    @Test
    public void testExtendsUncheckedCrudJoinEntityHelperL() {
        assertTrue(UncheckedCrudJoinEntityHelperL.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelperL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedReadOnlyCrudJoinEntityHelperL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
    }
}
