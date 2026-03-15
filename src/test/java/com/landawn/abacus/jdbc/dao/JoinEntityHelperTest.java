package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class JoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(JoinEntityHelper.class.isInterface());
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, JoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(JoinEntityHelper.class.getDeclaredMethods().length > 0);
    }
}
