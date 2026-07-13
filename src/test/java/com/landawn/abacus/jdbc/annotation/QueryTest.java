package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.QueryOperation;

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
        assertEquals(QueryOperation.DEFAULT, defaultValue);
    }

    @Test
    public void testDefaultValueProcedure() throws Exception {
        Method m = Query.class.getDeclaredMethod("procedure");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueBatch() throws Exception {
        Method m = Query.class.getDeclaredMethod("batch");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueCollectionAsSingleParameter() throws Exception {
        Method m = Query.class.getDeclaredMethod("collectionAsSingleParameter");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueFragmentsContainNamedParameters() throws Exception {
        Method m = Query.class.getDeclaredMethod("fragmentsContainNamedParameters");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueInjectCurrentTimeParameters() throws Exception {
        Method m = Query.class.getDeclaredMethod("injectCurrentTimeParameters");
        Object defaultValue = m.getDefaultValue();
        assertEquals(false, defaultValue);
    }

    @Test
    public void testDefaultValueQueryTimeoutSeconds() throws Exception {
        Method m = Query.class.getDeclaredMethod("queryTimeoutSeconds");
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
