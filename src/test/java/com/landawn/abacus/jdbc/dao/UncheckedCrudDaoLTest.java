package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedCrudDaoLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedCrudDaoL.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedCrudDao() {
        assertTrue(UncheckedCrudDao.class.isAssignableFrom(UncheckedCrudDaoL.class));
    }

    @Test
    public void testExtendsCrudDaoL() {
        assertTrue(CrudDaoL.class.isAssignableFrom(UncheckedCrudDaoL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedCrudDaoL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedCrudDaoL.class.getDeclaredMethods().length > 0);
    }
}
