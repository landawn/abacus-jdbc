package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class DaoTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(Dao.class.isInterface());
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, Dao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(Dao.class.getDeclaredMethods().length > 0);
    }
}
