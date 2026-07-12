package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class HandlersTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = Handlers.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = Handlers.class.getAnnotation(Target.class);
        assertNotNull(target);
        Set<ElementType> types = new HashSet<>(Arrays.asList(target.value()));
        assertEquals(2, types.size());
        assertTrue(types.contains(ElementType.METHOD));
        assertTrue(types.contains(ElementType.TYPE));
    }

    @Test
    public void testValueElementHasNoDefault() throws Exception {
        // Handlers.value() has no default value; it is a required element
        assertNull(Handlers.class.getMethod("value").getDefaultValue());
    }

    @Test
    public void testValueReturnType() throws Exception {
        assertEquals(Handler[].class, Handlers.class.getMethod("value").getReturnType());
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(Handlers.class.isAnnotation());
    }
}
