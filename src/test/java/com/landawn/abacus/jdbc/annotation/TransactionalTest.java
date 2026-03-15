package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.Propagation;

public class TransactionalTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = Transactional.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = Transactional.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] targetTypes = target.value();
        assertEquals(1, targetTypes.length);
        assertEquals(ElementType.METHOD, targetTypes[0]);
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(Transactional.class.isAnnotation());
    }

    @Test
    public void testDefaultValuePropagation() throws Exception {
        Method m = Transactional.class.getDeclaredMethod("propagation");
        Object defaultValue = m.getDefaultValue();
        assertEquals(Propagation.REQUIRED, defaultValue);
    }

    @Test
    public void testDefaultValueIsolation() throws Exception {
        Method m = Transactional.class.getDeclaredMethod("isolation");
        Object defaultValue = m.getDefaultValue();
        assertEquals(IsolationLevel.DEFAULT, defaultValue);
    }
}
