package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class CrudDaoLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(CrudDaoL.class.isInterface());
    }

    @Test
    public void testExtendsCrudDao() {
        assertTrue(CrudDao.class.isAssignableFrom(CrudDaoL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, CrudDaoL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(CrudDaoL.class.getDeclaredMethods().length > 0);
    }
}
