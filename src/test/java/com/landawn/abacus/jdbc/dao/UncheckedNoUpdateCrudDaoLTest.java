package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedNoUpdateCrudDaoLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedNoUpdateCrudDaoL.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedNoUpdateCrudDao() {
        assertTrue(UncheckedNoUpdateCrudDao.class.isAssignableFrom(UncheckedNoUpdateCrudDaoL.class));
    }

    @Test
    public void testExtendsUncheckedCrudDaoL() {
        assertTrue(UncheckedCrudDaoL.class.isAssignableFrom(UncheckedNoUpdateCrudDaoL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedNoUpdateCrudDaoL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedNoUpdateCrudDaoL.class.getDeclaredMethods().length > 0);
    }
}
