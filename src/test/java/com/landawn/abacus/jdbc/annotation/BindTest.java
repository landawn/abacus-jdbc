package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class BindTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = Bind.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = Bind.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(ElementType.PARAMETER, target.value()[0]);
    }

    @Test
    public void testDefaultValue() throws Exception {
        assertEquals("", Bind.class.getMethod("value").getDefaultValue());
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(Bind.class.isAnnotation());
    }
}
