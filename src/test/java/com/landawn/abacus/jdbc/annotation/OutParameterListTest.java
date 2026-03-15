package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class OutParameterListTest extends TestBase {

    @Test
    public void testOutParameterList_RetentionPolicy() {
        Retention retention = OutParameterList.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testOutParameterList_Target() {
        Target target = OutParameterList.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] elementTypes = target.value();
        assertEquals(1, elementTypes.length);
        assertEquals(ElementType.METHOD, elementTypes[0]);
    }

    @Test
    public void testOutParameterList_IsAnnotation() {
        assertTrue(OutParameterList.class.isAnnotation());
    }

    @Test
    public void testOutParameterList_ValueHasNoDefault() throws Exception {
        Method valueMethod = OutParameterList.class.getDeclaredMethod("value");
        Object defaultValue = valueMethod.getDefaultValue();
        assertNull(defaultValue, "value() should have no default (it is required)");
    }

    @Test
    public void testOutParameterList_ValueReturnType() throws Exception {
        Method valueMethod = OutParameterList.class.getDeclaredMethod("value");
        assertEquals(OutParameter[].class, valueMethod.getReturnType());
    }

    @Test
    public void testOutParameterList_DiscoverableViaReflection() {
        assertNotNull(OutParameterList.class.getAnnotation(Retention.class));
        assertNotNull(OutParameterList.class.getAnnotation(Target.class));
    }

    @Test
    public void testOutParameterList_ElementCount() {
        Method[] methods = OutParameterList.class.getDeclaredMethods();
        assertEquals(1, methods.length, "OutParameterList should have exactly 1 element: value");
    }
}
