package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.JdbcUtil;

public class PerfLogTest extends TestBase {

    @Test
    public void testPerfLog_RetentionPolicy() {
        Retention retention = PerfLog.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testPerfLog_Target() {
        Target target = PerfLog.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] elementTypes = target.value();
        assertEquals(2, elementTypes.length);
        Set<ElementType> targetSet = Arrays.stream(elementTypes).collect(Collectors.toSet());
        assertTrue(targetSet.contains(ElementType.METHOD));
        assertTrue(targetSet.contains(ElementType.TYPE));
    }

    @Test
    public void testPerfLog_IsAnnotation() {
        assertTrue(PerfLog.class.isAnnotation());
    }

    @Test
    public void testPerfLog_MinExecutionTimeForSqlDefault() throws Exception {
        Method method = PerfLog.class.getDeclaredMethod("minExecutionTimeForSql");
        Object defaultValue = method.getDefaultValue();
        assertEquals(JdbcUtil.DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG, defaultValue);
        assertEquals(1000L, defaultValue);
    }

    @Test
    public void testPerfLog_MaxSqlLogLengthDefault() throws Exception {
        Method method = PerfLog.class.getDeclaredMethod("maxSqlLogLength");
        Object defaultValue = method.getDefaultValue();
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, defaultValue);
        assertEquals(1024, defaultValue);
    }

    @Test
    public void testPerfLog_MinExecutionTimeForOperationDefault() throws Exception {
        Method method = PerfLog.class.getDeclaredMethod("minExecutionTimeForOperation");
        Object defaultValue = method.getDefaultValue();
        assertEquals(JdbcUtil.DEFAULT_MIN_EXECUTION_TIME_FOR_DAO_METHOD_PERF_LOG, defaultValue);
        assertEquals(3000L, defaultValue);
    }

    @Test
    public void testPerfLog_FilterDefault() throws Exception {
        Method method = PerfLog.class.getDeclaredMethod("filter");
        Object defaultValue = method.getDefaultValue();
        assertNotNull(defaultValue);
        assertTrue(defaultValue instanceof String[]);
        String[] filterArray = (String[]) defaultValue;
        assertEquals(1, filterArray.length);
        assertEquals(".*", filterArray[0]);
    }

    @Test
    public void testPerfLog_FilterReturnType() throws Exception {
        Method method = PerfLog.class.getDeclaredMethod("filter");
        assertEquals(String[].class, method.getReturnType());
    }

    @Test
    public void testPerfLog_MinExecutionTimeForSqlReturnType() throws Exception {
        Method method = PerfLog.class.getDeclaredMethod("minExecutionTimeForSql");
        assertEquals(long.class, method.getReturnType());
    }

    @Test
    public void testPerfLog_MaxSqlLogLengthReturnType() throws Exception {
        Method method = PerfLog.class.getDeclaredMethod("maxSqlLogLength");
        assertEquals(int.class, method.getReturnType());
    }

    @Test
    public void testPerfLog_DiscoverableViaReflection() {
        assertNotNull(PerfLog.class.getAnnotation(Retention.class));
        assertNotNull(PerfLog.class.getAnnotation(Target.class));
    }

    @Test
    public void testPerfLog_ElementCount() {
        Method[] methods = PerfLog.class.getDeclaredMethods();
        assertEquals(4, methods.length,
                "PerfLog should have exactly 4 elements: minExecutionTimeForSql, maxSqlLogLength, minExecutionTimeForOperation, filter");
    }
}
