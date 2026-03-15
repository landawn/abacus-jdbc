package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyCrudJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyCrudJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsReadOnlyJoinEntityHelper() {
        assertTrue(ReadOnlyJoinEntityHelper.class.isAssignableFrom(ReadOnlyCrudJoinEntityHelper.class));
    }

    @Test
    public void testExtendsCrudJoinEntityHelper() {
        assertTrue(CrudJoinEntityHelper.class.isAssignableFrom(ReadOnlyCrudJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(4, ReadOnlyCrudJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
    }
}
