package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class FetchColumnByEntityClassTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = FetchColumnByEntityClass.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = FetchColumnByEntityClass.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(ElementType.METHOD, target.value()[0]);
    }

    @Test
    public void testDefaultValue() throws Exception {
        assertEquals(true, FetchColumnByEntityClass.class.getMethod("value").getDefaultValue());
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(FetchColumnByEntityClass.class.isAnnotation());
    }
}
