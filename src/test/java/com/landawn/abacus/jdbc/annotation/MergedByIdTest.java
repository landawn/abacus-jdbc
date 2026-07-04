package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class MergedByIdTest extends TestBase {

    @Test
    public void testMergedById_RetentionPolicy() {
        Retention retention = MergedById.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testMergedById_Target() {
        Target target = MergedById.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] elementTypes = target.value();
        assertEquals(1, elementTypes.length);
        assertEquals(ElementType.METHOD, elementTypes[0]);
    }

    @Test
    public void testMergedById_IsAnnotation() {
        assertTrue(MergedById.class.isAnnotation());
    }

    @Test
    public void testMergedById_ValueDefault() throws Exception {
        Method valueMethod = MergedById.class.getDeclaredMethod("value");
        Object defaultValue = valueMethod.getDefaultValue();
        assertEquals("", defaultValue);
    }

    @Test
    public void testMergedById_ValueIsNotDeprecated() throws Exception {
        // value() is the only way to merge by non-id properties (e.g. @MergedById("ID, firstName")),
        // so it is a live, non-deprecated element; empty means "use the entity's @Id property(ies)".
        Method valueMethod = MergedById.class.getDeclaredMethod("value");
        assertNull(valueMethod.getAnnotation(Deprecated.class));
    }

    @Test
    public void testMergedById_DiscoverableViaReflection() {
        assertNotNull(MergedById.class.getAnnotation(Retention.class));
        assertNotNull(MergedById.class.getAnnotation(Target.class));
    }

    @Test
    public void testMergedById_ElementCount() {
        Method[] methods = MergedById.class.getDeclaredMethods();
        assertEquals(1, methods.length, "MergedById should have exactly 1 element: value");
    }
}
