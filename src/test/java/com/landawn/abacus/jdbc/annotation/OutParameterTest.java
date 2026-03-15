package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class OutParameterTest extends TestBase {

    @Test
    public void testOutParameter_RetentionPolicy() {
        Retention retention = OutParameter.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testOutParameter_Target() {
        Target target = OutParameter.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] elementTypes = target.value();
        assertEquals(1, elementTypes.length);
        assertEquals(ElementType.METHOD, elementTypes[0]);
    }

    @Test
    public void testOutParameter_IsAnnotation() {
        assertTrue(OutParameter.class.isAnnotation());
    }

    @Test
    public void testOutParameter_IsRepeatable() {
        Repeatable repeatable = OutParameter.class.getAnnotation(Repeatable.class);
        assertNotNull(repeatable);
        assertEquals(OutParameterList.class, repeatable.value());
    }

    @Test
    public void testOutParameter_NameDefault() throws Exception {
        Method nameMethod = OutParameter.class.getDeclaredMethod("name");
        Object defaultValue = nameMethod.getDefaultValue();
        assertEquals("", defaultValue);
    }

    @Test
    public void testOutParameter_PositionDefault() throws Exception {
        Method positionMethod = OutParameter.class.getDeclaredMethod("position");
        Object defaultValue = positionMethod.getDefaultValue();
        assertEquals(-1, defaultValue);
    }

    @Test
    public void testOutParameter_SqlTypeHasNoDefault() throws Exception {
        Method sqlTypeMethod = OutParameter.class.getDeclaredMethod("sqlType");
        Object defaultValue = sqlTypeMethod.getDefaultValue();
        assertNull(defaultValue, "sqlType should have no default value (it is required)");
    }

    @Test
    public void testOutParameter_SqlTypeReturnType() throws Exception {
        Method sqlTypeMethod = OutParameter.class.getDeclaredMethod("sqlType");
        assertEquals(int.class, sqlTypeMethod.getReturnType());
    }

    @Test
    public void testOutParameter_DiscoverableViaReflection() {
        assertNotNull(OutParameter.class.getAnnotation(Retention.class));
        assertNotNull(OutParameter.class.getAnnotation(Target.class));
        assertNotNull(OutParameter.class.getAnnotation(Repeatable.class));
    }

    @Test
    public void testOutParameter_ElementCount() {
        Method[] methods = OutParameter.class.getDeclaredMethods();
        assertEquals(3, methods.length, "OutParameter should have exactly 3 elements: name, position, sqlType");
    }
}
