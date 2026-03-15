package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedCrudDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedDao() {
        assertTrue(UncheckedDao.class.isAssignableFrom(UncheckedCrudDao.class));
    }

    @Test
    public void testExtendsCrudDao() {
        assertTrue(CrudDao.class.isAssignableFrom(UncheckedCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, UncheckedCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedCrudDao.class.getDeclaredMethods().length > 0);
    }
}
