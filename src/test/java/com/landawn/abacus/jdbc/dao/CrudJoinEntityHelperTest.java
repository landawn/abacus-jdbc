package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class CrudJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(CrudJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsJoinEntityHelper() {
        assertTrue(JoinEntityHelper.class.isAssignableFrom(CrudJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, CrudJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(CrudJoinEntityHelper.class.getDeclaredMethods().length > 0);
    }
}
