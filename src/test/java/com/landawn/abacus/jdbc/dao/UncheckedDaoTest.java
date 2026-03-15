package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedDao.class.isInterface());
    }

    @Test
    public void testExtendsDao() {
        assertTrue(Dao.class.isAssignableFrom(UncheckedDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedDao.class.getDeclaredMethods().length > 0);
    }
}
