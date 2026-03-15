package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class DaoUtilTest extends TestBase {

    @Test
    public void testIsConcreteClass() {
        assertFalse(DaoUtil.class.isInterface());
    }

    @Test
    public void testIsFinalClass() {
        assertTrue(Modifier.isFinal(DaoUtil.class.getModifiers()));
    }

    @Test
    public void testIsNotPublic() {
        assertFalse(Modifier.isPublic(DaoUtil.class.getModifiers()));
    }

    @Test
    public void testHasNoTypeParameters() {
        assertEquals(0, DaoUtil.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(DaoUtil.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testHasStaticMethods() {
        long staticMethodCount = java.util.Arrays.stream(DaoUtil.class.getDeclaredMethods()).filter(m -> Modifier.isStatic(m.getModifiers())).count();
        assertTrue(staticMethodCount > 0, "DaoUtil should contain static utility methods");
    }
}
