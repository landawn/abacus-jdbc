package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class NoUpdateCrudDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(NoUpdateCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsNoUpdateDao() {
        assertTrue(NoUpdateDao.class.isAssignableFrom(NoUpdateCrudDao.class));
    }

    @Test
    public void testExtendsCrudDao() {
        assertTrue(CrudDao.class.isAssignableFrom(NoUpdateCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, NoUpdateCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(NoUpdateCrudDao.class.getDeclaredMethods().length > 0);
    }
}
