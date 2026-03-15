package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class NoUpdateDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(NoUpdateDao.class.isInterface());
    }

    @Test
    public void testExtendsDao() {
        assertTrue(Dao.class.isAssignableFrom(NoUpdateDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, NoUpdateDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(NoUpdateDao.class.getDeclaredMethods().length > 0);
    }
}
