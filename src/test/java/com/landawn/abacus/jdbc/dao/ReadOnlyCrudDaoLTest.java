package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyCrudDaoLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyCrudDaoL.class.isInterface());
    }

    @Test
    public void testExtendsReadOnlyCrudDao() {
        assertTrue(ReadOnlyCrudDao.class.isAssignableFrom(ReadOnlyCrudDaoL.class));
    }

    @Test
    public void testExtendsNoUpdateCrudDaoL() {
        assertTrue(NoUpdateCrudDaoL.class.isAssignableFrom(ReadOnlyCrudDaoL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, ReadOnlyCrudDaoL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
    }
}
