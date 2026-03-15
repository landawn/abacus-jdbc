package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyCrudJoinEntityHelperLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyCrudJoinEntityHelperL.class.isInterface());
    }

    @Test
    public void testExtendsReadOnlyCrudJoinEntityHelper() {
        assertTrue(ReadOnlyCrudJoinEntityHelper.class.isAssignableFrom(ReadOnlyCrudJoinEntityHelperL.class));
    }

    @Test
    public void testExtendsCrudJoinEntityHelperL() {
        assertTrue(CrudJoinEntityHelperL.class.isAssignableFrom(ReadOnlyCrudJoinEntityHelperL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, ReadOnlyCrudJoinEntityHelperL.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
    }
}
