package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class PrefixFieldMappingTest extends TestBase {

    @Test
    public void testPrefixFieldMapping_RetentionPolicy() {
        Retention retention = PrefixFieldMapping.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testPrefixFieldMapping_Target() {
        Target target = PrefixFieldMapping.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] elementTypes = target.value();
        assertEquals(1, elementTypes.length);
        assertEquals(ElementType.METHOD, elementTypes[0]);
    }

    @Test
    public void testPrefixFieldMapping_IsAnnotation() {
        assertTrue(PrefixFieldMapping.class.isAnnotation());
    }

    @Test
    public void testPrefixFieldMapping_ValueDefault() throws Exception {
        Method valueMethod = PrefixFieldMapping.class.getDeclaredMethod("value");
        Object defaultValue = valueMethod.getDefaultValue();
        assertEquals("", defaultValue);
    }

    @Test
    public void testPrefixFieldMapping_ValueReturnType() throws Exception {
        Method valueMethod = PrefixFieldMapping.class.getDeclaredMethod("value");
        assertEquals(String.class, valueMethod.getReturnType());
    }

    @Test
    public void testPrefixFieldMapping_DiscoverableViaReflection() {
        assertNotNull(PrefixFieldMapping.class.getAnnotation(Retention.class));
        assertNotNull(PrefixFieldMapping.class.getAnnotation(Target.class));
    }

    @Test
    public void testPrefixFieldMapping_ElementCount() {
        Method[] methods = PrefixFieldMapping.class.getDeclaredMethods();
        assertEquals(1, methods.length, "PrefixFieldMapping should have exactly 1 element: value");
    }
}
