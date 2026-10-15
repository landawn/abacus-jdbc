package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;

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
        assertEquals("null key", control.get("null"));
        assertEquals("boolean", control.get("true"));

        entries.put("child", sortedChild());
        results.put("sortedKeys", ImmutableMap.wrap(entries));
        dao.getUntypedKeys("sortedKeys");
        final ImmutableMap<Object, Object> copy = dao.getUntypedKeys("sortedKeys");
        assertEquals(control.keySet(), copy.keySet());
        assertEquals("number", copy.get("1"));
        assertEquals("null key", copy.get("null"));
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
