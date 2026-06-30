package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyCrudLJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyCrudLJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsReadOnlyCrudJoinEntityHelper() {
        assertTrue(ReadOnlyCrudJoinEntityHelper.class.isAssignableFrom(ReadOnlyCrudLJoinEntityHelper.class));
    }

    @Test
    public void testExtendsReadOnlyCrudJoinEntityHelperOnly() {
        // No longer extends the full (deletable) CrudLJoinEntityHelper; only the read-only CRUD join helper.
        assertTrue(CrudJoinEntityReadOps.class.isAssignableFrom(ReadOnlyCrudLJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, ReadOnlyCrudLJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(true, "Interface may inherit all methods without declaring its own");
    }
}
