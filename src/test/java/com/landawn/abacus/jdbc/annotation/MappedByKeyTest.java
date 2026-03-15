package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class MappedByKeyTest extends TestBase {

    @Test
    public void testMappedByKey_RetentionPolicy() {
        Retention retention = MappedByKey.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testMappedByKey_Target() {
        Target target = MappedByKey.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] elementTypes = target.value();
        assertEquals(1, elementTypes.length);
        assertEquals(ElementType.METHOD, elementTypes[0]);
    }

    @Test
    public void testMappedByKey_IsAnnotation() {
        assertTrue(MappedByKey.class.isAnnotation());
    }

    @Test
    public void testMappedByKey_ValueDefault() throws Exception {
        Method valueMethod = MappedByKey.class.getDeclaredMethod("value");
        Object defaultValue = valueMethod.getDefaultValue();
        assertEquals("", defaultValue);
    }

    @Test
    public void testMappedByKey_KeyNameDefault() throws Exception {
        Method keyNameMethod = MappedByKey.class.getDeclaredMethod("keyName");
        Object defaultValue = keyNameMethod.getDefaultValue();
        assertEquals("", defaultValue);
    }

    @Test
    public void testMappedByKey_MapClassDefault() throws Exception {
        Method mapClassMethod = MappedByKey.class.getDeclaredMethod("mapClass");
        Object defaultValue = mapClassMethod.getDefaultValue();
        assertEquals(HashMap.class, defaultValue);
    }

    @Test
    public void testMappedByKey_MapClassReturnType() throws Exception {
        Method mapClassMethod = MappedByKey.class.getDeclaredMethod("mapClass");
        assertTrue(Class.class.isAssignableFrom(mapClassMethod.getReturnType()));
    }

    @Test
    public void testMappedByKey_DiscoverableViaReflection() {
        assertNotNull(MappedByKey.class.getAnnotation(Retention.class));
        assertNotNull(MappedByKey.class.getAnnotation(Target.class));
    }

    @Test
    public void testMappedByKey_ElementCount() {
        Method[] methods = MappedByKey.class.getDeclaredMethods();
        assertEquals(3, methods.length, "MappedByKey should have exactly 3 elements: value, keyName, mapClass");
    }
}
