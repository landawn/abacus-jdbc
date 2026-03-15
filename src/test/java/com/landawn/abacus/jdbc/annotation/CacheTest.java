package com.landawn.abacus.jdbc.annotation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;

public class CacheTest extends TestBase {

    @Test
    public void testRetentionPolicy() {
        Retention retention = Cache.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    public void testTarget() {
        Target target = Cache.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertEquals(1, target.value().length);
        assertEquals(ElementType.TYPE, target.value()[0]);
    }

    @Test
    public void testDefaultCapacity() throws Exception {
        assertEquals(JdbcUtil.DEFAULT_CACHE_CAPACITY, Cache.class.getMethod("capacity").getDefaultValue());
    }

    @Test
    public void testDefaultEvictDelay() throws Exception {
        assertEquals((long) JdbcUtil.DEFAULT_CACHE_EVICT_DELAY, Cache.class.getMethod("evictDelay").getDefaultValue());
    }

    @Test
    public void testDefaultImpl() throws Exception {
        assertEquals(Jdbc.DefaultDaoCache.class, Cache.class.getMethod("impl").getDefaultValue());
    }

    @Test
    public void testIsAnnotation() {
        assertTrue(Cache.class.isAnnotation());
    }
}
