package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedJoinEntityHelper() {
        assertTrue(UncheckedJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyJoinEntityHelper.class));
    }

    @Test
    public void testExtendsReadOnlyJoinEntityHelper() {
        assertTrue(ReadOnlyJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedReadOnlyJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedReadOnlyJoinEntityHelper.class.getDeclaredMethods().length > 0);
    }
}
