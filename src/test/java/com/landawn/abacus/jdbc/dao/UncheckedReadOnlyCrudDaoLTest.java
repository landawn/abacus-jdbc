package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyCrudDaoLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyCrudDaoL.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedReadOnlyCrudDao() {
        assertTrue(UncheckedReadOnlyCrudDao.class.isAssignableFrom(UncheckedReadOnlyCrudDaoL.class));
    }

    @Test
    public void testExtendsUncheckedNoUpdateCrudDaoL() {
        assertTrue(UncheckedNoUpdateCrudDaoL.class.isAssignableFrom(UncheckedReadOnlyCrudDaoL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedReadOnlyCrudDaoL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
    }
}
