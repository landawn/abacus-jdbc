package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedNoUpdateCrudDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedNoUpdateCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedNoUpdateDao() {
        assertTrue(UncheckedNoUpdateDao.class.isAssignableFrom(UncheckedNoUpdateCrudDao.class));
    }

    @Test
    public void testExtendsNoUpdateCrudDao() {
        assertTrue(NoUpdateCrudDao.class.isAssignableFrom(UncheckedNoUpdateCrudDao.class));
    }

    @Test
    public void testExtendsUncheckedCrudDao() {
        assertTrue(UncheckedCrudDao.class.isAssignableFrom(UncheckedNoUpdateCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, UncheckedNoUpdateCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedNoUpdateCrudDao.class.getDeclaredMethods().length > 0);
    }
}
