package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.OnDeleteAction;

public class OnDeleteTest extends TestBase {

    @Test
    public void testOnDelete_RetentionPolicy() {
        Retention retention = OnDelete.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testOnDelete_Target() {
        Target target = OnDelete.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] elementTypes = target.value();
        assertEquals(1, elementTypes.length);
        assertEquals(ElementType.METHOD, elementTypes[0]);
    }

    @Test
    public void testOnDelete_IsAnnotation() {
        assertTrue(OnDelete.class.isAnnotation());
    }

    @Test
    public void testOnDelete_IsDeprecated() {
        assertNotNull(OnDelete.class.getAnnotation(Deprecated.class));
    }

    @Test
    public void testOnDelete_ActionDefault() throws Exception {
        Method actionMethod = OnDelete.class.getDeclaredMethod("action");
        Object defaultValue = actionMethod.getDefaultValue();
        assertEquals(OnDeleteAction.NO_ACTION, defaultValue);
    }

    @Test
    public void testOnDelete_ActionIsDeprecated() throws Exception {
        Method actionMethod = OnDelete.class.getDeclaredMethod("action");
        assertNotNull(actionMethod.getAnnotation(Deprecated.class));
    }

    @Test
    public void testOnDelete_DiscoverableViaReflection() {
        assertNotNull(OnDelete.class.getAnnotation(Retention.class));
        assertNotNull(OnDelete.class.getAnnotation(Target.class));
        assertNotNull(OnDelete.class.getAnnotation(Deprecated.class));
    }

    @Test
    public void testOnDelete_ElementCount() {
        Method[] methods = OnDelete.class.getDeclaredMethods();
        assertEquals(1, methods.length, "OnDelete should have exactly 1 element: action");
    }
}
