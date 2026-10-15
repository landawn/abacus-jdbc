package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.CacheSerialization;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.ImmutableNavigableMap;
import com.landawn.abacus.util.ImmutableNavigableSet;
import com.landawn.abacus.util.ImmutableSortedMap;
import com.landawn.abacus.util.ImmutableSortedSet;
import com.landawn.abacus.util.u;

@Tag("2025")
class DaoCacheJsonComparatorTest {
    private static final Map<String, Object> results = new LinkedHashMap<>();
    private static com.zaxxer.hikari.HikariDataSource dataSource;
    private static CacheDao dao;

    @BeforeAll
    static void setUp() {
        dataSource = (com.zaxxer.hikari.HikariDataSource) JdbcUtil.createHikariDataSource("jdbc:h2:mem:json_comparator_review", "sa", "");
        dao = JdbcUtil.createDao(CacheDao.class, dataSource);
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
        results.clear();
    }

    /** A custom collection the JSON parser can construct. */
    public static final class Names extends ArrayList<String> {
        private static final long serialVersionUID = 1L;

        public Names() {
        }
    }

    /** A custom collection without a default constructor, which the JSON parser cannot construct. */
    public static final class Csv extends ArrayList<String> {
        private static final long serialVersionUID = 1L;

        private Csv(final String csv) {
            addAll(List.of(csv.split(",")));
        }

        static Csv of(final String csv) {
            return new Csv(csv);
        }
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

    public static class Key {
        private int code;
        private String label;

        public Key() {
        }

        Key(final int code, final String label) {
            this.code = code;
            this.label = label;
        }

        public int getCode() {
            return code;
        }

        public void setCode(final int code) {
            this.code = code;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(final String label) {
            this.label = label;
        }
    }

    public static class BeanWithSortedProperty {
        private ImmutableSortedMap<Long, Value> items;

        public BeanWithSortedProperty() {
        }

        public ImmutableSortedMap<Long, Value> getItems() {
            return items;
        }

        public void setItems(final ImmutableSortedMap<Long, Value> items) {
            this.items = items;
        }
    }

