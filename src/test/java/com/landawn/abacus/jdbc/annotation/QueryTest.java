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
import com.landawn.abacus.jdbc.OP;

public class QueryTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = Query.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = Query.class.getAnnotation(Target.class);
        assertNotNull(target);
        ElementType[] targetTypes = target.value();
        assertEquals(1, targetTypes.length);
        assertEquals(ElementType.METHOD, targetTypes[0]);
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(Query.class.isAnnotation());
    }

    @Test
    public void testDefaultValueValue() throws Exception {
        Method m = Query.class.getDeclaredMethod("value");
        Object defaultValue = m.getDefaultValue();
        assertNotNull(defaultValue);
        assertArrayEquals(new String[] {}, (String[]) defaultValue);
    }

    @Test
    public void testDefaultValueId() throws Exception {
        Method m = Query.class.getDeclaredMethod("id");
        Object defaultValue = m.getDefaultValue();
        assertNotNull(defaultValue);
        assertArrayEquals(new String[] {}, (String[]) defaultValue);
    }

    @Test
    public void testDefaultValueOp() throws Exception {
        Method m = Query.class.getDeclaredMethod("op");
        Object defaultValue = m.getDefaultValue();
        assertEquals(OP.DEFAULT, defaultValue);
    }

    @Test
    public void testDefaultValueIsProcedure() throws Exception {
        Method m = Query.class.getDeclaredMethod("isProcedure");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueIsBatch() throws Exception {
        Method m = Query.class.getDeclaredMethod("isBatch");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueIsSingleParameter() throws Exception {
        Method m = Query.class.getDeclaredMethod("isSingleParameter");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueFragmentContainsNamedParameters() throws Exception {
        Method m = Query.class.getDeclaredMethod("fragmentContainsNamedParameters");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueAutoSetSysTimeParam() throws Exception {
        Method m = Query.class.getDeclaredMethod("autoSetSysTimeParam");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueQueryTimeout() throws Exception {
        Method m = Query.class.getDeclaredMethod("queryTimeout");
        Object defaultValue = m.getDefaultValue();
        assertEquals(-1, defaultValue);
    }

    @Test
    public void testDefaultValueFetchSize() throws Exception {
        Method m = Query.class.getDeclaredMethod("fetchSize");
        Object defaultValue = m.getDefaultValue();
        assertEquals(-1, defaultValue);
    }

    @Test
    public void testDefaultValueBatchSize() throws Exception {
        Method m = Query.class.getDeclaredMethod("batchSize");
        Object defaultValue = m.getDefaultValue();
        assertEquals(JdbcUtil.DEFAULT_BATCH_SIZE, defaultValue);
    }
}
