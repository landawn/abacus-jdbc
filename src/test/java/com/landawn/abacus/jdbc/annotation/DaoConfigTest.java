package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class DaoConfigTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = DaoConfig.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = DaoConfig.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(ElementType.TYPE, target.value()[0]);
    }

    @Test
    public void testDefaultAddLimitForSingleQuery() throws Exception {
        assertEquals(false, DaoConfig.class.getMethod("addLimitForSingleQuery").getDefaultValue());
    }

    @Test
    public void testDefaultCallGenerateIdForInsertIfIdNotSet() throws Exception {
        assertEquals(false, DaoConfig.class.getMethod("callGenerateIdForInsertIfIdNotSet").getDefaultValue());
    }

    @Test
    public void testDefaultCallGenerateIdForInsertWithSqlIfIdNotSet() throws Exception {
        assertEquals(false, DaoConfig.class.getMethod("callGenerateIdForInsertWithSqlIfIdNotSet").getDefaultValue());
    }

    @Test
    public void testDefaultAllowJoiningByNullOrDefaultValue() throws Exception {
        assertEquals(false, DaoConfig.class.getMethod("allowJoiningByNullOrDefaultValue").getDefaultValue());
    }

    @Test
    public void testDefaultFetchColumnByEntityClassForDatasetQuery() throws Exception {
        assertEquals(true, DaoConfig.class.getMethod("fetchColumnByEntityClassForDatasetQuery").getDefaultValue());
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(DaoConfig.class.isAnnotation());
    }
}
