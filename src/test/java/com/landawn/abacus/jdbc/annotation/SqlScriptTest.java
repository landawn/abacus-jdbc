package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class SqlScriptTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = SqlScript.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = SqlScript.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] targetTypes = target.value();
        assertEquals(1, targetTypes.length);
        assertEquals(ElementType.FIELD, targetTypes[0]);
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(SqlScript.class.isAnnotation());
    }

    @Test
    public void testDefaultValueId() throws Exception {
        Method m = SqlScript.class.getDeclaredMethod("id");
        Object defaultValue = m.getDefaultValue();
        assertEquals("", defaultValue);
    }
}
