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

    @Test
    public void testContainerIsCompatibleWithRepeatableHandler() {
        // JLS 9.6.3: the container must be retained at least as long as, be applicable to every target of,
        // and be @Documented whenever the repeatable annotation is; otherwise repeated @Handler fails to compile.
        assertEquals(Handlers.class, Handler.class.getAnnotation(java.lang.annotation.Repeatable.class).value());
        assertEquals(Handler.class.getAnnotation(Retention.class).value(), Handlers.class.getAnnotation(Retention.class).value());
        Set<ElementType> containerTargets = new HashSet<>(Arrays.asList(Handlers.class.getAnnotation(Target.class).value()));
        assertTrue(containerTargets.containsAll(Arrays.asList(Handler.class.getAnnotation(Target.class).value())));
        assertNotNull(Handlers.class.getAnnotation(java.lang.annotation.Documented.class));
    }
}
