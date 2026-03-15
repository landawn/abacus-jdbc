package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.EmptyHandler;

public class HandlerTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = Handler.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = Handler.class.getAnnotation(Target.class);
        assertNotNull(target);
        Set<ElementType> types = new HashSet<>(Arrays.asList(target.value()));
        assertEquals(2, types.size());
        assertTrue(types.contains(ElementType.METHOD));
        assertTrue(types.contains(ElementType.TYPE));
    }

    @Test
    public void testRepeatable() {
        Repeatable repeatable = Handler.class.getAnnotation(Repeatable.class);
        assertNotNull(repeatable);
        assertEquals(HandlerList.class, repeatable.value());
    }

    @Test
    public void testDefaultQualifier() throws Exception {
        assertEquals("", Handler.class.getMethod("qualifier").getDefaultValue());
    }

    @Test
    public void testDefaultType() throws Exception {
        assertEquals(EmptyHandler.class, Handler.class.getMethod("type").getDefaultValue());
    }

    @Test
    public void testDefaultFilter() throws Exception {
        String[] expected = { ".*" };
        assertArrayEquals(expected, (String[]) Handler.class.getMethod("filter").getDefaultValue());
    }

    @Test
    public void testDefaultIsForInvokeFromOutsideOfDaoOnly() throws Exception {
        assertEquals(false, Handler.class.getMethod("isForInvokeFromOutsideOfDaoOnly").getDefaultValue());
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(Handler.class.isAnnotation());
    }
}
