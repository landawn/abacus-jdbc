package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.JdbcUtil;

public class CacheResultTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = CacheResult.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = CacheResult.class.getAnnotation(Target.class);
        assertNotNull(target);
        Set<ElementType> types = new HashSet<>(Arrays.asList(target.value()));
        assertEquals(2, types.size());
        assertTrue(types.contains(ElementType.METHOD));
        assertTrue(types.contains(ElementType.TYPE));
    }

    @Test
    public void testDefaultDisabled() throws Exception {
        assertEquals(false, CacheResult.class.getMethod("disabled").getDefaultValue());
    }

    @Test
    public void testDefaultLiveTime() throws Exception {
        assertEquals((long) JdbcUtil.DEFAULT_CACHE_LIVE_TIME, CacheResult.class.getMethod("liveTime").getDefaultValue());
    }

    @Test
    public void testDefaultMaxIdleTime() throws Exception {
        assertEquals((long) JdbcUtil.DEFAULT_CACHE_MAX_IDLE_TIME, CacheResult.class.getMethod("maxIdleTime").getDefaultValue());
    }

    @Test
    public void testDefaultMinSize() throws Exception {
        assertEquals(0, CacheResult.class.getMethod("minSize").getDefaultValue());
    }

    @Test
    public void testDefaultMaxSize() throws Exception {
        assertEquals(Integer.MAX_VALUE, CacheResult.class.getMethod("maxSize").getDefaultValue());
    }

    @Test
    public void testDefaultTransfer() throws Exception {
        assertEquals("none", CacheResult.class.getMethod("transfer").getDefaultValue());
    }

    @Test
    public void testDefaultFilter() throws Exception {
        String[] expected = { "query", "queryFor", "list", "get", "batchGet", "find", "findFirst", "findOnlyOne", "exist", "notExist", "count" };
        assertArrayEquals(expected, (String[]) CacheResult.class.getMethod("filter").getDefaultValue());
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(CacheResult.class.isAnnotation());
    }
}
