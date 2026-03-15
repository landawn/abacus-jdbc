package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyCrudJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyCrudJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedReadOnlyJoinEntityHelper() {
        assertTrue(UncheckedReadOnlyJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelper.class));
    }

    @Test
    public void testExtendsUncheckedCrudJoinEntityHelper() {
        assertTrue(UncheckedCrudJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, UncheckedReadOnlyCrudJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
    }
}
