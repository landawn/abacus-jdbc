package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedCrudJoinEntityHelperLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedCrudJoinEntityHelperL.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedCrudJoinEntityHelper() {
        assertTrue(UncheckedCrudJoinEntityHelper.class.isAssignableFrom(UncheckedCrudJoinEntityHelperL.class));
    }

    @Test
    public void testExtendsCrudJoinEntityHelperL() {
        assertTrue(CrudJoinEntityHelperL.class.isAssignableFrom(UncheckedCrudJoinEntityHelperL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedCrudJoinEntityHelperL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedCrudJoinEntityHelperL.class.getDeclaredMethods().length > 0);
    }
}
