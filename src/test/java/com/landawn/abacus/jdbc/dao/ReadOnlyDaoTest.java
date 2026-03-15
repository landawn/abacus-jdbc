package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyDao.class.isInterface());
    }

    @Test
    public void testExtendsNoUpdateDao() {
        assertTrue(NoUpdateDao.class.isAssignableFrom(ReadOnlyDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, ReadOnlyDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(ReadOnlyDao.class.getDeclaredMethods().length > 0);
    }
}
