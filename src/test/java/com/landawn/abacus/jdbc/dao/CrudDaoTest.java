package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class CrudDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(CrudDao.class.isInterface());
    }

    @Test
    public void testExtendsDao() {
        assertTrue(Dao.class.isAssignableFrom(CrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, CrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(CrudDao.class.getDeclaredMethods().length > 0);
    }
}
