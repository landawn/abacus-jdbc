package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedNoUpdateDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedNoUpdateDao.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedDao() {
        assertTrue(UncheckedDao.class.isAssignableFrom(UncheckedNoUpdateDao.class));
    }

    @Test
    public void testExtendsNoUpdateDao() {
        assertTrue(NoUpdateDao.class.isAssignableFrom(UncheckedNoUpdateDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedNoUpdateDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedNoUpdateDao.class.getDeclaredMethods().length > 0);
    }
}
