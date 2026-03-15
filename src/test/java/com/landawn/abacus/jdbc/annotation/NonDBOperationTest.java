package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class NonDBOperationTest extends TestBase {

    @Test
    public void testNonDBOperation_RetentionPolicy() {
        Retention retention = NonDBOperation.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testNonDBOperation_Target() {
        Target target = NonDBOperation.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] elementTypes = target.value();
        assertEquals(1, elementTypes.length);
        assertEquals(ElementType.METHOD, elementTypes[0]);
    }

    @Test
    public void testNonDBOperation_IsAnnotation() {
        assertTrue(NonDBOperation.class.isAnnotation());
    }

    @Test
    public void testNonDBOperation_NoElements() {
        Method[] methods = NonDBOperation.class.getDeclaredMethods();
        assertEquals(0, methods.length, "NonDBOperation should have no annotation elements");
    }

    @Test
    public void testNonDBOperation_DiscoverableViaReflection() {
        assertNotNull(NonDBOperation.class.getAnnotation(Retention.class));
        assertNotNull(NonDBOperation.class.getAnnotation(Target.class));
    }

    @Test
    public void testNonDBOperation_IsInterface() {
        assertTrue(NonDBOperation.class.isInterface(), "Annotations are interfaces");
    }
}