    @SuppressWarnings("unchecked")
    public interface CacheDao extends NonUpdateCrudDao<Value, Long, CacheDao> {
        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default <K, V> ImmutableSortedMap<K, V> getSortedMap(final String key) {
            return (ImmutableSortedMap<K, V>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default <K, V> ImmutableNavigableMap<K, V> getNavigableMap(final String key) {
            return (ImmutableNavigableMap<K, V>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default <E> ImmutableSortedSet<E> getSortedSet(final String key) {
            return (ImmutableSortedSet<E>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default <E> ImmutableNavigableSet<E> getNavigableSet(final String key) {
            return (ImmutableNavigableSet<E>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableList<ImmutableSortedMap<Key, Value>> getNestedList(final String key) {
            return (ImmutableList<ImmutableSortedMap<Key, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableMap<String, List<ImmutableNavigableSet<Key>>> getNestedMap(final String key) {
            return (ImmutableMap<String, List<ImmutableNavigableSet<Key>>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default u.Optional<ImmutableNavigableMap<Key, Value>> getOptional(final String key) {
            return (u.Optional<ImmutableNavigableMap<Key, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.Optional<ImmutableSortedSet<Key>> getJdkOptional(final String key) {
            return (java.util.Optional<ImmutableSortedSet<Key>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default u.Nullable<ImmutableSortedMap<Key, Value>> getNullable(final String key) {
            return (u.Nullable<ImmutableSortedMap<Key, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableList<u.Optional<ImmutableSortedSet<Key>>> getNestedOptional(final String key) {
            return (ImmutableList<u.Optional<ImmutableSortedSet<Key>>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableList<Object> getHeterogeneous(final String key) {
            return (ImmutableList<Object>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Object[] getArray(final String key) {
            return (Object[]) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableList<Map<Long, Value>[]> getCovariantArrays(final String key) {
            return (ImmutableList<Map<Long, Value>[]>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default u.Optional<Object> getUnknownOptional(final String key) {
            return (u.Optional<Object>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        @SuppressWarnings("rawtypes")
        default u.Optional getRawOptional(final String key) {
            return (u.Optional) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableList<u.Nullable<ImmutableSortedSet<Key>>> getNestedNullable(final String key) {
            return (ImmutableList<u.Nullable<ImmutableSortedSet<Key>>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableList<java.util.Optional<ImmutableSortedSet<Key>>> getNestedJdkOptional(final String key) {
            return (ImmutableList<java.util.Optional<ImmutableSortedSet<Key>>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableMap<ImmutableSortedSet<Key>, Value> getSortedKeys(final String key) {
            return (ImmutableMap<ImmutableSortedSet<Key>, Value>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableList<BeanWithSortedProperty> getBeans(final String key) {
            return (ImmutableList<BeanWithSortedProperty>) results.get(key);
        }

        default ImmutableSortedMap<Key, Value> getThreadScopeFallback() {
            return (ImmutableSortedMap<Key, Value>) results.get("threadScope");
        }
    }

    private enum Family {
        SORTED_MAP, NAVIGABLE_MAP, SORTED_SET, NAVIGABLE_SET;

        boolean isMap() {
            return this == SORTED_MAP || this == NAVIGABLE_MAP;
        }

        Object get(final String key) {
            return switch (this) {
                case SORTED_MAP -> dao.getSortedMap(key);
                case NAVIGABLE_MAP -> dao.getNavigableMap(key);
                case SORTED_SET -> dao.getSortedSet(key);
                case NAVIGABLE_SET -> dao.getNavigableSet(key);
            };
        }

        <K> Object wrap(final List<K> keys, final Comparator<? super K> comparator) {
            if (isMap()) {
                final TreeMap<K, Value> map = new TreeMap<>(comparator);
                keys.forEach(key -> map.put(key, new Value(1, "original")));
                return this == SORTED_MAP ? ImmutableSortedMap.wrap(map) : ImmutableNavigableMap.wrap(map);
            }
            final TreeSet<K> set = new TreeSet<>(comparator);
            set.addAll(keys);
            return this == SORTED_SET ? ImmutableSortedSet.wrap(set) : ImmutableNavigableSet.wrap(set);
        }
    }

    @TestFactory
    List<DynamicTest> preservesComparatorsForAllSortedFamilies() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Family family : Family.values()) {
            tests.add(dynamicTest(family + " reverse", () -> {
                final Comparator<Long> comparator = Comparator.reverseOrder();
                final Object copy = cached(family, family + "reverse", family.wrap(List.of(1L, 2L), comparator));
                assertEquals(List.of(2L, 1L), orderedKeys(copy));
                assertSame(comparator, comparator(copy));
            }));
            tests.add(dynamicTest(family + " non-Comparable and isolation", () -> {
                final Comparator<Key> comparator = Comparator.comparingInt(Key::getCode).reversed();
                final Key first = new Key(1, "one");
                final Key second = new Key(2, "two");
                final Object source = family.wrap(List.of(first, second), comparator);
                final String key = family + "nonComparable";
                results.put(key, source);
                assertSame(source, family.get(key));
                second.label = "changed after write";
                if (source instanceof Map<?, ?> map) {
                    ((Value) map.values().iterator().next()).name = "changed after write";
                }
                final Object copy = family.get(key);
                assertSame(comparator, comparator(copy));
                assertEquals(List.of(2, 1), orderedKeys(copy).stream().map(value -> ((Key) value).code).toList());
                final Key copiedKey = (Key) orderedKeys(copy).get(0);
                assertNotSame(second, copiedKey);
                assertEquals("two", copiedKey.label);
                copiedKey.label = "changed after read";
                if (copy instanceof Map<?, ?> map) {
                    final Value copiedValue = (Value) map.values().iterator().next();
                    assertEquals("original", copiedValue.name);
                    copiedValue.name = "changed after read";
                    assertEquals("original", ((Value) ((Map<?, ?>) family.get(key)).values().iterator().next()).name);
                }
                assertEquals("two", ((Key) orderedKeys(family.get(key)).get(0)).label);
                if (copy instanceof Collection<?> collection) {
                    assertThrows(UnsupportedOperationException.class, collection::clear);
                } else {
                    assertThrows(UnsupportedOperationException.class, ((Map<?, ?>) copy)::clear);
                }
            }));
            tests.add(dynamicTest(family + " comparator-distinct cardinality", () -> {
                final Comparator<BigDecimal> comparator = Comparator.comparingInt(BigDecimal::scale);
                final Object copy = cached(family, family + "cardinality", family.wrap(List.of(new BigDecimal("1.0"), new BigDecimal("1.00")), comparator));
                assertEquals(2, orderedKeys(copy).size());
                assertEquals(List.of(1, 2), orderedKeys(copy).stream().map(value -> ((BigDecimal) value).scale()).toList());
            }));
            tests.add(dynamicTest(family + " empty and null-aware", () -> {
                final Comparator<Key> comparator = Comparator.nullsFirst(Comparator.comparingInt(Key::getCode));
                final Object empty = cached(family, family + "empty", family.wrap(List.<Key> of(), comparator));
                assertSame(comparator, comparator(empty));
                assertEquals(0, orderedKeys(empty).size());
                final Object copy = cached(family, family + "null", family.wrap(java.util.Arrays.asList(null, new Key(1, "one")), comparator));
                assertSame(comparator, comparator(copy));
                assertEquals(2, orderedKeys(copy).size());
                assertNull(orderedKeys(copy).get(0));
                final Object onlyNull = cached(family, family + "onlyNull", family.wrap(java.util.Collections.singletonList(null), comparator));
                assertSame(comparator, comparator(onlyNull));
                assertEquals(1, orderedKeys(onlyNull).size());
                assertNull(orderedKeys(onlyNull).get(0));
                final Comparator<String> nullFirst = Comparator.nullsFirst(Comparator.naturalOrder());
                final Object nullAndString = cached(family, family + "nullAndString", family.wrap(java.util.Arrays.asList(null, "null"), nullFirst));
                assertEquals(java.util.Arrays.asList(null, "null"), orderedKeys(nullAndString));
            }));
        }
        return tests;
    }

    @Test
    void preservesNestedListMapAndDeclaredGenericValues() {
        final ImmutableSortedMap<Key, Value> source = keyMap();
        results.put("nestedList", ImmutableList.of(source));
        dao.getNestedList("nestedList");
        source.get(source.firstKey()).name = "changed";
        final ImmutableSortedMap<Key, Value> copy = dao.getNestedList("nestedList").get(0);
        assertSame(source.comparator(), copy.comparator());
        assertEquals("original", copy.get(copy.firstKey()).name);
        assertNotSame(source.firstKey(), copy.firstKey());
    }

    @Test
    void preservesNestedMapListSetAndEmptyComparator() {
        final Comparator<Key> comparator = Comparator.comparingInt(Key::getCode).reversed();
        final TreeSet<Key> set = new TreeSet<>(comparator);
        set.add(new Key(1, "one"));
        final ImmutableNavigableSet<Key> source = ImmutableNavigableSet.wrap(set);
        results.put("nestedMap", ImmutableMap.of("sets", new ArrayList<>(List.of(source, ImmutableNavigableSet.wrap(new TreeSet<>(comparator))))));
        dao.getNestedMap("nestedMap");
        source.first().label = "changed";
        final List<ImmutableNavigableSet<Key>> copy = dao.getNestedMap("nestedMap").get("sets");
        assertSame(comparator, copy.get(0).comparator());
        assertSame(comparator, copy.get(1).comparator());
        assertEquals("one", copy.get(0).first().label);
    }

    @Test
    void preservesOptionalNullableAndNestedOptionalContainers() {
        final ImmutableSortedMap<Key, Value> source = keyMap();
        final TreeMap<Key, Value> navigable = new TreeMap<>(source.comparator());
        navigable.putAll(source);
        results.put("optional", u.Optional.of(ImmutableNavigableMap.wrap(navigable)));
        results.put("nullable", u.Nullable.of(source));
        final TreeSet<Key> keys = new TreeSet<>(source.comparator());
        keys.addAll(source.keySet());
        results.put("jdkOptional", java.util.Optional.of(ImmutableSortedSet.wrap(keys)));
        results.put("nestedOptional", ImmutableList.of(u.Optional.of(ImmutableSortedSet.wrap(keys))));
        dao.getOptional("optional");
        dao.getNullable("nullable");
        dao.getJdkOptional("jdkOptional");
        dao.getNestedOptional("nestedOptional");
        source.get(source.firstKey()).name = "changed";
        source.firstKey().label = "changed";
        assertSame(source.comparator(), dao.getOptional("optional").get().comparator());
        assertEquals("original", dao.getOptional("optional").get().firstEntry().getValue().name);
        assertSame(source.comparator(), dao.getNullable("nullable").get().comparator());
        assertSame(source.comparator(), dao.getJdkOptional("jdkOptional").get().comparator());
        assertSame(source.comparator(), dao.getNestedOptional("nestedOptional").get(0).get().comparator());
        assertEquals("two", dao.getJdkOptional("jdkOptional").get().first().label);
        assertEquals("two", dao.getNestedOptional("nestedOptional").get(0).get().first().label);
    }

    @Test
    void retainsEmptyAndPresentNullHolderSemantics() {
        results.put("absent", u.Optional.empty());
        results.put("jdkAbsent", java.util.Optional.empty());
        results.put("presentNull", u.Nullable.of(null));
        assertEquals(false, dao.getOptional("absent").isPresent());
        assertEquals(false, dao.getOptional("absent").isPresent());
        assertEquals(false, dao.getJdkOptional("jdkAbsent").isPresent());
        assertEquals(true, dao.getNullable("presentNull").isPresent());
        assertNull(dao.getNullable("presentNull").get());
    }

    @Test
    void preservesOrdinaryHeterogeneousJsonValuesAlongsideSortedContainers() {
        results.put("heterogeneous", ImmutableList.of(1L, "text", keyMap()));
        dao.getHeterogeneous("heterogeneous");
        final ImmutableList<Object> copy = dao.getHeterogeneous("heterogeneous");
        assertEquals(Integer.class, copy.get(0).getClass());
        assertEquals(1, copy.get(0));
        assertEquals("text", copy.get(1));
        assertEquals(2, ((SortedMap<?, ?>) copy.get(2)).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesSortedContainersInObjectArraysWithoutPromotingOtherValues() {
        final ImmutableSortedMap<Key, Value> child = keyMap();
        results.put("array", new Object[] { 1L, "text", null, child, new Object[] { 2L, child } });
        dao.getArray("array");
        child.get(child.firstKey()).name = "changed after write";
        final Object[] copy = dao.getArray("array");
        assertEquals(Object[].class, copy.getClass());
        assertEquals(Integer.class, copy[0].getClass());
        assertEquals("text", copy[1]);
        assertNull(copy[2]);
        final List<?> untypedArrayCopy = (List<?>) copy[4];
        assertEquals(Integer.class, untypedArrayCopy.get(0).getClass());
        assertEquals(2, untypedArrayCopy.get(0));
        assertSame(child.comparator(), ((SortedMap<?, ?>) untypedArrayCopy.get(1)).comparator());
        final SortedMap<Key, Map<String, Object>> copiedChild = (SortedMap<Key, Map<String, Object>>) copy[3];
        assertSame(child.comparator(), copiedChild.comparator());
        assertEquals("original", copiedChild.get(copiedChild.firstKey()).get("name"));
        copiedChild.get(copiedChild.firstKey()).put("name", "changed after read");
        final SortedMap<Key, Map<String, Object>> next = (SortedMap<Key, Map<String, Object>>) dao.getArray("array")[3];
        assertEquals("original", next.get(next.firstKey()).get("name"));

        final TreeMap<Long, Value> typedMap = new TreeMap<>(Comparator.reverseOrder());
        typedMap.put(1L, new Value(1, "typed"));
        final ImmutableSortedMap<Long, Value>[] covariant = new ImmutableSortedMap[] { ImmutableSortedMap.wrap(typedMap) };
        results.put("covariantArray", ImmutableList.wrap(java.util.Collections.singletonList(covariant)));
        dao.getCovariantArrays("covariantArray");
        typedMap.get(1L).name = "changed";
        final Map<Long, Value>[] typedCopy = dao.getCovariantArrays("covariantArray").get(0);
        assertEquals(Map[].class, typedCopy.getClass());
        assertSame(typedMap.comparator(), ((SortedMap<?, ?>) typedCopy[0]).comparator());
        assertEquals("typed", typedCopy[0].get(1L).name);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rootArraysParseUnresolvedElementsAsTheirRuntimeComponentType() {
        // The root copy keeps the runtime TreeMap[] component type, so the natural-order member must come
        // back as a TreeMap (not the JSON default Map) to be storable next to its comparator-bearing sibling.
        final TreeMap<Long, Value> reverse = new TreeMap<>(Comparator.reverseOrder());
        reverse.put(1L, new Value(1, "reverse"));
        reverse.put(2L, new Value(2, "reverse"));
        final TreeMap<Long, Value> natural = new TreeMap<>();
        natural.put(1L, new Value(1, "natural"));
        natural.put(2L, new Value(2, "natural"));
        results.put("treeMapArray", new TreeMap[] { reverse, natural });
        dao.getArray("treeMapArray");
        reverse.get(1L).name = "changed after write";
        natural.get(1L).name = "changed after write";
        final Object[] copy = dao.getArray("treeMapArray");
        assertEquals(TreeMap[].class, copy.getClass());
        // Element key/value types are unresolved through Object[], so values keep the JSON map defaults (as in
        // preservesSortedContainersInObjectArraysWithoutPromotingOtherValues); the comparator-bearing member
        // resolves its key type from the runtime keys, the natural-order member keeps the parser's default keys.
        final TreeMap<Object, Map<String, Object>> copiedReverse = (TreeMap<Object, Map<String, Object>>) copy[0];
        final TreeMap<Object, Map<String, Object>> copiedNatural = (TreeMap<Object, Map<String, Object>>) copy[1];
        assertSame(reverse.comparator(), copiedReverse.comparator());
        assertNull(copiedNatural.comparator());
        assertEquals(List.of(2L, 1L), new ArrayList<>(copiedReverse.keySet()));
        assertEquals(List.of("1", "2"), copiedNatural.keySet().stream().map(String::valueOf).toList());
        assertNotSame(Long.class, copiedNatural.firstKey().getClass());
        assertEquals("reverse", copiedReverse.get(1L).get("name"));
        assertEquals("natural", copiedNatural.firstEntry().getValue().get("name"));
        copiedNatural.firstEntry().getValue().put("name", "changed after read");
        assertEquals("natural", ((TreeMap<Object, Map<String, Object>>) dao.getArray("treeMapArray")[1]).firstEntry().getValue().get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unresolvedHolderValuesRetainJsonMapValueDefaults() {
        final TreeMap<Long, Value> child = new TreeMap<>(Comparator.reverseOrder());
        child.put(1L, new Value(1, "original"));
        results.put("unknownOptional", u.Optional.of(ImmutableSortedMap.wrap(child)));
        dao.getUnknownOptional("unknownOptional");
        child.get(1L).name = "changed after write";
        final SortedMap<Long, Map<String, Object>> copy = (SortedMap<Long, Map<String, Object>>) dao.getUnknownOptional("unknownOptional").get();
        assertSame(child.comparator(), copy.comparator());
        assertEquals("original", copy.get(1L).get("name"));
        assertEquals(Integer.class, copy.get(1L).get("id").getClass());
        copy.get(1L).put("name", "changed after read");
        final SortedMap<Long, Map<String, Object>> next = (SortedMap<Long, Map<String, Object>>) dao.getUnknownOptional("unknownOptional").get();
        assertEquals("original", next.get(1L).get("name"));
    }

    @Test
    void unresolvedHoldersCopyPlainValuesThroughTheirRuntimeClass() {
        // Without a sorted container the holder takes the plain held-value copy, which has no untyped fallback: a
        // raw holder declares nothing and an Object type would read the value back as its JSON String.
        final Names names = new Names();
        names.add("a");
        results.put("rawNames", u.Optional.of(names));
        results.put("unknownNames", u.Optional.of(names));
        dao.getRawOptional("rawNames");
        dao.getUnknownOptional("unknownNames");
        names.add("added after write");
        for (final Object copy : new Object[] { dao.getRawOptional("rawNames").get(), dao.getUnknownOptional("unknownNames").get() }) {
            assertEquals(Names.class, copy.getClass());
            assertEquals(List.of("a"), copy);
        }

        // A container the parser cannot construct still fails with the parser's own diagnostic naming the class,
        // not a NullPointerException (raw holder) or a String copy (Object holder).
        results.put("rawCsv", u.Optional.of(Csv.of("a,b")));
        results.put("unknownCsv", u.Optional.of(Csv.of("a,b")));
        for (final Runnable read : new Runnable[] { () -> dao.getRawOptional("rawCsv"), () -> dao.getUnknownOptional("unknownCsv") }) {
            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, read::run);
            assertTrue(failure.getMessage().contains("No default constructor"), failure.getMessage());
            assertTrue(failure.getMessage().contains("Csv"), failure.getMessage());
        }
    }

    @Test
    void nullAndEmptyHolderSiblingsRetainJsonElementSemantics() {
        final TreeSet<Key> keys = new TreeSet<>(Comparator.comparingInt(Key::getCode).reversed());
        keys.add(new Key(1, "one"));
        final ImmutableSortedSet<Key> child = ImmutableSortedSet.wrap(keys);
        results.put("optionalSiblings", ImmutableList.wrap(java.util.Arrays.asList(null, u.Optional.empty(), u.Optional.of(child))));
        results.put("nullableSiblings", ImmutableList.wrap(java.util.Arrays.asList(null, u.Nullable.empty(), u.Nullable.of(null), u.Nullable.of(child))));
        results.put("jdkOptionalSiblings", ImmutableList.wrap(java.util.Arrays.asList(null, java.util.Optional.empty(), java.util.Optional.of(child))));
        dao.getNestedOptional("optionalSiblings");
        dao.getNestedNullable("nullableSiblings");
        dao.getNestedJdkOptional("jdkOptionalSiblings");
        final ImmutableList<u.Optional<ImmutableSortedSet<Key>>> optionalCopy = dao.getNestedOptional("optionalSiblings");
        assertEquals(false, optionalCopy.get(0).isPresent());
        assertEquals(false, optionalCopy.get(1).isPresent());
        assertSame(keys.comparator(), optionalCopy.get(2).get().comparator());
        final ImmutableList<u.Nullable<ImmutableSortedSet<Key>>> nullableCopy = dao.getNestedNullable("nullableSiblings");
        assertEquals(false, nullableCopy.get(0).isPresent());
        assertEquals(false, nullableCopy.get(1).isPresent());
        assertEquals(false, nullableCopy.get(2).isPresent());
        assertSame(keys.comparator(), nullableCopy.get(3).get().comparator());
        final ImmutableList<java.util.Optional<ImmutableSortedSet<Key>>> jdkCopy = dao.getNestedJdkOptional("jdkOptionalSiblings");
        assertEquals(false, jdkCopy.get(0).isPresent());
        assertEquals(false, jdkCopy.get(1).isPresent());
        assertSame(keys.comparator(), jdkCopy.get(2).get().comparator());
    }

    @Test
    void preservesSortedContainersUsedAsMapKeys() {
        final TreeSet<Key> keys = new TreeSet<>(Comparator.comparingInt(Key::getCode).reversed());
        keys.add(new Key(1, "one"));
        keys.add(new Key(2, "two"));
        final ImmutableSortedSet<Key> child = ImmutableSortedSet.wrap(keys);
        final Value value = new Value(1, "original");
        results.put("sortedKeys", ImmutableMap.of(child, value));
        dao.getSortedKeys("sortedKeys");
        keys.first().label = "changed after write";
        value.name = "changed after write";
        final ImmutableMap<ImmutableSortedSet<Key>, Value> copy = dao.getSortedKeys("sortedKeys");
        final ImmutableSortedSet<Key> copiedKeys = copy.keySet().iterator().next();
        assertSame(keys.comparator(), copiedKeys.comparator());
        assertEquals("two", copiedKeys.first().label);
        assertEquals("original", copy.values().iterator().next().name);
        copiedKeys.first().label = "changed after read";
        copy.values().iterator().next().name = "changed after read";
        final ImmutableMap<ImmutableSortedSet<Key>, Value> next = dao.getSortedKeys("sortedKeys");
        assertEquals("two", next.keySet().iterator().next().first().label);
        assertEquals("original", next.values().iterator().next().name);
    }

    @Test
    void leavesBeanPropertySerializationWithTheExistingJsonParser() {
        final BeanWithSortedProperty bean = new BeanWithSortedProperty();
        final TreeMap<Long, Value> map = new TreeMap<>(Comparator.reverseOrder());
        map.put(1L, new Value(1, "one"));
        map.put(2L, new Value(2, "two"));
        bean.items = ImmutableSortedMap.wrap(map);
        results.put("beanProperty", ImmutableList.of(bean));
        dao.getBeans("beanProperty");
        final BeanWithSortedProperty copy = dao.getBeans("beanProperty").get(0);
        assertNull(copy.items.comparator());
        assertEquals(1L, copy.items.firstKey());
        assertNotSame(bean, copy);
    }

    static void verifyNoKryoThreadScope() {
        assertEquals(false, com.landawn.abacus.parser.ParserFactory.isKryoParserAvailable());
        setUp();
        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            final ImmutableSortedMap<Key, Value> source = keyMap();
            results.put("threadScope", source);
            dao.getThreadScopeFallback();
            source.get(source.firstKey()).name = "changed";
            final ImmutableSortedMap<Key, Value> copy = dao.getThreadScopeFallback();
            assertSame(source.comparator(), copy.comparator());
            assertEquals("original", copy.get(copy.firstKey()).name);
        } finally {
            tearDown();
        }
    }

    private static ImmutableSortedMap<Key, Value> keyMap() {
        final TreeMap<Key, Value> result = new TreeMap<>(Comparator.comparingInt(Key::getCode).reversed());
        result.put(new Key(1, "one"), new Value(1, "original"));
        result.put(new Key(2, "two"), new Value(2, "original"));
        return ImmutableSortedMap.wrap(result);
    }

    private static Object cached(final Family family, final String key, final Object source) {
        results.put(key, source);
        family.get(key);
        return family.get(key);
    }

    private static List<?> orderedKeys(final Object value) {
        return value instanceof Map<?, ?> map ? new ArrayList<>(map.keySet()) : new ArrayList<>((Collection<?>) value);
    }

    private static Comparator<?> comparator(final Object value) {
        return value instanceof SortedMap<?, ?> map ? map.comparator() : ((SortedSet<?>) value).comparator();
    }
}
