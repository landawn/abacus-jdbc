package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsJoinEntityHelper() {
        assertTrue(JoinEntityHelper.class.isAssignableFrom(UncheckedJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedJoinEntityHelper.class.getDeclaredMethods().length > 0);
    }
}
