package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.CacheSerialization;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.u;

/**
 * Regression tests for JSON cache copies of maps holding a {@code null} key. JSON writes every map key as a quoted
 * string, so such a key used to come back from the cache as the String {@code "null"} ({@code get(null)} then missed
 * and the copy was not equal to the result), and a non-String key type such as {@code Integer} made the first call
 * fail with {@code NumberFormatException} while copying the successful result into the cache. Only sorted maps with a
 * null-friendly comparator were handled.
 *
 * <p>Each test returns the source result on the first call, then replaces the source, so the second call proves it was
 * served (copied) from the cache.</p>
 */
@Tag("integration")
@Tag("2025")
class DaoCacheJsonNullMapKeyTest {
    private static final Map<String, Object> results = new HashMap<>();
    private static com.zaxxer.hikari.HikariDataSource dataSource;
    private static CacheDao dao;

    @BeforeAll
    static void setUp() {
        dataSource = (com.zaxxer.hikari.HikariDataSource) JdbcUtil.createHikariDataSource("jdbc:h2:mem:json_null_map_key", "sa", "");
        dao = JdbcUtil.createDao(CacheDao.class, dataSource);
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
        results.clear();
    }

    public static class Value {
        @Id
        private long id;
        private String name;

        public Value() {
        }

        Value(final long id, final String name) {
            this.id = id;
            this.name = name;
        }

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    @SuppressWarnings("unchecked")
    public interface CacheDao extends NonUpdateCrudDao<Value, Long, CacheDao> {
        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Map<String, Integer> getStringKeyed(final String key) {
            return (Map<String, Integer>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Map<Integer, String> getIntegerKeyed(final String key) {
            return (Map<Integer, String>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default <K, V> Map<K, V> getUntyped(final String key) {
            return (Map<K, V>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default LinkedHashMap<String, Value> getLinkedValues(final String key) {
            return (LinkedHashMap<String, Value>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableMap<String, Integer> getImmutable(final String key) {
            return (ImmutableMap<String, Integer>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default List<Map<Long, String>> getNested(final String key) {
            return (List<Map<Long, String>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default u.Optional<Map<Integer, String>> getOptional(final String key) {
            return (u.Optional<Map<Integer, String>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default TreeMap<String, Integer> getSorted(final String key) {
            return (TreeMap<String, Integer>) results.get(key);
        }
    }

    /**
     * Returns the first (source) and the second (cached) result, replacing the source in between.
     */
    private static <T> List<T> firstAndCached(final String key, final T source, final Supplier<T> call) {
        results.put(key, source);
        final T first = call.get();
        results.put(key, null);
        final T cached = call.get();
        assertNotSame(source, cached);
        return List.of(first, cached);
    }

    @Test
    void stringKeyedMapKeepsTheNullKey() {
        final Map<String, Integer> source = new HashMap<>();
        source.put(null, 1);
        source.put("a", 2);

        final Map<String, Integer> cached = firstAndCached("string", source, () -> dao.getStringKeyed("string")).get(1);

        assertEquals(source, cached);
        assertTrue(cached.containsKey(null));
        assertEquals(1, cached.get(null));
        assertFalse(cached.containsKey("null"));
    }

    // The first call used to fail with NumberFormatException: the successful result could not be copied into the cache.
    @Test
    void integerKeyedMapIsCachedInsteadOfFailingTheFirstCall() {
        final Map<Integer, String> source = new HashMap<>();
        source.put(null, "none");
        source.put(7, "seven");

        final List<Map<Integer, String>> calls = firstAndCached("integer", source, () -> dao.getIntegerKeyed("integer"));

        assertSame(source, calls.get(0));
        assertEquals(source, calls.get(1));
        assertEquals("none", calls.get(1).get(null));
        assertEquals("seven", calls.get(1).get(7));
    }

    @Test
    void mapWhoseOnlyKeyIsNullKeepsIt() {
        final Map<String, Integer> source = new HashMap<>();
        source.put(null, 1);

        assertEquals(source, firstAndCached("nullOnly", source, () -> dao.getStringKeyed("nullOnly")).get(1));
        assertEquals(source, firstAndCached("nullOnlyUntyped", source, () -> dao.<String, Integer> getUntyped("nullOnlyUntyped")).get(1));
    }

    @Test
    void untypedMapKeepsTheNullKeyAndInfersTheOtherKeys() {
        final Map<Long, String> source = new HashMap<>();
        source.put(null, "none");
        source.put(5L, "five");

        final Map<Long, String> cached = firstAndCached("untyped", source, () -> dao.<Long, String> getUntyped("untyped")).get(1);

        assertEquals(source, cached);
        assertEquals("five", cached.get(5L));
    }

    // Entry-by-entry copying keeps the source's iteration order and the declared value type.
    @Test
    void linkedMapKeepsEntryOrderAndValueTypes() {
        final LinkedHashMap<String, Value> source = new LinkedHashMap<>();
        source.put("first", new Value(1, "one"));
        source.put(null, new Value(2, "two"));
        source.put("last", new Value(3, "three"));

        final LinkedHashMap<String, Value> cached = firstAndCached("linked", source, () -> dao.getLinkedValues("linked")).get(1);

        assertEquals(new ArrayList<>(source.keySet()), new ArrayList<>(cached.keySet()));
        assertInstanceOf(Value.class, cached.get(null));
        assertEquals("two", cached.get(null).getName());
        assertNotSame(source.get(null), cached.get(null));
    }

    @Test
    void immutableMapKeepsTheNullKeyAndItsOrder() {
        final LinkedHashMap<String, Integer> backing = new LinkedHashMap<>();
        backing.put("b", 2);
        backing.put(null, 0);
        backing.put("a", 1);
        final ImmutableMap<String, Integer> source = ImmutableMap.wrap(backing);

        final ImmutableMap<String, Integer> cached = firstAndCached("immutable", source, () -> dao.getImmutable("immutable")).get(1);

        assertEquals(source, cached);
        assertEquals(new ArrayList<>(backing.keySet()), new ArrayList<>(cached.keySet()));
    }

    @Test
    void nestedMapKeepsTheNullKeyAndItsDeclaredKeyType() {
        final Map<Long, String> child = new HashMap<>();
        child.put(null, "none");
        child.put(3L, "three");

        final List<Map<Long, String>> cached = firstAndCached("nested", new ArrayList<>(List.of(child)), () -> dao.getNested("nested")).get(1);

        assertEquals(List.of(child), cached);
        assertEquals("three", cached.get(0).get(3L));
    }

    @Test
    void mapHeldByAnOptionalKeepsTheNullKey() {
        final Map<Integer, String> held = new HashMap<>();
        held.put(null, "none");
        held.put(1, "one");

        final u.Optional<Map<Integer, String>> cached = firstAndCached("optional", u.Optional.of(held), () -> dao.getOptional("optional")).get(1);

        assertEquals(held, cached.get());
    }

    // Previously the only supported null-key case; it must keep working.
    @Test
    void sortedMapWithNullFriendlyComparatorKeepsTheNullKeyAndComparator() {
        final TreeMap<String, Integer> source = new TreeMap<>(Comparator.nullsFirst(Comparator.<String> reverseOrder()));
        source.put("a", 1);
        source.put(null, 0);
        source.put("b", 2);

        final TreeMap<String, Integer> cached = firstAndCached("sorted", source, () -> dao.getSorted("sorted")).get(1);

        assertEquals(source, cached);
        assertSame(source.comparator(), cached.comparator());
        assertEquals(new ArrayList<>(source.keySet()), new ArrayList<>(cached.keySet()));
    }
}
