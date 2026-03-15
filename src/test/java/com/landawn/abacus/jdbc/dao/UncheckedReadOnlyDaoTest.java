package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedNoUpdateDao() {
        assertTrue(UncheckedNoUpdateDao.class.isAssignableFrom(UncheckedReadOnlyDao.class));
    }

    @Test
    public void testExtendsReadOnlyDao() {
        assertTrue(ReadOnlyDao.class.isAssignableFrom(UncheckedReadOnlyDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedReadOnlyDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedReadOnlyDao.class.getDeclaredMethods().length > 0);
    }
}
