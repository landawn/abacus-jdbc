package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class SqlSourceTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = SqlSource.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = SqlSource.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] targetTypes = target.value();
        assertEquals(1, targetTypes.length);
        assertEquals(ElementType.TYPE, targetTypes[0]);
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(SqlSource.class.isAnnotation());
    }

    @Test
    public void testDefaultValueValue() throws Exception {
        Method m = SqlSource.class.getDeclaredMethod("value");
        Object defaultValue = m.getDefaultValue();
        assertEquals("", defaultValue);
    }
}
