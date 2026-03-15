package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class CrudJoinEntityHelperLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(CrudJoinEntityHelperL.class.isInterface());
    }

    @Test
    public void testExtendsCrudJoinEntityHelper() {
        assertTrue(CrudJoinEntityHelper.class.isAssignableFrom(CrudJoinEntityHelperL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, CrudJoinEntityHelperL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(CrudJoinEntityHelperL.class.getDeclaredMethods().length > 0);
    }
}
