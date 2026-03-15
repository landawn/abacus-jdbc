package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class NoUpdateCrudDaoLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(NoUpdateCrudDaoL.class.isInterface());
    }

    @Test
    public void testExtendsNoUpdateCrudDao() {
        assertTrue(NoUpdateCrudDao.class.isAssignableFrom(NoUpdateCrudDaoL.class));
    }

    @Test
    public void testExtendsCrudDaoL() {
        assertTrue(CrudDaoL.class.isAssignableFrom(NoUpdateCrudDaoL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, NoUpdateCrudDaoL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(NoUpdateCrudDaoL.class.getDeclaredMethods().length > 0);
    }
}
