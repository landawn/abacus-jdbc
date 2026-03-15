package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsJoinEntityHelper() {
        assertTrue(JoinEntityHelper.class.isAssignableFrom(ReadOnlyJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, ReadOnlyJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(ReadOnlyJoinEntityHelper.class.getDeclaredMethods().length > 0);
    }
}
