package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyCrudDaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyCrudDao.class.isInterface());
    }

    @Test
    public void testExtendsReadOnlyDao() {
        assertTrue(ReadOnlyDao.class.isAssignableFrom(ReadOnlyCrudDao.class));
    }

    @Test
    public void testExtendsNoUpdateCrudDao() {
        assertTrue(NoUpdateCrudDao.class.isAssignableFrom(ReadOnlyCrudDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, ReadOnlyCrudDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(ReadOnlyCrudDao.class.getDeclaredMethods().length > 0);
    }
}
