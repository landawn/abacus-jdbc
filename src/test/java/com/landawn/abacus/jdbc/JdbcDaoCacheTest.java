package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple3;

class JdbcDaoCacheTest extends JdbcTestSupport {

    @Test
    public void testRowExtractorBuilderDoesNotCacheMetadataAfterOutputValidationFails() throws SQLException {
        when(mockResultSetMetaData.getColumnCount()).thenReturn(3, 1);
        when(mockResultSet.getObject(1)).thenReturn("value");
        final Jdbc.RowExtractor extractor = Jdbc.RowExtractor.builder().build();

        assertThrows(IllegalArgumentException.class, () -> extractor.accept(mockResultSet, new Object[1]));
        final Object[] output = new Object[1];
        extractor.accept(mockResultSet, output);

        assertEquals("value", output[0]);
        verify(mockResultSetMetaData, times(2)).getColumnCount();
    }

    // DaoCache Tests
    @Test
    public void testDaoCacheCreate() {
        Jdbc.DaoCache cache = Jdbc.DaoCache.create(100, 1000);
        assertNotNull(cache);
        assertTrue(cache instanceof Jdbc.DefaultDaoCache);
    }

    @Test
    public void testCacheUpdateValidatesMetadataBeforeInvalidatingEntries() {
        final Method builtInMethod = JdbcUtil.BUILT_IN_DAO_UPDATE_METHODS.iterator().next();
        final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> missingReturnType = Tuple.of(builtInMethod, ImmutableList.empty(), null);

        for (final Jdbc.DaoCache cache : List.of(Jdbc.DaoCache.createByMap(), Jdbc.DaoCache.create(10, 0))) {
            cache.put("method#users#1", "cached", null, null, null);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> cache.update("method#users#2", 1, null, null, null)).getMessage()
                    .contains("methodSignature"));
            assertThrows(IllegalArgumentException.class, () -> cache.update("method#users#2", 0, null, null, missingReturnType));
            assertEquals("cached", cache.get("method#users#1", null, null, null));
            assertFalse(cache.put("method#users#null", null, null, null, null));
            cache.clear();
        }
    }

    @Test
    public void testDaoCacheFactoriesPropagateInvalidCapacityAndEvictionDelay() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.DaoCache.create(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> Jdbc.DaoCache.create(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new Jdbc.DefaultDaoCache(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Jdbc.DefaultDaoCache(1, -1));
        assertThrows(IllegalArgumentException.class, () -> Jdbc.DaoCache.createByMap(-1));
        assertThrows(IllegalArgumentException.class, () -> new Jdbc.DaoCacheByMap(-1));
    }

    @Test
    public void testDaoCacheLifetimeValidationPrecedesNullResultShortCircuit() {
        final Jdbc.DaoCache pooled = Jdbc.DaoCache.create(2, 0);
        assertThrows(IllegalArgumentException.class, () -> pooled.put("key", null, 0, -1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> pooled.put("key", "value", 0, 1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> pooled.put("key", "value", 1, 0, null, null, null));
        assertNull(pooled.get("key", null, null, null));

        final Jdbc.DaoCache mapped = Jdbc.DaoCache.createByMap();
        assertTrue(mapped.put("key", "value", 0, -1, null, null, null));
        assertEquals("value", mapped.get("key", null, null, null));
    }

    @Test
    public void testMapBackedCachePropagatesUnsupportedMutationOnlyWhenNeeded() {
        final Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap(Map.of("existing", "value"));
        assertFalse(cache.put("key", null, null, null, null));
        assertFalse(cache.put("key", null, -1, -1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> cache.put(null, "value", null, null, null));
        assertThrows(UnsupportedOperationException.class, () -> cache.put("key", "value", null, null, null));
        assertThrows(UnsupportedOperationException.class, () -> cache.put("key", "value", 1, 1, null, null, null));
        assertThrows(UnsupportedOperationException.class, cache::clear);
        assertEquals("value", cache.get("existing", null, null, null));
    }

    @Test
    public void testDaoCacheCreateByMap() {
        Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap();
        assertNotNull(cache);
        // Verify the factory produces a DaoCacheByMap instance
        assertTrue(cache instanceof Jdbc.DaoCacheByMap);
        // Verify cache starts empty (get returns null for unknown key)
        assertNull(cache.get("nonexistent", null, null, null));
    }

    @Test
    public void testDaoCacheCreateByMapWithMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("preloaded", "value1");
        Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap(map);
        assertNotNull(cache);
        assertTrue(cache instanceof Jdbc.DaoCacheByMap);
        // Verify the cache is backed by the provided map and can see pre-existing entries
        assertEquals("value1", cache.get("preloaded", null, null, null));
    }

    @Test
    public void testDaoCacheCreateByMapWithNullMap() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.DaoCache.createByMap(null));
        assertThrows(IllegalArgumentException.class, () -> new Jdbc.DaoCacheByMap(null));
    }

    @Test
    public void testDaoCacheByMapConstructors() {
        // Default constructor: empty cache backed by ConcurrentHashMap
        Jdbc.DaoCacheByMap cache1 = new Jdbc.DaoCacheByMap();
        assertNotNull(cache1);
        assertTrue(cache1.cache().isEmpty());

        // Capacity constructor: empty cache with initial capacity
        Jdbc.DaoCacheByMap cache2 = new Jdbc.DaoCacheByMap(50);
        assertNotNull(cache2);
        assertTrue(cache2.cache().isEmpty());

        // Map constructor: cache backed by the provided map
        Map<String, Object> map = new HashMap<>();
        map.put("key1", "val1");
        Jdbc.DaoCacheByMap cache3 = new Jdbc.DaoCacheByMap(map);
        assertNotNull(cache3);
        // Verify the backing map is the same instance and contains the pre-loaded entry
        assertSame(map, cache3.cache());
        assertEquals(1, cache3.cache().size());
        assertEquals("val1", cache3.cache().get("key1"));
    }

    // DaoCacheByMap Tests
    @Test
    public void testDaoCacheByMapGetPut() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        String cacheKey = "testKey";
        Object result = "testResult";

        // Initially should be null
        assertNull(cache.get(cacheKey, null, null, methodSignature));

        // Put and get
        assertTrue(cache.put(cacheKey, result, null, null, methodSignature));
        assertEquals(result, cache.get(cacheKey, null, null, methodSignature));
    }

    // Bug fix: DaoCacheByMap.put() should reject null cache keys consistently with DefaultDaoCache.
    @Tag("2025")
    @Test
    public void testDaoCacheByMapPut_NullKey_ThrowsIAE() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        assertThrows(IllegalArgumentException.class, () -> cache.put(null, "value", null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapPutNullResult() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        // null result should return false and not be stored
        assertFalse(cache.put("key1", null, null, null, methodSignature));
        assertNull(cache.get("key1", null, null, methodSignature));

        // null result with TTL should return false too
        assertFalse(cache.put("key2", null, 5000, 3000, null, null, methodSignature));
        assertNull(cache.get("key2", null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapPutWithTTL() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        String cacheKey = "testKey";
        Object result = "testResult";

        // TTL is ignored in map implementation
        assertTrue(cache.put(cacheKey, result, 5000, 3000, null, null, methodSignature));
        assertEquals(result, cache.get(cacheKey, null, null, methodSignature));
    }

    @Tag("2025")
    @Test
    public void testDaoCacheByMapPutWithTTL_NullKey_ThrowsIAE() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        assertThrows(IllegalArgumentException.class, () -> cache.put(null, "value", 5000L, 3000L, null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapUpdate() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        // Put some entries
        cache.put("method#users#params1", "result1", null, null, methodSignature);
        cache.put("method#products#params2", "result2", null, null, methodSignature);
        cache.put("method#users#params3", "result3", null, null, methodSignature);

        // Update with table name
        cache.update("method#users#updateParams", 1, null, null, methodSignature);

        // Users entries should be removed
        assertNull(cache.get("method#users#params1", null, null, methodSignature));
        assertNull(cache.get("method#users#params3", null, null, methodSignature));
        // Products entry should remain
        assertEquals("result2", cache.get("method#products#params2", null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapUpdate_OnlyInvalidatesExactTableSegment() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        cache.put("method#users#params1", "users", null, null, methodSignature);
        cache.put("method#superusers#params2", "superusers", null, null, methodSignature);
        cache.put("method#orders#[\"users\"]", "orders", null, null, methodSignature);

        cache.update("method#users#updateParams", 1, null, null, methodSignature);

        assertNull(cache.get("method#users#params1", null, null, methodSignature));
        assertEquals("superusers", cache.get("method#superusers#params2", null, null, methodSignature));
        assertEquals("orders", cache.get("method#orders#[\"users\"]", null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapUpdate_EmptyTableName() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        cache.put("entry1", "val1", null, null, methodSignature);
        cache.put("entry2", "val2", null, null, methodSignature);

        // key without '#' → empty table name → entire cache cleared
        cache.update("simplekey", 1, null, null, methodSignature);

        assertNull(cache.get("entry1", null, null, methodSignature));
        assertNull(cache.get("entry2", null, null, methodSignature));
    }

    @Test
    public void testDaoCacheByMapUpdate_BuiltInUpdateMethod_ZeroResult() throws Exception {
        Jdbc.DaoCacheByMap cache = new Jdbc.DaoCacheByMap();

        // Get an actual method from BUILT_IN_DAO_UPDATE_METHODS
        Method builtInUpdateMethod = JdbcUtil.BUILT_IN_DAO_UPDATE_METHODS.stream()
                .filter(m -> m.getName().equals("update") && m.getParameterCount() == 1)
                .findFirst()
                .orElse(null);

        if (builtInUpdateMethod != null) {
            ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
            Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(builtInUpdateMethod, paramTypes, int.class);

            cache.put("method#users#params", "cached", null, null, methodSignature);

            // result=0 with BUILT_IN_DAO_UPDATE_METHODS method → early return, cache not cleared
            cache.update("method#users#update", 0, null, null, methodSignature);

            // Cache should NOT be cleared (early return occurred)
            assertEquals("cached", cache.get("method#users#params", null, null, methodSignature));
        }
    }

    // DefaultDaoCache Tests
    @Test
    public void testDefaultDaoCacheGetPut() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        String cacheKey = "testKey";
        Object result = "testResult";

        // Initially should be null
        assertNull(cache.get(cacheKey, null, null, methodSignature));

        // Put and get
        assertTrue(cache.put(cacheKey, result, null, null, methodSignature));
        assertEquals(result, cache.get(cacheKey, null, null, methodSignature));
    }

    // Regression: DefaultDaoCache.put used to accept null result, but a cached null is
    // indistinguishable from a miss under the sentinel-free get() contract — so the slot was
    // wasted and the underlying method got re-invoked on every call. DaoCacheByMap already
    // rejected null result; this brings the default impl in line.
    @Tag("2025")
    @Test
    public void testDefaultDaoCachePut_NullResultRejected() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(1000, 3000);

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        // Both overloads must return false (rejected) for a null result.
        assertFalse(cache.put("key1", null, null, null, methodSignature));
        assertFalse(cache.put("key2", null, 5000L, 3000L, null, null, methodSignature));

        // Subsequent get must return null (true "miss"), not a wrapped null sentinel.
        assertNull(cache.get("key1", null, null, methodSignature));
        assertNull(cache.get("key2", null, null, methodSignature));

        // Sanity: a non-null result IS stored.
        assertTrue(cache.put("key3", "the-value", null, null, methodSignature));
        assertEquals("the-value", cache.get("key3", null, null, methodSignature));
    }

    @Test
    public void testDefaultDaoCachePutWithTTL() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        String cacheKey = "testKey";
        Object result = "testResult";

        assertTrue(cache.put(cacheKey, result, 5000, 3000, null, null, methodSignature));
        assertEquals(result, cache.get(cacheKey, null, null, methodSignature));
    }

    @Test
    public void testDefaultDaoCacheUpdate() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        // Put some entries
        cache.put("method#users#params1", "result1", null, null, methodSignature);
        cache.put("method#products#params2", "result2", null, null, methodSignature);
        cache.put("method#users#params3", "result3", null, null, methodSignature);

        // Update with table name
        cache.update("method#users#updateParams", 1, null, null, methodSignature);

        // Users entries should be removed
        assertNull(cache.get("method#users#params1", null, null, methodSignature));
        assertNull(cache.get("method#users#params3", null, null, methodSignature));
        // Products entry should remain
        assertEquals("result2", cache.get("method#products#params2", null, null, methodSignature));
    }

    @Test
    public void testDefaultDaoCacheUpdate_OnlyInvalidatesExactTableSegment() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        cache.put("method#users#params1", "users", null, null, methodSignature);
        cache.put("method#superusers#params2", "superusers", null, null, methodSignature);
        cache.put("method#orders#[\"users\"]", "orders", null, null, methodSignature);

        cache.update("method#users#updateParams", 1, null, null, methodSignature);

        assertNull(cache.get("method#users#params1", null, null, methodSignature));
        assertEquals("superusers", cache.get("method#superusers#params2", null, null, methodSignature));
        assertEquals("orders", cache.get("method#orders#[\"users\"]", null, null, methodSignature));
    }

    @Test
    public void testDefaultDaoCacheUpdate_EmptyTableName() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        Method method = Object.class.getMethods()[0];
        ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
        Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, paramTypes, Object.class);

        cache.put("entry1", "val1", null, null, methodSignature);
        cache.put("entry2", "val2", null, null, methodSignature);

        // key without '#' → empty table name → entire pool cleared
        cache.update("simplekey", 1, null, null, methodSignature);

        assertNull(cache.get("entry1", null, null, methodSignature));
        assertNull(cache.get("entry2", null, null, methodSignature));
    }

    @Test
    public void testDefaultDaoCacheUpdate_BuiltInUpdateMethod_ZeroResult() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        // Get an actual method from BUILT_IN_DAO_UPDATE_METHODS (update method from CrudDao)
        Method builtInUpdateMethod = JdbcUtil.BUILT_IN_DAO_UPDATE_METHODS.stream()
                .filter(m -> m.getName().equals("update") && m.getParameterCount() == 1)
                .findFirst()
                .orElse(null);

        if (builtInUpdateMethod != null) {
            ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
            // Return type is int.class
            Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(builtInUpdateMethod, paramTypes, int.class);

            cache.put("method#users#params", "cached", null, null, methodSignature);

            // result=0 with BUILT_IN_DAO_UPDATE_METHODS method → early return, cache not cleared
            cache.update("method#users#update", 0, null, null, methodSignature);

            // Cache should NOT be cleared (early return occurred)
            assertEquals("cached", cache.get("method#users#params", null, null, methodSignature));
        }
    }

    @Test
    public void testDefaultDaoCacheUpdate_BuiltInUpdateMethod_NonZeroResult() throws Exception {
        Jdbc.DefaultDaoCache cache = new Jdbc.DefaultDaoCache(100, 1000);

        // Get an actual method from BUILT_IN_DAO_UPDATE_METHODS
        Method builtInUpdateMethod = JdbcUtil.BUILT_IN_DAO_UPDATE_METHODS.stream()
                .filter(m -> m.getName().equals("update") && m.getParameterCount() == 1)
                .findFirst()
                .orElse(null);

        if (builtInUpdateMethod != null) {
            ImmutableList<Class<?>> paramTypes = ImmutableList.empty();
            Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(builtInUpdateMethod, paramTypes, int.class);

            cache.put("method#users#params", "cached", null, null, methodSignature);

            // result=1 (non-zero) → does NOT early return → cache is cleared
            cache.update("method#users#update", 1, null, null, methodSignature);

            // Cache should be cleared
            assertNull(cache.get("method#users#params", null, null, methodSignature));
        }
    }
}
