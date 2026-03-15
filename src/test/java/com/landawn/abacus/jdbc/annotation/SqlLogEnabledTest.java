package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.JdbcUtil;

public class SqlLogEnabledTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = SqlLogEnabled.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = SqlLogEnabled.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] targetTypes = target.value();
        assertEquals(2, targetTypes.length);
        assertTrue(java.util.Set.of(targetTypes).contains(ElementType.METHOD));
        assertTrue(java.util.Set.of(targetTypes).contains(ElementType.TYPE));
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(SqlLogEnabled.class.isAnnotation());
    }

    @Test
    public void testDefaultValueValue() throws Exception {
        Method m = SqlLogEnabled.class.getDeclaredMethod("value");
        Object defaultValue = m.getDefaultValue();
        assertEquals(true, defaultValue);
    }

    @Test
    public void testDefaultValueMaxSqlLogLength() throws Exception {
        Method m = SqlLogEnabled.class.getDeclaredMethod("maxSqlLogLength");
        Object defaultValue = m.getDefaultValue();
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, defaultValue);
    }

    @Test
    public void testDefaultValueFilter() throws Exception {
        Method m = SqlLogEnabled.class.getDeclaredMethod("filter");
        Object defaultValue = m.getDefaultValue();
        assertNotNull(defaultValue);
        assertArrayEquals(new String[] { ".*" }, (String[]) defaultValue);
    }
}
