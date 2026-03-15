package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyCrudDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedReadOnlyDao() {
        assertTrue(UncheckedReadOnlyDao.class.isAssignableFrom(UncheckedReadOnlyCrudDao.class));
    }

    @Test
    public void testExtendsUncheckedNoUpdateCrudDao() {
        assertTrue(UncheckedNoUpdateCrudDao.class.isAssignableFrom(UncheckedReadOnlyCrudDao.class));
    }

    @Test
    public void testExtendsReadOnlyCrudDao() {
        assertTrue(ReadOnlyCrudDao.class.isAssignableFrom(UncheckedReadOnlyCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, UncheckedReadOnlyCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedReadOnlyCrudDao.class.getDeclaredMethods().length > 0);
    }
}
