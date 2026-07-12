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

public class OutParametersTest extends TestBase {

    @Test
    public void testOutParameters_RetentionPolicy() {
        Retention retention = OutParameters.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testOutParameters_Target() {
        Target target = OutParameters.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] elementTypes = target.value();
        assertEquals(1, elementTypes.length);
        assertEquals(ElementType.METHOD, elementTypes[0]);
    }

    @Test
    public void testOutParameters_IsAnnotation() {
        assertTrue(OutParameters.class.isAnnotation());
    }

    @Test
    public void testOutParameters_ValueHasNoDefault() throws Exception {
        Method valueMethod = OutParameters.class.getDeclaredMethod("value");
        Object defaultValue = valueMethod.getDefaultValue();
        assertNull(defaultValue, "value() should have no default (it is required)");
    }

    @Test
    public void testOutParameters_ValueReturnType() throws Exception {
        Method valueMethod = OutParameters.class.getDeclaredMethod("value");
        assertEquals(OutParameter[].class, valueMethod.getReturnType());
    }

    @Test
    public void testOutParameters_DiscoverableViaReflection() {
        assertNotNull(OutParameters.class.getAnnotation(Retention.class));
        assertNotNull(OutParameters.class.getAnnotation(Target.class));
    }

    @Test
    public void testOutParameters_ElementCount() {
        Method[] methods = OutParameters.class.getDeclaredMethods();
        assertEquals(1, methods.length, "OutParameters should have exactly 1 element: value");
    }
}
