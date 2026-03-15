package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedCrudJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedCrudJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedJoinEntityHelper() {
        assertTrue(UncheckedJoinEntityHelper.class.isAssignableFrom(UncheckedCrudJoinEntityHelper.class));
    }

    @Test
    public void testExtendsCrudJoinEntityHelper() {
        assertTrue(CrudJoinEntityHelper.class.isAssignableFrom(UncheckedCrudJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, UncheckedCrudJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedCrudJoinEntityHelper.class.getDeclaredMethods().length > 0);
    }
}
