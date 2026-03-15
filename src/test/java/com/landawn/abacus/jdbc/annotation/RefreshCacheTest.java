package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class RefreshCacheTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = RefreshCache.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = RefreshCache.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] targetTypes = target.value();
        assertEquals(2, targetTypes.length);
        assertTrue(java.util.Set.of(targetTypes).contains(ElementType.METHOD));
        assertTrue(java.util.Set.of(targetTypes).contains(ElementType.TYPE));
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(RefreshCache.class.isAnnotation());
    }

    @Test
    public void testDefaultValueDisabled() throws Exception {
        Method m = RefreshCache.class.getDeclaredMethod("disabled");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueFilter() throws Exception {
        Method m = RefreshCache.class.getDeclaredMethod("filter");
        Object defaultValue = m.getDefaultValue();
        assertNotNull(defaultValue);
        String[] expected = { "update", "delete", "deleteById", "insert", "save", "add", "remove", "upsert", "batchUpdate", "batchDelete", "batchDeleteByIds",
                "batchInsert", "batchSave", "batchAdd", "batchRemove", "batchUpsert", "execute" };
        assertArrayEquals(expected, (String[]) defaultValue);
    }
}
