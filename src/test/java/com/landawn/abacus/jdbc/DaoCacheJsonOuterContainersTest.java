package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.function.Supplier;

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
import com.landawn.abacus.util.ImmutableBiMap;
import com.landawn.abacus.util.ImmutableCollection;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.ImmutableSet;
import com.landawn.abacus.util.ImmutableSortedMap;
import com.landawn.abacus.util.ImmutableSortedSet;

@Tag("integration")
@Tag("2025")
class DaoCacheJsonOuterContainersTest {
    private static final Map<String, Object> results = new LinkedHashMap<>();
    private static com.zaxxer.hikari.HikariDataSource dataSource;
    private static CacheDao dao;

    @BeforeAll
    static void setUp() {
        dataSource = (com.zaxxer.hikari.HikariDataSource) JdbcUtil.createHikariDataSource("jdbc:h2:mem:json_outer_containers_review", "sa", "");
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

    public static class NaturalValueMap extends java.util.TreeMap<Long, Value> {
        private static final long serialVersionUID = 1L;

        public NaturalValueMap() {
        }
    }

    public static class NaturalLongSet extends java.util.TreeSet<Long> {
        private static final long serialVersionUID = 1L;

        public NaturalLongSet() {
        }
    }

    public static class NaturalParentMap extends java.util.TreeMap<Long, SortedMap<Long, Value>> {
        private static final long serialVersionUID = 1L;

        public NaturalParentMap() {
        }
    }

    @SuppressWarnings("unchecked")
    public interface CacheDao extends NonUpdateCrudDao<Value, Long, CacheDao> {
        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableCollection<ImmutableSortedMap<Long, Value>> getCollection(final String key) {
            return (ImmutableCollection<ImmutableSortedMap<Long, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableSet<ImmutableSortedMap<Long, Value>> getSet(final String key) {
            return (ImmutableSet<ImmutableSortedMap<Long, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableBiMap<String, ImmutableSortedMap<Long, Value>> getBiMap(final String key) {
            return (ImmutableBiMap<String, ImmutableSortedMap<Long, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ArrayList<ImmutableSortedMap<Long, Value>> getList(final String key) {
            return (ArrayList<ImmutableSortedMap<Long, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default LinkedHashMap<String, ImmutableSortedMap<Long, Value>> getMap(final String key) {
            return (LinkedHashMap<String, ImmutableSortedMap<Long, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableMap<String, Object> getObjects(final String key) {
            return (ImmutableMap<String, Object>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableMap<Object, Object> getUntypedKeys(final String key) {
            return (ImmutableMap<Object, Object>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableCollection<SortedMap<Long, Value>> getMapViews(final String key) {
            return (ImmutableCollection<SortedMap<Long, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableMap<String, SortedSet<Long>> getSetViews(final String key) {
            return (ImmutableMap<String, SortedSet<Long>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableCollection<List<SortedMap<Long, Value>>> getListParents(final String key) {
            return (ImmutableCollection<List<SortedMap<Long, Value>>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableCollection<Map<String, SortedMap<Long, Value>>> getMapParents(final String key) {
            return (ImmutableCollection<Map<String, SortedMap<Long, Value>>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.TreeMap<Long, Value> getNaturalMap(final String key) {
            return (java.util.TreeMap<Long, Value>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.TreeSet<Long> getNaturalSet(final String key) {
            return (java.util.TreeSet<Long>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.TreeMap<Long, SortedMap<Long, Value>> getNaturalParent(final String key) {
            return (java.util.TreeMap<Long, SortedMap<Long, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableCollection<ImmutableMap<Long, Value>> getBaseMaps(final String key) {
            return (ImmutableCollection<ImmutableMap<Long, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableCollection<ImmutableSet<Long>> getBaseSets(final String key) {
            return (ImmutableCollection<ImmutableSet<Long>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableCollection<ImmutableCollection<Long>> getBaseCollections(final String key) {
            return (ImmutableCollection<ImmutableCollection<Long>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.concurrent.ConcurrentSkipListMap<Long, Value> getConcurrentMap(final String key) {
            return (java.util.concurrent.ConcurrentSkipListMap<Long, Value>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.concurrent.ConcurrentSkipListSet<Value> getConcurrentSet(final String key) {
            return (java.util.concurrent.ConcurrentSkipListSet<Value>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableCollection<java.util.concurrent.ConcurrentMap<Long, Value>> getConcurrentMapChildren(final String key) {
            return (ImmutableCollection<java.util.concurrent.ConcurrentMap<Long, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableCollection<java.util.concurrent.ConcurrentNavigableMap<Long, Value>> getConcurrentNavigableChildren(final String key) {
            return (ImmutableCollection<java.util.concurrent.ConcurrentNavigableMap<Long, Value>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Object[] getUntypedArray(final String key) {
            return (Object[]) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default List<Object> getUntypedList(final String key) {
            return (List<Object>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Status getStatus(final String key) {
            return (Status) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Object getAny(final String key) {
            return results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default List<Value> getValues(final String key) {
            return (List<Value>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Set<Long> getLongSet(final String key) {
            return (Set<Long>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Map<String, Long> getCounts(final String key) {
            return (Map<String, Long>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default SortedMap<Long, Value> getSortedValues(final String key) {
            return (SortedMap<Long, Value>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default List<java.sql.Timestamp> getTimestamps(final String key) {
            return (List<java.sql.Timestamp>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.EnumSet<Status> getStatusSet(final String key) {
            return (java.util.EnumSet<Status>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default SortedSet<Long> getSortedLongs(final String key) {
            return (SortedSet<Long>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Map<String, Long> getEmptyCounts(final String key) {
            return (Map<String, Long>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Op getOp(final String key) {
            return (Op) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.Queue<String> getQueue(final String key) {
            return (java.util.Queue<String>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default com.landawn.abacus.util.u.Optional<Object> getHeldOptional(final String key) {
            return com.landawn.abacus.util.u.Optional.ofNullable(results.get(key));
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default com.landawn.abacus.util.u.Nullable<Object> getHeldNullable(final String key) {
            return com.landawn.abacus.util.u.Nullable.of(results.get(key));
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.Optional<java.util.EnumSet<Status>> getHeldStatusSet(final String key) {
            return java.util.Optional.ofNullable((java.util.EnumSet<Status>) results.get(key));
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default <T> java.util.Optional<T> getHeldJdkOptional(final String key) {
            return java.util.Optional.ofNullable((T) results.get(key));
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Map<Status, Long> getStatusCounts(final String key) {
            return (Map<Status, Long>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.EnumMap<Status, List<Value>> getStatusValues(final String key) {
            return (java.util.EnumMap<Status, List<Value>>) results.get(key);
        }
    }

    public enum Status {
        ACTIVE, INACTIVE
    }

    public enum Op {
        PLUS {
            @Override
            long apply(final long a, final long b) {
                return a + b;
            }
        };

        abstract long apply(long a, long b);
    }

    public enum Kind {
        PLAIN, SPECIAL {
            @Override
            int weight() {
                return 2;
            }
        };

        int weight() {
            return 1;
        }
    }

    @TestFactory
    List<DynamicTest> sortedChildrenRetainTheirOuterContainerFamilies() {
        return List.of(dynamicTest("ImmutableCollection", () -> {
            final ImmutableSortedMap<Long, Value> child = sortedChild();
            check("collection", ImmutableCollection.wrap(new ArrayList<>(List.of(child))), ImmutableCollection.class, true,
                    () -> dao.getCollection("collection"));
        }), dynamicTest("ImmutableSet", () -> {
            final ImmutableSortedMap<Long, Value> child = sortedChild();
            check("set", ImmutableSet.wrap(new LinkedHashSet<>(List.of(child))), ImmutableSet.class, true, () -> dao.getSet("set"));
        }), dynamicTest("ImmutableBiMap", () -> {
            final ImmutableSortedMap<Long, Value> child = sortedChild();
            check("biMap", ImmutableBiMap.of("child", child), ImmutableBiMap.class, true, () -> dao.getBiMap("biMap"));
            final ImmutableBiMap<String, ImmutableSortedMap<Long, Value>> copy = dao.getBiMap("biMap");
            assertEquals("child", copy.inverse().get(copy.get("child")));
        }), dynamicTest("ArrayList", () -> check("list", new ArrayList<>(List.of(sortedChild())), ArrayList.class, false, () -> dao.getList("list"))),
                dynamicTest("LinkedHashMap", () -> {
                    final LinkedHashMap<String, ImmutableSortedMap<Long, Value>> source = new LinkedHashMap<>();
                    source.put("child", sortedChild());
                    check("map", source, LinkedHashMap.class, false, () -> dao.getMap("map"));
                }));
    }

    @Test
    @SuppressWarnings("unchecked")
    void heterogeneousObjectValuesKeepJsonDefaultsAlongsideASortedChild() {
        final ImmutableSortedMap<Long, Value> child = sortedChild();
        results.put("objects", ImmutableMap.of("number", 1L, "text", "text", "child", child));
        dao.getObjects("objects");
        child.get(2L).name = "changed after write";
        final ImmutableMap<String, Object> copy = dao.getObjects("objects");
        assertEquals(Integer.class, copy.get("number").getClass());
        assertEquals(1, copy.get("number"));
        assertEquals("text", copy.get("text"));
        final ImmutableSortedMap<Long, Map<String, Object>> copiedChild = (ImmutableSortedMap<Long, Map<String, Object>>) copy.get("child");
        assertSame(child.comparator(), copiedChild.comparator());
        assertEquals("original", copiedChild.get(2L).get("name"));
        assertThrows(UnsupportedOperationException.class, copy::clear);
        copiedChild.get(2L).put("name", "changed after read");
        final ImmutableSortedMap<Long, Map<String, Object>> nextChild = (ImmutableSortedMap<Long, Map<String, Object>>) dao.getObjects("objects").get("child");
        assertEquals("original", nextChild.get(2L).get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void untypedRootsRebuildUniformPrivateContainerViewsFromTheirRuntimeShape() {
        // Every element shares a JDK view class without a default constructor. Naming that class as the inferred
        // element type fails the copy ("No default constructor found"); the views must be rebuilt as sorted copies.
        final java.util.TreeMap<Long, Value> reversed = new java.util.TreeMap<>(Comparator.reverseOrder());
        reversed.putAll(sortedChild());
        // putAll rather than the copy constructor, which would inherit the child's reverse comparator.
        final java.util.TreeMap<Long, Value> natural = new java.util.TreeMap<>();
        natural.putAll(sortedChild());

        results.put("unmodifiableArray",
                new Object[] { java.util.Collections.unmodifiableSortedMap(reversed), java.util.Collections.unmodifiableSortedMap(natural) });
        dao.getUntypedArray("unmodifiableArray");
        final Object[] arrayCopy = dao.getUntypedArray("unmodifiableArray");
        assertSame(Object[].class, arrayCopy.getClass());
        final SortedMap<Long, Map<String, Object>> reversedCopy = (SortedMap<Long, Map<String, Object>>) arrayCopy[0];
        assertSame(reversed.comparator(), reversedCopy.comparator());
        assertEquals(List.of(2L, 1L), new ArrayList<>(reversedCopy.keySet()));
        assertEquals("original", reversedCopy.get(2L).get("name"));
        // The natural-order view carries no comparator, so it keeps the JSON default container of an Object slot.
        final Map<String, Map<String, Object>> naturalCopy = (Map<String, Map<String, Object>>) arrayCopy[1];
        assertEquals(List.of("1", "2"), new ArrayList<>(naturalCopy.keySet()));
        assertEquals("original", naturalCopy.get("2").get("name"));
        reversedCopy.get(2L).put("name", "changed after read");
        assertEquals("original", ((SortedMap<Long, Map<String, Object>>) dao.getUntypedArray("unmodifiableArray")[0]).get(2L).get("name"));

        results.put("descendingArray", new Object[] { natural.descendingMap(), new java.util.TreeMap<>(natural).descendingMap() });
        dao.getUntypedArray("descendingArray");
        final Object[] descendingCopy = dao.getUntypedArray("descendingArray");
        for (final Object element : descendingCopy) {
            final SortedMap<Long, Map<String, Object>> view = (SortedMap<Long, Map<String, Object>>) element;
            assertSame(natural.descendingMap().comparator(), view.comparator());
            assertEquals(List.of(2L, 1L), new ArrayList<>(view.keySet()));
        }
        assertNotSame(descendingCopy[0], descendingCopy[1]);

        results.put("factoryListArray", new Object[] { List.of(reversed), List.of(new java.util.TreeMap<>(reversed)) });
        dao.getUntypedArray("factoryListArray");
        for (final Object element : dao.getUntypedArray("factoryListArray")) {
            final List<SortedMap<Long, Map<String, Object>>> list = (List<SortedMap<Long, Map<String, Object>>>) element;
            assertEquals(1, list.size());
            assertSame(reversed.comparator(), list.get(0).comparator());
            assertEquals(List.of(2L, 1L), new ArrayList<>(list.get(0).keySet()));
        }

        results.put("unmodifiableList",
                new ArrayList<>(List.of(java.util.Collections.unmodifiableSortedMap(reversed), java.util.Collections.unmodifiableSortedMap(natural))));
        dao.getUntypedList("unmodifiableList");
        final List<Object> listCopy = dao.getUntypedList("unmodifiableList");
        assertEquals(2, listCopy.size());
        final SortedMap<Long, Map<String, Object>> listElement = (SortedMap<Long, Map<String, Object>>) listCopy.get(0);
        assertSame(reversed.comparator(), listElement.comparator());
        assertEquals(List.of(2L, 1L), new ArrayList<>(listElement.keySet()));
        assertEquals("original", ((Map<String, Map<String, Object>>) listCopy.get(1)).get("2").get("name"));
    }

    @Test
    void untypedRootsRebuildUniformContainersTheParserCannotConstructFromTheirRuntimeShape() {
        // Collections.emptyList()/emptyMap()/emptySet() have a no-argument constructor, but a private one of java.base that the
        // parser cannot call; an anonymous container class it rejects. Naming such a class as the inferred element type failed
        // the copy, and so the call, after the method had returned its result.
        results.put("emptyLists", new ArrayList<>(List.of(java.util.Collections.emptyList(), java.util.Collections.emptyList())));
        assertEquals(List.of(List.of(), List.of()), dao.getUntypedList("emptyLists"));
        results.put("emptyLists", new ArrayList<>());
        assertEquals(List.of(List.of(), List.of()), dao.getUntypedList("emptyLists"), "served from the cache");

        results.put("emptyMaps", new ArrayList<>(List.of(java.util.Collections.emptyMap(), java.util.Collections.emptyMap())));
        assertEquals(List.of(Map.of(), Map.of()), dao.getUntypedList("emptyMaps"));
        assertEquals(List.of(Map.of(), Map.of()), dao.getUntypedList("emptyMaps"));

        // A plain ImmutableMap root is copied as a tree, which infers its value type from the values the same way.
        // The first call returns the method's own result (empty sets); the cached copy reads them back as untyped lists.
        results.put("emptySets", ImmutableMap.of("a", java.util.Collections.emptySet(), "b", java.util.Collections.emptySet()));
        assertEquals(Map.of("a", Set.of(), "b", Set.of()), new LinkedHashMap<>(dao.getObjects("emptySets")));
        assertEquals(Map.of("a", List.of(), "b", List.of()), new LinkedHashMap<>(dao.getObjects("emptySets")));

        // The cached copy reads the untyped numbers back as JSON's default Integer.
        results.put("anonymousLists", new ArrayList<>(List.of(anonymousList(), anonymousList())));
        assertEquals(List.of(List.of(1L), List.of(1L)), dao.getUntypedList("anonymousLists"));
        assertEquals(List.of(List.of(1), List.of(1)), dao.getUntypedList("anonymousLists"));
    }

    @Test
    void rootEnumIsServedFromTheCache() {
        // The parser writes a root enum quoted but read it back with its quotes ("No enum constant ...\"ACTIVE\""), which
        // failed every call of the method after it had returned its result.
        results.put("status", Status.ACTIVE);
        assertSame(Status.ACTIVE, dao.getStatus("status"));
        results.put("status", null);
        assertSame(Status.ACTIVE, dao.getStatus("status"), "served from the cache");

        results.put("anyStatus", Status.INACTIVE);
        assertSame(Status.INACTIVE, dao.getAny("anyStatus"));
        results.put("anyStatus", null);
        assertSame(Status.INACTIVE, dao.getAny("anyStatus"), "served from the cache");
    }

    @Test
    void rootsTheParserCannotConstructAreRebuiltAsDefaultContainersOfTheirKind() {
        // List.of, Stream.toList(), Arrays.asList, Collections.unmodifiable* and EnumSet classes have no constructor the parser can
        // call: naming them as the class of the copy failed every call of the method after it had returned its result.
        final List<Supplier<List<Value>>> lists = List.of(() -> List.of(new Value(1, "a"), new Value(2, "b")),
                () -> java.util.stream.Stream.of(new Value(1, "a"), new Value(2, "b")).toList(),
                () -> java.util.Arrays.asList(new Value(1, "a"), new Value(2, "b")),
                () -> java.util.Collections.unmodifiableList(new ArrayList<>(List.of(new Value(1, "a"), new Value(2, "b")))));

        for (int i = 0; i < lists.size(); i++) {
            final String key = "list" + i;
            final List<Value> source = lists.get(i).get();
            results.put(key, source);
            assertSame(source, dao.getValues(key));
            results.put(key, null);
            final List<Value> copy = dao.getValues(key);
            assertSame(ArrayList.class, copy.getClass(), key);
            assertEquals(List.of(1L, 2L), copy.stream().map(Value::getId).toList(), key);
            assertEquals("b", copy.get(1).getName(), key);
        }

        results.put("set", java.util.Collections.unmodifiableSet(new LinkedHashSet<>(List.of(3L, 1L, 2L))));
        dao.getLongSet("set");
        results.put("set", null);
        final Set<Long> setCopy = dao.getLongSet("set");
        assertSame(LinkedHashSet.class, setCopy.getClass());
        assertEquals(List.of(3L, 1L, 2L), new ArrayList<>(setCopy), "keeps the source's iteration order");

        results.put("enumSet", java.util.EnumSet.of(Status.INACTIVE));
        dao.getAny("enumSet");
        results.put("enumSet", null);
        assertEquals(new LinkedHashSet<>(List.of(Status.INACTIVE)), dao.getAny("enumSet"));

        final Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("z", 1L);
        counts.put("a", 2L);
        results.put("map", java.util.Collections.unmodifiableMap(counts));
        dao.getCounts("map");
        results.put("map", null);
        final Map<String, Long> mapCopy = dao.getCounts("map");
        assertSame(LinkedHashMap.class, mapCopy.getClass());
        assertEquals(List.of("z", "a"), new ArrayList<>(mapCopy.keySet()));
        assertEquals(2L, mapCopy.get("a"));

        results.put("mapOf", Map.of("k", 5L));
        dao.getCounts("mapOf");
        results.put("mapOf", null);
        assertEquals(Map.of("k", 5L), dao.getCounts("mapOf"));

        // Copied as a tree (a comparator, a Timestamp): the root container class is chosen the same way.
        final java.util.TreeMap<Long, Value> reversed = new java.util.TreeMap<>(Comparator.reverseOrder());
        reversed.put(1L, new Value(1, "a"));
        reversed.put(2L, new Value(2, "b"));
        results.put("sorted", java.util.Collections.unmodifiableSortedMap(reversed));
        dao.getSortedValues("sorted");
        results.put("sorted", null);
        final SortedMap<Long, Value> sortedCopy = dao.getSortedValues("sorted");
        assertSame(java.util.TreeMap.class, sortedCopy.getClass());
        assertSame(reversed.comparator(), sortedCopy.comparator());
        assertEquals(List.of(2L, 1L), new ArrayList<>(sortedCopy.keySet()));
        assertEquals("b", sortedCopy.get(2L).getName());

        final java.sql.Timestamp timestamp = new java.sql.Timestamp(123456789000L);
        timestamp.setNanos(123456789);
        results.put("timestamps", List.of(timestamp));
        dao.getTimestamps("timestamps");
        results.put("timestamps", null);
        final List<java.sql.Timestamp> timestampsCopy = dao.getTimestamps("timestamps");
        assertSame(ArrayList.class, timestampsCopy.getClass());
        assertEquals(List.of(timestamp), timestampsCopy);
        assertNotSame(timestamp, timestampsCopy.get(0));

        // An EnumSet is copied as an EnumSet (its elements are constants): rebuilt through JSON, a declared EnumSet failed
        // every call of the method, as no default container fits it and its runtime class has no constructor to call.
        final java.util.EnumSet<Status> statusSet = java.util.EnumSet.of(Status.ACTIVE);
        results.put("statusSet", statusSet);
        assertSame(statusSet, dao.getStatusSet("statusSet"));
        results.put("statusSet", null);
        final java.util.EnumSet<Status> statusSetCopy = dao.getStatusSet("statusSet");
        assertNotSame(statusSet, statusSetCopy);
        assertEquals(java.util.EnumSet.of(Status.ACTIVE), statusSetCopy);
        statusSetCopy.add(Status.INACTIVE);
        statusSet.clear();
        assertEquals(java.util.EnumSet.of(Status.ACTIVE), dao.getStatusSet("statusSet"), "the cached copy is isolated");

        final java.util.EnumSet<Status> heldStatusSet = java.util.EnumSet.of(Status.INACTIVE);
        results.put("heldStatusSet", heldStatusSet);
        dao.getHeldStatusSet("heldStatusSet");
        results.put("heldStatusSet", null);
        final java.util.EnumSet<Status> heldStatusSetCopy = dao.getHeldStatusSet("heldStatusSet").get();
        assertNotSame(heldStatusSet, heldStatusSetCopy);
        assertEquals(java.util.EnumSet.of(Status.INACTIVE), heldStatusSetCopy);
        heldStatusSetCopy.clear();
        assertEquals(java.util.EnumSet.of(Status.INACTIVE), dao.getHeldStatusSet("heldStatusSet").get(), "the cached copy is isolated");

        // A return type that does not accept the default container of the result's kind still fails: the parser names the class.
        results.put("lifoQueue", java.util.Collections.asLifoQueue(new java.util.ArrayDeque<>(List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> dao.getQueue("lifoQueue"));
    }

    @Test
    void nonPublicSortedSetRootsAreRebuiltAsTreeSetsKeepingTheirOrdering() {
        final java.util.TreeSet<Long> natural = new java.util.TreeSet<>(List.of(3L, 1L, 2L));
        final java.util.TreeSet<Long> reversed = new java.util.TreeSet<>(Comparator.reverseOrder());
        reversed.addAll(List.of(3L, 1L, 2L));
        final java.util.TreeMap<Long, String> map = new java.util.TreeMap<>();
        map.put(2L, "b");
        map.put(1L, "a");
        final Map<String, SortedSet<Long>> views = new LinkedHashMap<>();
        views.put("unmodifiableNatural", java.util.Collections.unmodifiableSortedSet(natural));
        views.put("unmodifiableReversed", java.util.Collections.unmodifiableSortedSet(reversed));
        views.put("keySet", (SortedSet<Long>) map.keySet());
        views.put("descendingKeySet", map.descendingKeySet());
        views.put("navigableKeySetDescending", map.navigableKeySet().descendingSet());

        for (final Map.Entry<String, SortedSet<Long>> e : views.entrySet()) {
            final String key = "sortedRoot." + e.getKey();
            final SortedSet<Long> source = e.getValue();
            final List<Long> expectedOrder = new ArrayList<>(source);
            results.put(key, source);
            assertSame(source, dao.getSortedLongs(key), key);
            results.put(key, null);
            final SortedSet<Long> copy = dao.getSortedLongs(key);
            assertSame(java.util.TreeSet.class, copy.getClass(), key);
            assertEquals(expectedOrder, new ArrayList<>(copy), key);
            if (source.comparator() == null) {
                assertEquals(null, copy.comparator(), key);
            }
            copy.add(99L);
            assertEquals(expectedOrder, new ArrayList<>(dao.getSortedLongs(key)), key + ": the cached copy is isolated");
        }
    }

    @Test
    void factoryAndEmptyRootsAreRebuiltAsDefaultContainers() {
        final Set<Long> setOf = Set.of(5L, 6L, 7L);
        results.put("setOf", setOf);
        dao.getLongSet("setOf");
        results.put("setOf", null);
        final Set<Long> setOfCopy = dao.getLongSet("setOf");
        assertSame(LinkedHashSet.class, setOfCopy.getClass());
        assertEquals(new ArrayList<>(setOf), new ArrayList<>(setOfCopy), "keeps the source's iteration order");

        results.put("emptyList", java.util.Collections.emptyList());
        dao.getValues("emptyList");
        results.put("emptyList", null);
        final List<Value> emptyListCopy = dao.getValues("emptyList");
        assertEquals(List.of(), emptyListCopy);
        emptyListCopy.add(new Value(1, "added"));
        assertEquals(List.of(), dao.getValues("emptyList"));

        results.put("emptyMap", java.util.Collections.emptyMap());
        dao.getEmptyCounts("emptyMap");
        results.put("emptyMap", null);
        final Map<String, Long> emptyMapCopy = dao.getEmptyCounts("emptyMap");
        assertEquals(Map.of(), emptyMapCopy);
        emptyMapCopy.put("added", 1L);
        assertEquals(Map.of(), dao.getEmptyCounts("emptyMap"));
    }

    @Test
    void rootEnumConstantWithABodyIsServedFromTheCache() {
        // The constant's class is an anonymous subclass of the enum.
        results.put("op", Op.PLUS);
        assertSame(Op.PLUS, dao.getOp("op"));
        results.put("op", null);
        assertSame(Op.PLUS, dao.getOp("op"), "served from the cache");
        assertEquals(3L, dao.getOp("op").apply(1, 2));

        results.put("anyOp", Op.PLUS);
        assertSame(Op.PLUS, dao.getAny("anyOp"));
        results.put("anyOp", null);
        assertSame(Op.PLUS, dao.getAny("anyOp"), "served from the cache");
    }

    // The class of a constant with a body is an anonymous subclass of its enum: an untyped container holding it and another
    // constant of the enum had no common element class, so the cached copy read the constants back as Strings. (For an enum
    // the library's class loader cannot see, see DaoCacheJsonChildLoaderEnumTest.)
    @Test
    void enumConstantsWithABodyInUnresolvedPositionsAreServedFromTheCache() {
        final Map<String, java.util.function.Function<String, Object>> getters = new LinkedHashMap<>();
        getters.put("u.Optional", k -> dao.getHeldOptional(k).get());
        getters.put("u.Nullable", k -> dao.getHeldNullable(k).get());
        getters.put("java.util.Optional", k -> dao.getHeldJdkOptional(k).get());

        for (final Map.Entry<String, java.util.function.Function<String, Object>> getter : getters.entrySet()) {
            final String key = "heldOp." + getter.getKey();
            results.put(key, Op.PLUS);
            assertSame(Op.PLUS, getter.getValue().apply(key), key);
            results.put(key, null);
            assertSame(Op.PLUS, getter.getValue().apply(key), key + ": served from the cache");
        }

        results.put("opList", new ArrayList<>(List.of(Op.PLUS, Op.PLUS)));
        dao.getUntypedList("opList");
        results.put("opList", null);
        assertEquals(List.of(Op.PLUS, Op.PLUS), dao.getUntypedList("opList"));

        final LinkedHashMap<Op, Op> ops = new LinkedHashMap<>();
        ops.put(Op.PLUS, Op.PLUS);
        results.put("opMap", ops);
        dao.getAny("opMap");
        results.put("opMap", null);
        final Map<?, ?> opMapCopy = (Map<?, ?>) dao.getAny("opMap");
        assertNotSame(ops, opMapCopy);
        assertSame(Op.PLUS, opMapCopy.keySet().iterator().next());
        assertSame(Op.PLUS, opMapCopy.get(Op.PLUS));

        // Mixed with a constant without a body of the same enum: the elements still share the enum's type.
        results.put("mixedEnumList", new ArrayList<>(List.of(Kind.SPECIAL, Kind.PLAIN)));
        dao.getUntypedList("mixedEnumList");
        results.put("mixedEnumList", null);
        assertEquals(List.of(Kind.SPECIAL, Kind.PLAIN), dao.getUntypedList("mixedEnumList"));
    }

    // EnumMap is public but has no no-argument constructor: naming it as the class of the copy failed every call of a method
    // returning one. It is copied as an EnumMap with deep-copied values.
    @Test
    void enumMapsAreCopiedAsEnumMapsWithDeepCopiedValues() {
        final java.util.EnumMap<Status, Long> counts = new java.util.EnumMap<>(Status.class);
        counts.put(Status.INACTIVE, 2L);
        counts.put(Status.ACTIVE, 1L);
        results.put("enumMap", counts);
        assertSame(counts, dao.getStatusCounts("enumMap"));
        results.put("enumMap", null);
        final Map<Status, Long> copy = dao.getStatusCounts("enumMap");
        assertSame(java.util.EnumMap.class, copy.getClass());
        assertNotSame(counts, copy);
        assertEquals(counts, copy);
        assertSame(Long.class, copy.get(Status.ACTIVE).getClass());
        copy.clear();
        assertEquals(counts, dao.getStatusCounts("enumMap"), "the cached copy is isolated");

        final java.util.EnumMap<Status, List<Value>> values = new java.util.EnumMap<>(Status.class);
        values.put(Status.ACTIVE, new ArrayList<>(List.of(new Value(1, "a"))));
        results.put("enumMapValues", values);
        dao.getStatusValues("enumMapValues");
        results.put("enumMapValues", null);
        final java.util.EnumMap<Status, List<Value>> valuesCopy = dao.getStatusValues("enumMapValues");
        assertNotSame(values.get(Status.ACTIVE), valuesCopy.get(Status.ACTIVE));
        assertNotSame(values.get(Status.ACTIVE).get(0), valuesCopy.get(Status.ACTIVE).get(0));
        assertEquals(1L, valuesCopy.get(Status.ACTIVE).get(0).getId());
        assertEquals("a", valuesCopy.get(Status.ACTIVE).get(0).getName());
        valuesCopy.get(Status.ACTIVE).get(0).setName("changed");
        valuesCopy.get(Status.ACTIVE).add(new Value(2, "added"));
        final List<Value> cachedValues = dao.getStatusValues("enumMapValues").get(Status.ACTIVE);
        assertEquals(1, cachedValues.size(), "the cached copy is isolated");
        assertEquals("a", cachedValues.get(0).getName(), "the cached copy is isolated");

        // An empty EnumMap keeps its key type.
        results.put("emptyEnumMap", new java.util.EnumMap<Status, Long>(Status.class));
        dao.getStatusCounts("emptyEnumMap");
        results.put("emptyEnumMap", null);
        final Map<Status, Long> emptyCopy = dao.getStatusCounts("emptyEnumMap");
        assertSame(java.util.EnumMap.class, emptyCopy.getClass());
        emptyCopy.put(Status.ACTIVE, 1L);
        assertEquals(Map.of(), dao.getStatusCounts("emptyEnumMap"), "the cached copy is isolated");

        // Untyped (Object) and held by a holder of an unresolved type: the values are typed by their runtime class.
        final java.util.EnumMap<Status, Long> held = new java.util.EnumMap<>(counts);
        results.put("anyEnumMap", held);
        dao.getAny("anyEnumMap");
        results.put("anyEnumMap", null);
        final Object anyCopy = dao.getAny("anyEnumMap");
        assertSame(java.util.EnumMap.class, anyCopy.getClass());
        assertEquals(held, anyCopy);

        results.put("heldEnumMap", held);
        dao.getHeldOptional("heldEnumMap");
        results.put("heldEnumMap", null);
        final Object heldCopy = dao.getHeldOptional("heldEnumMap").get();
        assertSame(java.util.EnumMap.class, heldCopy.getClass());
        assertNotSame(held, heldCopy);
        assertEquals(held, heldCopy);
    }

    // A public container class without a no-argument constructor (ConcurrentHashMap.KeySetView) was named as the class of the
    // copy, which failed every call: it is rebuilt as the default container of its kind, as a non-public one is.
    @Test
    void publicRootsWithoutANoArgumentConstructorAreRebuiltAsDefaultContainers() {
        final Set<Long> keySet = java.util.concurrent.ConcurrentHashMap.newKeySet();
        keySet.add(7L);
        results.put("keySetView", keySet);
        assertSame(keySet, dao.getLongSet("keySetView"));
        results.put("keySetView", null);
        final Set<Long> copy = dao.getLongSet("keySetView");
        assertSame(LinkedHashSet.class, copy.getClass());
        assertEquals(Set.of(7L), copy);
    }

    @Test
    void heldNonPublicContainersOfAnUnresolvedTypeAreRebuiltAsDefaultContainers() {
        final java.util.TreeSet<Long> reversed = new java.util.TreeSet<>(Comparator.reverseOrder());
        reversed.addAll(List.of(1L, 2L));
        final Map<String, Object> held = new LinkedHashMap<>();
        held.put("listOf", List.of(1, 2));
        held.put("arraysAsList", java.util.Arrays.asList(1, 2));
        held.put("unmodifiableList", java.util.Collections.unmodifiableList(new ArrayList<>(List.of(1, 2))));
        held.put("setOf", Set.of(1));
        held.put("unmodifiableSortedSet", java.util.Collections.unmodifiableSortedSet(reversed));
        held.put("mapOf", Map.of("k", 1));
        held.put("unmodifiableMap", java.util.Collections.unmodifiableMap(new LinkedHashMap<>(Map.of("k", 1))));
        final Map<String, java.util.function.Function<String, Object>> getters = new LinkedHashMap<>();
        getters.put("u.Optional", k -> dao.getHeldOptional(k).get());
        getters.put("u.Nullable", k -> dao.getHeldNullable(k).get());
        getters.put("java.util.Optional", k -> dao.getHeldJdkOptional(k).get());

        for (final Map.Entry<String, java.util.function.Function<String, Object>> getter : getters.entrySet()) {
            for (final Map.Entry<String, Object> e : held.entrySet()) {
                final String key = "held." + getter.getKey() + "." + e.getKey();
                final Object source = e.getValue();
                results.put(key, source);
                // The first call caches the result: its copy is returned only on a later call.
                getter.getValue().apply(key);
                final Object copy = getter.getValue().apply(key);
                assertNotSame(source, copy, key);
                assertEquals(source, copy, key);

                if (source instanceof SortedSet<?> sortedSet) {
                    assertSame(java.util.TreeSet.class, copy.getClass(), key);
                    assertEquals(new ArrayList<>(sortedSet), new ArrayList<>((Set<?>) copy), key);
                } else {
                    assertSame(source instanceof List ? ArrayList.class : source instanceof Set ? LinkedHashSet.class : LinkedHashMap.class, copy.getClass(),
                            key);
                }

                if (copy instanceof Map<?, ?> map) {
                    map.clear();
                } else {
                    ((Collection<?>) copy).clear();
                }

                assertEquals(source, getter.getValue().apply(key), key + ": the cached copy is isolated");
            }
        }
    }

    @SuppressWarnings("serial")
    private static List<Long> anonymousList() {
        // Declared in a static context: the anonymous class has a no-argument constructor.
        return new ArrayList<>() {
            {
                add(1L);
            }
        };
    }

    @Test
    void ordinaryMapKeysRetainTheirQuotedJsonContextAlongsideASortedChild() {
        final LinkedHashMap<Object, Object> entries = new LinkedHashMap<>();
        entries.put(1L, "number");
        entries.put(null, "null key");
        entries.put(true, "boolean");
        entries.put("child", new ArrayList<>());
        results.put("keyControl", ImmutableMap.wrap(new LinkedHashMap<>(entries)));
        dao.getUntypedKeys("keyControl");
        final ImmutableMap<Object, Object> control = dao.getUntypedKeys("keyControl");
        assertEquals("number", control.get("1"));
        // A null key stays null (it used to come back as the String "null", which could also collide with a real "null"
        // key); the other Object-typed scalar keys keep JSON's quoted-key defaults.
        assertEquals("null key", control.get(null));
        assertFalse(control.containsKey("null"));
        assertEquals("boolean", control.get("true"));

        entries.put("child", sortedChild());
        results.put("sortedKeys", ImmutableMap.wrap(entries));
        dao.getUntypedKeys("sortedKeys");
        final ImmutableMap<Object, Object> copy = dao.getUntypedKeys("sortedKeys");
        assertEquals(control.keySet(), copy.keySet());
        assertEquals("number", copy.get("1"));
        assertEquals("null key", copy.get(null));
        assertEquals("boolean", copy.get("true"));
    }

    @Test
    void declaredSortedMapChildrenAcceptUnmodifiableAndRangeViews() {
        final java.util.TreeMap<Long, Value> backing = new java.util.TreeMap<>(Comparator.reverseOrder());
        backing.putAll(sortedChild());
        final List<SortedMap<Long, Value>> views = List.of(java.util.Collections.unmodifiableSortedMap(backing),
                java.util.Collections.unmodifiableNavigableMap(backing), backing.subMap(2L, true, 1L, true));
        for (int i = 0; i < views.size(); i++) {
            final String key = "mapView" + i;
            results.put(key, ImmutableCollection.wrap(new ArrayList<>(List.of(views.get(i)))));
            dao.getMapViews(key);
            final SortedMap<Long, Value> copy = dao.getMapViews(key).iterator().next();
            assertSame(backing.comparator(), copy.comparator());
            assertEquals(List.of(2L, 1L), new ArrayList<>(copy.keySet()));
            assertNotSame(backing.get(2L), copy.get(2L));
            copy.get(2L).name = "changed";
            assertEquals("original", dao.getMapViews(key).iterator().next().get(2L).name);
        }
    }

    @Test
    void declaredSortedSetChildrenAcceptUnmodifiableViews() {
        final java.util.TreeSet<Long> backing = new java.util.TreeSet<>(Comparator.reverseOrder());
        backing.addAll(List.of(1L, 2L));
        final List<SortedSet<Long>> views = List.of(java.util.Collections.unmodifiableSortedSet(backing),
                java.util.Collections.unmodifiableNavigableSet(backing));
        for (int i = 0; i < views.size(); i++) {
            final String key = "setView" + i;
            results.put(key, ImmutableMap.of("child", views.get(i)));
            dao.getSetViews(key);
            final SortedSet<Long> copy = dao.getSetViews(key).get("child");
            assertSame(backing.comparator(), copy.comparator());
            assertEquals(List.of(2L, 1L), new ArrayList<>(copy));
            assertNotSame(backing, copy);
        }
    }

    @Test
    void nestedListUsesItsDeclaredTypeToAcceptAFactoryCreatedParent() {
        final ImmutableSortedMap<Long, Value> child = sortedChild();
        results.put("listParent", ImmutableCollection.wrap(new ArrayList<>(List.of(List.of(child)))));
        dao.getListParents("listParent");
        child.get(2L).name = "changed";
        final List<SortedMap<Long, Value>> copy = dao.getListParents("listParent").iterator().next();
        assertSame(child.comparator(), copy.get(0).comparator());
        assertEquals("original", copy.get(0).get(2L).name);
        copy.clear();
        assertEquals(1, dao.getListParents("listParent").iterator().next().size());
    }

    @Test
    void nestedMapUsesItsDeclaredTypeToAcceptAFactoryCreatedParent() {
        final ImmutableSortedMap<Long, Value> child = sortedChild();
        results.put("mapParent", ImmutableCollection.wrap(new ArrayList<>(List.of(Map.of("child", child)))));
        dao.getMapParents("mapParent");
        child.get(2L).name = "changed";
        final Map<String, SortedMap<Long, Value>> copy = dao.getMapParents("mapParent").iterator().next();
        assertSame(child.comparator(), copy.get("child").comparator());
        assertEquals("original", copy.get("child").get(2L).name);
        copy.clear();
        assertEquals(1, dao.getMapParents("mapParent").iterator().next().size());
    }

    @Test
    void naturalOrderPublicSubclassesRetainTheirExistingJsonCopySupport() {
        final NaturalValueMap map = new NaturalValueMap();
        map.putAll(sortedChild());
        results.put("naturalMap", map);
        dao.getNaturalMap("naturalMap");
        map.get(2L).name = "changed";
        final java.util.TreeMap<Long, Value> copiedMap = dao.getNaturalMap("naturalMap");
        assertEquals(NaturalValueMap.class, copiedMap.getClass());
        assertEquals(List.of(1L, 2L), new ArrayList<>(copiedMap.keySet()));
        assertEquals("original", copiedMap.get(2L).name);

        final NaturalLongSet set = new NaturalLongSet();
        set.addAll(List.of(2L, 1L));
        results.put("naturalSet", set);
        dao.getNaturalSet("naturalSet");
        set.clear();
        final java.util.TreeSet<Long> copiedSet = dao.getNaturalSet("naturalSet");
        assertEquals(NaturalLongSet.class, copiedSet.getClass());
        assertEquals(List.of(1L, 2L), new ArrayList<>(copiedSet));
    }

    @Test
    void naturalOrderPublicSubclassCanContainAChildNeedingComparatorRepair() {
        final ImmutableSortedMap<Long, Value> child = sortedChild();
        final NaturalParentMap source = new NaturalParentMap();
        source.put(1L, child);
        results.put("naturalParent", source);
        dao.getNaturalParent("naturalParent");
        child.get(2L).name = "changed";
        final java.util.TreeMap<Long, SortedMap<Long, Value>> copy = dao.getNaturalParent("naturalParent");
        assertEquals(NaturalParentMap.class, copy.getClass());
        assertSame(child.comparator(), copy.get(1L).comparator());
        assertEquals("original", copy.get(1L).get(2L).name);
        copy.clear();
        assertEquals(1, dao.getNaturalParent("naturalParent").size());
    }

    @Test
    void declaredBaseImmutableTypesAcceptTheirSortedSubclasses() {
        final ImmutableSortedMap<Long, Value> map = sortedChild();
        final java.util.TreeSet<Long> backing = new java.util.TreeSet<>(Comparator.reverseOrder());
        backing.addAll(List.of(1L, 2L));
        final ImmutableSortedSet<Long> set = ImmutableSortedSet.wrap(backing);
        results.put("baseMaps", ImmutableCollection.wrap(new ArrayList<>(List.of(map))));
        results.put("baseSets", ImmutableCollection.wrap(new ArrayList<>(List.of(set))));
        results.put("baseCollections", ImmutableCollection.wrap(new ArrayList<>(List.of(set))));
        dao.getBaseMaps("baseMaps");
        dao.getBaseSets("baseSets");
        dao.getBaseCollections("baseCollections");
        map.get(2L).name = "changed";
        final ImmutableMap<Long, Value> copiedMap = dao.getBaseMaps("baseMaps").iterator().next();
        final ImmutableSet<Long> copiedSet = dao.getBaseSets("baseSets").iterator().next();
        final ImmutableCollection<Long> copiedCollection = dao.getBaseCollections("baseCollections").iterator().next();
        assertSame(map.comparator(), ((SortedMap<?, ?>) copiedMap).comparator());
        assertSame(set.comparator(), ((SortedSet<?>) copiedSet).comparator());
        assertSame(set.comparator(), ((SortedSet<?>) copiedCollection).comparator());
        assertEquals("original", copiedMap.get(2L).name);
        assertEquals(List.of(2L, 1L), new ArrayList<>(copiedSet));
        assertEquals(List.of(2L, 1L), new ArrayList<>(copiedCollection));
        assertThrows(UnsupportedOperationException.class, copiedMap::clear);
        assertThrows(UnsupportedOperationException.class, copiedSet::clear);
        assertThrows(UnsupportedOperationException.class, copiedCollection::clear);
    }

    @Test
    void declaredBaseImmutableTypesAcceptPlainSortedContainers() {
        // A plain TreeMap/TreeSet is not assignable to the declared ImmutableMap/ImmutableSet/ImmutableCollection
        // base, so the copy must be rebuilt as the base's sorted member instead of populating an empty immutable.
        final java.util.TreeMap<Long, Value> map = new java.util.TreeMap<>(Comparator.reverseOrder());
        map.putAll(sortedChild());
        final java.util.TreeSet<Long> set = new java.util.TreeSet<>(Comparator.reverseOrder());
        set.addAll(List.of(1L, 2L));
        results.put("plainBaseMaps", ImmutableCollection.wrap(new ArrayList<>(List.of(map))));
        results.put("plainBaseSets", ImmutableCollection.wrap(new ArrayList<>(List.of(set))));
        results.put("plainBaseCollections", ImmutableCollection.wrap(new ArrayList<>(List.of(set))));
        dao.getBaseMaps("plainBaseMaps");
        dao.getBaseSets("plainBaseSets");
        dao.getBaseCollections("plainBaseCollections");
        map.get(2L).name = "changed";
        final ImmutableMap<Long, Value> copiedMap = dao.getBaseMaps("plainBaseMaps").iterator().next();
        final ImmutableSet<Long> copiedSet = dao.getBaseSets("plainBaseSets").iterator().next();
        final ImmutableCollection<Long> copiedCollection = dao.getBaseCollections("plainBaseCollections").iterator().next();
        assertSame(map.comparator(), ((SortedMap<?, ?>) copiedMap).comparator());
        assertSame(set.comparator(), ((SortedSet<?>) copiedSet).comparator());
        assertSame(set.comparator(), ((SortedSet<?>) copiedCollection).comparator());
        assertEquals(List.of(2L, 1L), new ArrayList<>(copiedMap.keySet()));
        assertEquals("original", copiedMap.get(2L).name);
        assertEquals(List.of(2L, 1L), new ArrayList<>(copiedSet));
        assertEquals(List.of(2L, 1L), new ArrayList<>(copiedCollection));
        assertThrows(UnsupportedOperationException.class, copiedMap::clear);
        assertThrows(UnsupportedOperationException.class, copiedSet::clear);
        assertThrows(UnsupportedOperationException.class, copiedCollection::clear);
    }

    @Test
    void concurrentSortedContainersRetainTypeComparatorAndIsolation() {
        final java.util.concurrent.ConcurrentSkipListMap<Long, Value> map = new java.util.concurrent.ConcurrentSkipListMap<>(Comparator.reverseOrder());
        map.putAll(sortedChild());
        results.put("concurrentMap", map);
        dao.getConcurrentMap("concurrentMap");
        map.get(2L).name = "changed after write";
        final java.util.concurrent.ConcurrentSkipListMap<Long, Value> copiedMap = dao.getConcurrentMap("concurrentMap");
        assertEquals(java.util.concurrent.ConcurrentSkipListMap.class, copiedMap.getClass());
        assertSame(map.comparator(), copiedMap.comparator());
        assertEquals(List.of(2L, 1L), new ArrayList<>(copiedMap.keySet()));
        assertEquals("original", copiedMap.get(2L).name);
        copiedMap.get(2L).name = "changed after read";
        copiedMap.clear();
        assertEquals("original", dao.getConcurrentMap("concurrentMap").get(2L).name);

        final java.util.concurrent.ConcurrentSkipListSet<Value> set = new java.util.concurrent.ConcurrentSkipListSet<>(
                Comparator.comparingLong(Value::getId).reversed());
        set.add(new Value(1, "other"));
        set.add(new Value(2, "original"));
        results.put("concurrentSet", set);
        dao.getConcurrentSet("concurrentSet");
        set.first().name = "changed after write";
        final java.util.concurrent.ConcurrentSkipListSet<Value> copiedSet = dao.getConcurrentSet("concurrentSet");
        assertEquals(java.util.concurrent.ConcurrentSkipListSet.class, copiedSet.getClass());
        assertSame(set.comparator(), copiedSet.comparator());
        assertEquals(2L, copiedSet.first().id);
        assertEquals("original", copiedSet.first().name);
        copiedSet.first().name = "changed after read";
        copiedSet.clear();
        assertEquals(2, dao.getConcurrentSet("concurrentSet").size());
        assertEquals("original", dao.getConcurrentSet("concurrentSet").first().name);

        final java.util.concurrent.ConcurrentSkipListMap<Long, Value> child = new java.util.concurrent.ConcurrentSkipListMap<>(Comparator.reverseOrder());
        child.putAll(sortedChild());
        results.put("concurrentMapChildren", ImmutableCollection.wrap(new ArrayList<>(List.of(child))));
        results.put("concurrentNavigableChildren", ImmutableCollection.wrap(new ArrayList<>(List.of(child))));
        dao.getConcurrentMapChildren("concurrentMapChildren");
        dao.getConcurrentNavigableChildren("concurrentNavigableChildren");
        child.get(2L).name = "changed after write";
        final java.util.concurrent.ConcurrentMap<Long, Value> copiedChild = dao.getConcurrentMapChildren("concurrentMapChildren").iterator().next();
        final java.util.concurrent.ConcurrentNavigableMap<Long, Value> navigableChild = dao.getConcurrentNavigableChildren("concurrentNavigableChildren")
                .iterator()
                .next();
        assertSame(child.comparator(), ((SortedMap<?, ?>) copiedChild).comparator());
        assertSame(child.comparator(), navigableChild.comparator());
        assertEquals(List.of(2L, 1L), new ArrayList<>(copiedChild.keySet()));
        assertEquals(2L, navigableChild.firstKey());
        assertEquals("original", copiedChild.get(2L).name);
        assertEquals("original", navigableChild.get(2L).name);
        copiedChild.get(2L).name = "changed after read";
        navigableChild.get(2L).name = "changed after read";
        assertEquals("original", dao.getConcurrentMapChildren("concurrentMapChildren").iterator().next().get(2L).name);
        assertEquals("original", dao.getConcurrentNavigableChildren("concurrentNavigableChildren").iterator().next().get(2L).name);
    }

    private static void check(final String key, final Object source, final Class<?> expectedClass, final boolean immutable, final Supplier<?> call) {
        results.put(key, source);
        final ImmutableSortedMap<Long, Value> child = childOf(source);
        assertSame(source, call.get());
        child.get(2L).name = "changed after write";
        final Object copy = call.get();
        assertEquals(expectedClass, copy.getClass());
        assertNotSame(source, copy);
        final ImmutableSortedMap<Long, Value> copiedChild = childOf(copy);
        assertSame(child.comparator(), copiedChild.comparator());
        assertEquals(List.of(2L, 1L), new ArrayList<>(copiedChild.keySet()));
        assertEquals("original", copiedChild.get(2L).name);
        assertNotSame(child.get(2L), copiedChild.get(2L));
        assertThrows(UnsupportedOperationException.class, copiedChild::clear);
        copiedChild.get(2L).name = "changed after read";
        if (copy instanceof Map<?, ?> map) {
            if (immutable) {
                assertThrows(UnsupportedOperationException.class, map::clear);
            } else {
                map.clear();
            }
        } else if (immutable) {
            assertThrows(UnsupportedOperationException.class, ((Collection<?>) copy)::clear);
        } else {
            ((Collection<?>) copy).clear();
        }
        assertEquals("original", childOf(call.get()).get(2L).name);
    }

    private static ImmutableSortedMap<Long, Value> childOf(final Object outer) {
        return sortedChildOf(outer instanceof Map<?, ?> map ? map.values().iterator().next() : ((Collection<?>) outer).iterator().next());
    }

    @SuppressWarnings("unchecked")
    private static ImmutableSortedMap<Long, Value> sortedChildOf(final Object child) {
        return (ImmutableSortedMap<Long, Value>) child;
    }

    private static ImmutableSortedMap<Long, Value> sortedChild() {
        final java.util.TreeMap<Long, Value> child = new java.util.TreeMap<>(Comparator.reverseOrder());
        child.put(1L, new Value(1, "other"));
        child.put(2L, new Value(2, "original"));
        return ImmutableSortedMap.wrap(child);
    }
}
