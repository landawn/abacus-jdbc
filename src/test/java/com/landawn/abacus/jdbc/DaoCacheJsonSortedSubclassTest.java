package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.CacheSerialization;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;

@Tag("integration")
@Tag("2025")
@Execution(ExecutionMode.SAME_THREAD)
class DaoCacheJsonSortedSubclassTest {
    private static final Map<String, Object> results = new LinkedHashMap<>();
    private static final Map<String, Integer> invocations = new LinkedHashMap<>();
    private static final Comparator<Object> sharedComparator = new StateComparator(new ConstructorState(Policy.ASCENDING));
    private static Policy constructionPolicy = Policy.ASCENDING;
    private static Object constructorSeed;
    private static com.zaxxer.hikari.HikariDataSource dataSource;
    private static CacheDao dao;

    @BeforeAll
    static void setUp() {
        dataSource = (com.zaxxer.hikari.HikariDataSource) JdbcUtil.createHikariDataSource("jdbc:h2:mem:json_sorted_subclass_review", "sa", "");
        dao = JdbcUtil.createDao(CacheDao.class, dataSource);
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
        results.clear();
        invocations.clear();
        constructorSeed = null;
        constructionPolicy = Policy.ASCENDING;
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

    private enum Policy {
        ASCENDING, REVERSED, DECIMAL_SCALE, DECIMAL_VALUE, SHARED_ASCENDING
    }

    private static final class ConstructorState {
        final Policy policy;
        String note = "constructed";
        Map<Object, Boolean> allowedValues;
        RuntimeException comparisonFailure;

        ConstructorState(final Policy policy) {
            this.policy = policy;
        }
    }

    // Each ordinary constructor owns its comparator state. A shallow clone would retain that owner,
    // while replacing the comparator with the source comparator would reconnect an otherwise fresh copy.
    private static final class StateComparator implements Comparator<Object> {
        final ConstructorState owner;

        StateComparator(final ConstructorState owner) {
            this.owner = owner;
        }

        @Override
        public int compare(final Object left, final Object right) {
            if (owner.allowedValues != null && (!owner.allowedValues.containsKey(left) || !owner.allowedValues.containsKey(right))) {
                throw owner.comparisonFailure;
            }
            return switch (owner.policy) {
                case DECIMAL_SCALE -> Integer.compare(((BigDecimal) left).scale(), ((BigDecimal) right).scale());
                case DECIMAL_VALUE -> ((BigDecimal) left).compareTo((BigDecimal) right);
                case REVERSED -> Long.compare(idOf(right), idOf(left));
                default -> Long.compare(idOf(left), idOf(right));
            };
        }
    }

    public static class FreshMap<K, V> extends TreeMap<K, V> {
        private static final long serialVersionUID = 1L;
        final ConstructorState constructorState;

        public FreshMap() {
            this(new ConstructorState(constructionPolicy));
        }

        @SuppressWarnings("unchecked")
        private FreshMap(final ConstructorState state) {
            super((Comparator<? super K>) (state.policy == Policy.SHARED_ASCENDING ? sharedComparator : new StateComparator(state)));
            constructorState = state;
            if (constructorSeed != null) {
                put((K) constructorSeed, (V) new Value(0, "constructor seed"));
            }
        }
    }

    public static class FreshSet<E> extends TreeSet<E> {
        private static final long serialVersionUID = 1L;
        final ConstructorState constructorState;

        public FreshSet() {
            this(new ConstructorState(constructionPolicy));
        }

        @SuppressWarnings("unchecked")
        private FreshSet(final ConstructorState state) {
            super((Comparator<? super E>) (state.policy == Policy.SHARED_ASCENDING ? sharedComparator : new StateComparator(state)));
            constructorState = state;
            if (constructorSeed != null) {
                add((E) constructorSeed);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public interface CacheDao extends NonUpdateCrudDao<Value, Long, CacheDao> {
        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default FreshMap<Long, Value> getMap(final String key) {
            return (FreshMap<Long, Value>) result(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default FreshSet<Value> getSet(final String key) {
            return (FreshSet<Value>) result(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default List<FreshMap<Long, Value>> getMapList(final String key) {
            return (List<FreshMap<Long, Value>>) result(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default List<FreshSet<Value>> getSetList(final String key) {
            return (List<FreshSet<Value>>) result(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default FreshMap<BigDecimal, Value> getDecimalMap(final String key) {
            return (FreshMap<BigDecimal, Value>) result(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default FreshSet<BigDecimal> getDecimalSet(final String key) {
            return (FreshSet<BigDecimal>) result(key);
        }
    }

    @TestFactory
    List<DynamicTest> equivalentFreshComparatorsRetainConstructorAndCacheIsolation() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final boolean map : List.of(true, false)) {
            for (final boolean nested : List.of(false, true)) {
                for (final int size : List.of(0, 1, 3)) {
                    final String key = "equivalent-" + family(map) + "-" + (nested ? "list-" : "root-") + size;
                    tests.add(dynamicTest(key, () -> checkEquivalentCopy(key, map, nested, size)));
                }
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> unchangedComparatorIdentityRemainsSupported() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final boolean map : List.of(true, false)) {
            final String key = "shared-" + family(map);
            tests.add(dynamicTest(key, () -> {
                configure(Policy.SHARED_ASCENDING, null);
                final Object source = ordinarySource(map, 2);
                results.put(key, source);
                final Supplier<?> call = ordinaryCall(key, map, false);
                assertSame(source, call.get());
                mutateValuesAndAddEntry(source);
                final Object copy = call.get();
                assertSame(sharedComparator, comparator(copy));
                assertOrdinaryContents(copy, 2);
                assertNotSame(state(source), state(copy));
                mutateValuesAndAddEntry(copy);
                assertOrdinaryContents(call.get(), 2);
                assertEquals(1, invocations.get(key));
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> emptyAndSingletonSnapshotsDoNotRequireUniversalComparatorEquivalence() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final boolean map : List.of(true, false)) {
            for (final int size : List.of(0, 1)) {
                final String key = "snapshot-" + family(map) + "-" + size;
                tests.add(dynamicTest(key, () -> {
                    configure(Policy.ASCENDING, null);
                    final Object source = ordinarySource(map, size);
                    results.put(key, source);
                    configure(Policy.REVERSED, null);
                    final Supplier<?> call = ordinaryCall(key, map, false);
                    assertSame(source, call.get());
                    final Object copy = call.get();
                    assertOrdinaryContents(copy, size);
                    final Object first = map ? 1L : new Value(1, "one");
                    final Object second = map ? 2L : new Value(2, "two");
                    assertTrue(comparator(source).compare(first, second) < 0);
                    assertTrue(comparator(copy).compare(first, second) > 0);
                    assertSame(state(copy), ((StateComparator) comparator(copy)).owner);
                    assertNotSame(state(source), state(copy));
                    assertEquals(1, invocations.get(key));
                }));
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> changedOrderOrCollapsedEntriesFailOnCacheWritesAndReads() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final boolean map : List.of(true, false)) {
            for (final boolean read : List.of(false, true)) {
                for (final boolean decimals : List.of(false, true)) {
                    final String key = "reject-" + family(map) + "-" + (read ? "read-" : "write-") + (decimals ? "size" : "order");
                    tests.add(dynamicTest(key, () -> checkRejectedCopy(key, map, read, decimals, false)));
                }
            }
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> constructorEntriesAreAllowedWhenTheyPreserveTheSnapshot() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final boolean map : List.of(true, false)) {
            final String key = "seed-compatible-" + family(map);
            tests.add(dynamicTest(key, () -> {
                configure(Policy.DECIMAL_SCALE, new BigDecimal("1.0"));
                final Object source = decimalSource(map);
                results.put(key, source);
                final Supplier<?> call = decimalCall(key, map);
                assertSame(source, call.get());
                final Object copy = call.get();
                assertDecimalContents(copy);
                assertNotSame(state(source), state(copy));
                clear(copy);
                assertDecimalContents(call.get());
                assertEquals(1, invocations.get(key));
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> constructorEntriesCannotMaskLostKeysWithTheSameSize() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final boolean map : List.of(true, false)) {
            for (final boolean read : List.of(false, true)) {
                final String key = "seed-reject-" + family(map) + "-" + (read ? "read" : "write");
                tests.add(dynamicTest(key, () -> checkRejectedCopy(key, map, read, true, true)));
            }
        }
        return tests;
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparisonFailureRetainsItsCauseAndDoesNotInstallACacheEntry() {
        final String key = "source-comparator-rejects-copy";
        configure(Policy.ASCENDING, null);
        final FreshSet<Value> source = (FreshSet<Value>) ordinarySource(false, 2);
        final ConstructorState state = source.constructorState;
        state.allowedValues = new IdentityHashMap<>();
        source.forEach(value -> state.allowedValues.put(value, Boolean.TRUE));
        state.comparisonFailure = new IllegalStateException("The source comparator accepts only its original values");
        results.put(key, source);

        final UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class, () -> dao.getSet(key));
        assertSame(state.comparisonFailure, failure.getCause());
        assertTrue(failure.getMessage().contains(FreshSet.class.getName()), failure::getMessage);
        assertEquals(1, invocations.get(key));

        state.allowedValues = null;
        assertSame(source, dao.getSet(key));
        assertOrdinaryContents(dao.getSet(key), 2);
        assertEquals(2, invocations.get(key));
    }

    private static void checkEquivalentCopy(final String key, final boolean map, final boolean nested, final int size) {
        configure(Policy.ASCENDING, null);
        final Object source = ordinarySource(map, size);
        final Object supplied = nested ? new ArrayList<>(List.of(source)) : source;
        results.put(key, supplied);
        final Supplier<?> call = ordinaryCall(key, map, nested);
        assertSame(supplied, call.get());
        mutateValuesAndAddEntry(source);
        state(source).note = "source state changed";
        if (nested) {
            ((List<?>) supplied).clear();
        }

        final Object returned = call.get();
        final Object copy = child(returned, nested);
        assertEquals(source.getClass(), copy.getClass());
        assertNotSame(source, copy);
        assertOrdinaryContents(copy, size);
        assertNotSame(comparator(source), comparator(copy));
        assertFalse(comparator(source).equals(comparator(copy)));
        assertNotSame(state(source), state(copy));
        assertEquals("constructed", state(copy).note);
        assertSame(state(copy), ((StateComparator) comparator(copy)).owner);

        mutateValuesAndAddEntry(copy);
        state(copy).note = "returned state changed";
        if (nested) {
            ((List<?>) returned).clear();
        }
        final Object next = child(call.get(), nested);
        assertOrdinaryContents(next, size);
        assertNotSame(state(copy), state(next));
        assertEquals("constructed", state(next).note);
        assertEquals("source state changed", state(source).note);
        assertSame(state(next), ((StateComparator) comparator(next)).owner);
        assertEquals(1, invocations.get(key));
    }

    private static void checkRejectedCopy(final String key, final boolean map, final boolean read, final boolean decimals, final boolean seed) {
        final Policy originalPolicy = decimals ? Policy.DECIMAL_SCALE : Policy.ASCENDING;
        configure(originalPolicy, null);
        final Object source = decimals ? decimalSource(map) : ordinarySource(map, 2);
        results.put(key, source);
        final Supplier<?> call = decimals ? decimalCall(key, map) : ordinaryCall(key, map, false);
        if (read) {
            assertSame(source, call.get());
        }

        // The extra scale-three key hides a collapse under a numeric comparator: both containers have
        // two ascending entries, but the source's scale-two key has been replaced by a different key.
        configure(decimals ? Policy.DECIMAL_VALUE : Policy.REVERSED, seed ? new BigDecimal("2.000") : null);
        final UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class, call::get);
        assertTrue(failure.getMessage().contains(decimals && !seed ? "changed the size" : "changed the order"), failure::getMessage);
        assertEquals(1, invocations.get(key));

        configure(originalPolicy, null);
        final Object recovered = call.get();
        final Object next = call.get();
        if (decimals) {
            assertDecimalContents(recovered);
            assertDecimalContents(next);
        } else {
            assertOrdinaryContents(recovered, 2);
            assertOrdinaryContents(next, 2);
        }
        // A failed write must not install a damaged entry; a failed read must leave the original entry intact.
        assertEquals(read ? 1 : 2, invocations.get(key));
    }

    private static Object result(final String key) {
        invocations.merge(key, 1, Integer::sum);
        return results.get(key);
    }

    private static void configure(final Policy policy, final Object seed) {
        constructionPolicy = policy;
        constructorSeed = seed;
    }

    private static String family(final boolean map) {
        return map ? "map" : "set";
    }

    private static long idOf(final Object value) {
        return value instanceof Value ? ((Value) value).id : ((Number) value).longValue();
    }

    private static Object ordinarySource(final boolean map, final int size) {
        if (map) {
            final FreshMap<Long, Value> source = new FreshMap<>();
            for (int i = size; i > 0; i--) {
                source.put((long) i, new Value(i, "v" + i));
            }
            return source;
        }
        final FreshSet<Value> source = new FreshSet<>();
        for (int i = size; i > 0; i--) {
            source.add(new Value(i, "v" + i));
        }
        return source;
    }

    private static Object decimalSource(final boolean map) {
        if (map) {
            final FreshMap<BigDecimal, Value> source = new FreshMap<>();
            source.put(new BigDecimal("1.0"), new Value(1, "v1"));
            source.put(new BigDecimal("1.00"), new Value(2, "v2"));
            return source;
        }
        final FreshSet<BigDecimal> source = new FreshSet<>();
        source.add(new BigDecimal("1.0"));
        source.add(new BigDecimal("1.00"));
        return source;
    }

    private static Supplier<?> ordinaryCall(final String key, final boolean map, final boolean nested) {
        return nested ? map ? () -> dao.getMapList(key) : () -> dao.getSetList(key) : map ? () -> dao.getMap(key) : () -> dao.getSet(key);
    }

    private static Supplier<?> decimalCall(final String key, final boolean map) {
        return map ? () -> dao.getDecimalMap(key) : () -> dao.getDecimalSet(key);
    }

    private static Object child(final Object result, final boolean nested) {
        if (nested) {
            assertEquals(1, ((List<?>) result).size());
            return ((List<?>) result).get(0);
        }
        return result;
    }

    private static ConstructorState state(final Object value) {
        return value instanceof FreshMap<?, ?> ? ((FreshMap<?, ?>) value).constructorState : ((FreshSet<?>) value).constructorState;
    }

    @SuppressWarnings("unchecked")
    private static Comparator<Object> comparator(final Object value) {
        return (Comparator<Object>) (value instanceof FreshMap<?, ?> ? ((FreshMap<?, ?>) value).comparator() : ((FreshSet<?>) value).comparator());
    }

    @SuppressWarnings("unchecked")
    private static void mutateValuesAndAddEntry(final Object value) {
        if (value instanceof FreshMap<?, ?>) {
            final FreshMap<Long, Value> map = (FreshMap<Long, Value>) value;
            map.values().forEach(item -> item.name = "changed");
            map.put(99L, new Value(99, "added"));
        } else {
            final FreshSet<Value> set = (FreshSet<Value>) value;
            set.forEach(item -> item.name = "changed");
            set.add(new Value(99, "added"));
        }
    }

    private static void assertOrdinaryContents(final Object value, final int size) {
        final Collection<?> elements = value instanceof Map<?, ?> ? ((Map<?, ?>) value).values() : (Collection<?>) value;
        assertEquals(size, elements.size());
        int id = 1;
        for (final Object element : elements) {
            final Value item = (Value) element;
            assertEquals(id, item.id);
            assertEquals("v" + id, item.name);
            id++;
        }
        if (value instanceof Map<?, ?>) {
            assertEquals(elements.stream().map(element -> ((Value) element).id).toList(), new ArrayList<>(((Map<?, ?>) value).keySet()));
        }
    }

    private static void assertDecimalContents(final Object value) {
        final Collection<?> keys = value instanceof Map<?, ?> ? ((Map<?, ?>) value).keySet() : (Collection<?>) value;
        assertEquals(List.of(new BigDecimal("1.0"), new BigDecimal("1.00")), new ArrayList<>(keys));
        if (value instanceof Map<?, ?>) {
            assertEquals("v1", ((Value) ((Map<?, ?>) value).get(new BigDecimal("1.0"))).name);
            assertEquals("v2", ((Value) ((Map<?, ?>) value).get(new BigDecimal("1.00"))).name);
        }
    }

    private static void clear(final Object value) {
        if (value instanceof Map<?, ?>) {
            ((Map<?, ?>) value).clear();
        } else {
            ((Collection<?>) value).clear();
        }
    }
}
