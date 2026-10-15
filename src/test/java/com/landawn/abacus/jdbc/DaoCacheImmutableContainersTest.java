package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import javax.sql.DataSource;

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
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.ImmutableNavigableMap;
import com.landawn.abacus.util.ImmutableNavigableSet;
import com.landawn.abacus.util.ImmutableSet;
import com.landawn.abacus.util.ImmutableSortedMap;
import com.landawn.abacus.util.ImmutableSortedSet;

@Tag("integration")
@Tag("2025")
class DaoCacheImmutableContainersTest {
    private static final Map<String, Object> values = new LinkedHashMap<>();
    private static DataSource dataSource;
    private static CacheDao dao;

    @BeforeAll
    static void setUp() {
        dataSource = JdbcUtil.createHikariDataSource("jdbc:h2:mem:cache_container_review", "sa", "");
        dao = JdbcUtil.createDao(CacheDao.class, dataSource);
    }

    @AfterAll
    static void tearDown() {
        ((com.zaxxer.hikari.HikariDataSource) dataSource).close();
        values.clear();
    }

    public static class Value implements Comparable<Value> {
        @Id
        private long id;
        private String name;
        private Object link;

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

        public Object getLink() {
            return link;
        }

        public void setLink(final Object link) {
            this.link = link;
        }

        @Override
        public int compareTo(final Value other) {
            return Long.compare(id, other.id);
        }
    }

    @SuppressWarnings("unchecked")
    public interface CacheDao extends NonUpdateCrudDao<Value, Long, CacheDao> {
        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableCollection<Value> getCollection(final String key) {
            return (ImmutableCollection<Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableList<Value> getList(final String key) {
            return (ImmutableList<Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableSet<Value> getSet(final String key) {
            return (ImmutableSet<Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableSortedSet<Value> getSortedSet(final String key) {
            return (ImmutableSortedSet<Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableNavigableSet<Value> getNavigableSet(final String key) {
            return (ImmutableNavigableSet<Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableMap<Long, Value> getMap(final String key) {
            return (ImmutableMap<Long, Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableSortedMap<Long, Value> getSortedMap(final String key) {
            return (ImmutableSortedMap<Long, Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableNavigableMap<Long, Value> getNavigableMap(final String key) {
            return (ImmutableNavigableMap<Long, Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableBiMap<Long, Value> getBiMap(final String key) {
            return (ImmutableBiMap<Long, Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableList<ImmutableMap<Long, Value>> getNested(final String key) {
            return (ImmutableList<ImmutableMap<Long, Value>>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableList<Object> getObjects(final String key) {
            return (ImmutableList<Object>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default ImmutableMap<String, Object> getObjectMap(final String key) {
            return (ImmutableMap<String, Object>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default <T> Collection<T> getGenericCollection(final String key) {
            return (Collection<T>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default Value getValue(final String key) {
            return (Value) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default StatefulCollection getStatefulCollection(final String key) {
            return (StatefulCollection) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.KRYO)
        default Object getAnyValue(final String key) {
            return values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableCollection<Value> getJsonCollection(final String key) {
            return (ImmutableCollection<Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableList<Value> getJsonList(final String key) {
            return (ImmutableList<Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableSet<Value> getJsonSet(final String key) {
            return (ImmutableSet<Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableSortedSet<Value> getJsonSortedSet(final String key) {
            return (ImmutableSortedSet<Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableNavigableSet<Value> getJsonNavigableSet(final String key) {
            return (ImmutableNavigableSet<Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableMap<Long, Value> getJsonMap(final String key) {
            return (ImmutableMap<Long, Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableSortedMap<Long, Value> getJsonSortedMap(final String key) {
            return (ImmutableSortedMap<Long, Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableNavigableMap<Long, Value> getJsonNavigableMap(final String key) {
            return (ImmutableNavigableMap<Long, Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableBiMap<Long, Value> getJsonBiMap(final String key) {
            return (ImmutableBiMap<Long, Value>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableList<ImmutableMap<Long, Value>> getJsonNested(final String key) {
            return (ImmutableList<ImmutableMap<Long, Value>>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableSet<String> getJsonStringSet(final String key) {
            return (ImmutableSet<String>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableMap<String, Integer> getJsonStringMap(final String key) {
            return (ImmutableMap<String, Integer>) values.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableBiMap<String, Integer> getJsonStringBiMap(final String key) {
            return (ImmutableBiMap<String, Integer>) values.get(key);
        }
    }

    @TestFactory
    List<DynamicTest> preservesEveryImmutableContainerShape() {
        return List.of(
                dynamicTest("collection",
                        () -> checkCollection("collection", ImmutableCollection.wrap(new ArrayList<>(newValues())), () -> dao.getCollection("collection"))),
                dynamicTest("list", () -> checkCollection("list", ImmutableList.wrap(newValues()), () -> dao.getList("list"))),
                dynamicTest("reversed list", () -> checkCollection("reversed", ImmutableList.wrap(newValues()).reversed(), () -> dao.getList("reversed"))),
                dynamicTest("sublist", () -> checkCollection("sublist", ImmutableList.wrap(newValues()).subList(1, 2), () -> dao.getList("sublist"))),
                dynamicTest("linked sublist",
                        () -> checkCollection("linkedSublist", ImmutableList.wrap(new java.util.LinkedList<>(newValues())).subList(1, 2),
                                () -> dao.getList("linkedSublist"))),
                dynamicTest("set", () -> checkCollection("set", ImmutableSet.wrap(new LinkedHashSet<>(newValues())), () -> dao.getSet("set"))),
                dynamicTest("sorted set", () -> {
                    final ImmutableSortedSet<Value> source = ImmutableSortedSet.wrap(sortedValues());
                    checkCollection("sortedSet", source, () -> dao.getSortedSet("sortedSet"));
                    assertSame(source.comparator(), dao.getSortedSet("sortedSet").comparator());
                    assertEquals(2, dao.getSortedSet("sortedSet").first().id);
                }), dynamicTest("navigable set", () -> {
                    final ImmutableNavigableSet<Value> source = ImmutableNavigableSet.wrap(sortedValues());
                    checkCollection("navigableSet", source, () -> dao.getNavigableSet("navigableSet"));
                    assertSame(source.comparator(), dao.getNavigableSet("navigableSet").comparator());
                    assertEquals(1, dao.getNavigableSet("navigableSet").last().id);
                }), dynamicTest("map", () -> checkMap("map", ImmutableMap.wrap(mappedValues()), () -> dao.getMap("map"))), dynamicTest("sorted map", () -> {
                    final ImmutableSortedMap<Long, Value> source = ImmutableSortedMap.wrap(sortedMappedValues());
                    checkMap("sortedMap", source, () -> dao.getSortedMap("sortedMap"));
                    assertSame(source.comparator(), dao.getSortedMap("sortedMap").comparator());
                    assertEquals(2L, dao.getSortedMap("sortedMap").firstKey());
                }), dynamicTest("navigable map", () -> {
                    final ImmutableNavigableMap<Long, Value> source = ImmutableNavigableMap.wrap(sortedMappedValues());
                    checkMap("navigableMap", source, () -> dao.getNavigableMap("navigableMap"));
                    assertSame(source.comparator(), dao.getNavigableMap("navigableMap").comparator());
                    assertEquals(1L, dao.getNavigableMap("navigableMap").lastKey());
                }),
                dynamicTest("sorted range map",
                        () -> checkMap("sortedRange", ImmutableSortedMap.wrap(sortedMappedValues().subMap(2L, 0L)), () -> dao.getSortedMap("sortedRange"))),
                dynamicTest("navigable descending range map",
                        () -> checkMap("navigableRange", ImmutableNavigableMap.wrap(sortedMappedValues().descendingMap().headMap(2L, true)),
                                () -> dao.getNavigableMap("navigableRange"))),
                dynamicTest("bimap", () -> checkMap("bimap", ImmutableBiMap.copyOf(mappedValues()), () -> dao.getBiMap("bimap"))));
    }

    @Test
    void keepsNestedImmutableContainersAndGenericElementTypes() {
        values.put("nested", ImmutableList.of(ImmutableMap.wrap(mappedValues())));
        final ImmutableList<ImmutableMap<Long, Value>> first = dao.getNested("nested");
        first.get(0).get(1L).name = "changed";
        final ImmutableList<ImmutableMap<Long, Value>> cached = dao.getNested("nested");
        assertEquals("one", cached.get(0).get(1L).name);
        assertNotSame(first.get(0), cached.get(0));
        assertThrows(UnsupportedOperationException.class, () -> cached.get(0).clear());
    }

    @TestFactory
    List<DynamicTest> jsonPreservesSupportedImmutableContainerShapes() {
        return List.of(
                dynamicTest("JSON collection",
                        () -> checkCollection("jsonCollection", ImmutableCollection.wrap(newValues()), () -> dao.getJsonCollection("jsonCollection"))),
                dynamicTest("JSON list", () -> checkCollection("jsonList", ImmutableList.wrap(newValues()), () -> dao.getJsonList("jsonList"))),
                dynamicTest("JSON set", () -> checkCollection("jsonSet", ImmutableSet.wrap(new LinkedHashSet<>(newValues())), () -> dao.getJsonSet("jsonSet"))),
                dynamicTest("JSON sorted set",
                        () -> checkCollection("jsonSortedSet", ImmutableSortedSet.wrap(new TreeSet<>(newValues())),
                                () -> dao.getJsonSortedSet("jsonSortedSet"))),
                dynamicTest("JSON navigable set",
                        () -> checkCollection("jsonNavigableSet", ImmutableNavigableSet.wrap(new TreeSet<>(newValues())),
                                () -> dao.getJsonNavigableSet("jsonNavigableSet"))),
                dynamicTest("JSON map", () -> checkMap("jsonMap", ImmutableMap.wrap(mappedValues()), () -> dao.getJsonMap("jsonMap"))),
                dynamicTest("JSON sorted map",
                        () -> checkMap("jsonSortedMap", ImmutableSortedMap.wrap(new TreeMap<>(mappedValues())), () -> dao.getJsonSortedMap("jsonSortedMap"))),
                dynamicTest("JSON navigable map",
                        () -> checkMap("jsonNavigableMap", ImmutableNavigableMap.wrap(new TreeMap<>(mappedValues())),
                                () -> dao.getJsonNavigableMap("jsonNavigableMap"))),
                dynamicTest("JSON bimap", () -> checkMap("jsonBimap", ImmutableBiMap.copyOf(mappedValues()), () -> dao.getJsonBiMap("jsonBimap"))));
    }

    @Test
    void jsonKeepsNestedImmutableContainersAndDeclaredElementTypes() {
        values.put("jsonNested", ImmutableList.of(ImmutableMap.wrap(mappedValues())));
        final ImmutableList<ImmutableMap<Long, Value>> first = dao.getJsonNested("jsonNested");
        first.get(0).get(1L).name = "changed";
        final ImmutableList<ImmutableMap<Long, Value>> cached = dao.getJsonNested("jsonNested");
        assertEquals("one", cached.get(0).get(1L).name);
        assertNotSame(first.get(0), cached.get(0));
        assertEquals(List.of(2L, 1L), new ArrayList<>(cached.get(0).keySet()));
    }

    @Test
    void jsonCopiesOfPlainImmutableSetsAndMapsKeepInsertionOrder() {
        // "a" < "b" < "c" and 1 < 2 < 3 by hash code, so a HashSet/HashMap copy would iterate in sorted order.
        values.put("orderedSet", ImmutableSet.of("c", "a", "b"));
        values.put("orderedMap", ImmutableMap.of("c", 3, "a", 1, "b", 2));
        values.put("orderedBiMap", ImmutableBiMap.of("c", 3, "a", 1, "b", 2));
        // Identity-hashed elements would make every HashSet copy iterate in its own order.
        values.put("identityOrderedSet", ImmutableSet.wrap(new LinkedHashSet<>(List.of(new Value(3, "three"), new Value(1, "one"), new Value(2, "two")))));
        for (int read = 0; read < 3; read++) {
            final ImmutableSet<String> set = dao.getJsonStringSet("orderedSet");
            assertEquals(List.of("c", "a", "b"), new ArrayList<>(set));
            final ImmutableMap<String, Integer> map = dao.getJsonStringMap("orderedMap");
            assertEquals(List.of("c", "a", "b"), new ArrayList<>(map.keySet()));
            assertEquals(List.of(3, 1, 2), new ArrayList<>(map.values()));
            final ImmutableBiMap<String, Integer> biMap = dao.getJsonStringBiMap("orderedBiMap");
            assertEquals(List.of("c", "a", "b"), new ArrayList<>(biMap.keySet()));
            assertEquals(List.of(3, 1, 2), new ArrayList<>(biMap.values()));
            assertEquals(List.of(3, 1, 2), new ArrayList<>(biMap.inverse().keySet()));
            assertEquals(List.of(3L, 1L, 2L), dao.getJsonSet("identityOrderedSet").stream().map(Value::getId).toList());
        }
        assertNotSame(values.get("orderedSet"), dao.getJsonStringSet("orderedSet"));
        assertNotSame(values.get("orderedMap"), dao.getJsonStringMap("orderedMap"));
        assertNotSame(values.get("orderedBiMap"), dao.getJsonStringBiMap("orderedBiMap"));
    }

    public static final class CustomCollection extends ImmutableCollection<Value> {
        CustomCollection(final Collection<Value> backing) {
            super(backing);
        }
    }

    public static final class ColdParserMarker {
    }

    public static final class StatefulCollection extends ImmutableCollection<Value> {
        private final Value metadata;
        private final long version;
        private final Comparator<Value> ordering = new MutableComparator();
        private transient Value temporary;

        StatefulCollection(final Collection<Value> backing, final Value metadata, final long version) {
            super(backing);
            this.metadata = metadata;
            this.version = version;
            temporary = metadata;
        }
    }

    public static final class MutableComparator implements Comparator<Value> {
        private int direction = 1;

        @Override
        public int compare(final Value first, final Value second) {
            return direction * first.compareTo(second);
        }
    }

    @Test
    void preservesApplicationSubclassTypeStateAndBackReferences() {
        final Value metadata = new Value(3, "metadata");
        final StatefulCollection source = new StatefulCollection(newValues(), metadata, 42);
        metadata.link = source;
        values.put("subclass", source);
        dao.getStatefulCollection("subclass");
        metadata.name = "changed";
        ((MutableComparator) source.ordering).direction = -1;
        final StatefulCollection cached = dao.getStatefulCollection("subclass");
        assertEquals(StatefulCollection.class, cached.getClass());
        assertEquals(42, cached.version);
        assertEquals("metadata", cached.metadata.name);
        assertSame(cached.metadata, cached.temporary);
        assertSame(cached, cached.metadata.link);
        assertNotSame(source, cached);
        assertNotSame(metadata, cached.metadata);
        assertNotSame(source.ordering, cached.ordering);
        assertEquals(1, ((MutableComparator) cached.ordering).direction);
        assertEquals("one", cached.iterator().next().name);
    }

    @Test
    void preservesIdentityBackedMapAndSetEntries() {
        final String firstKey = new String("same");
        final String secondKey = new String("same");
        final Map<String, Object> backing = new java.util.IdentityHashMap<>();
        backing.put(firstKey, new Value(1, "one"));
        backing.put(secondKey, new Value(2, "two"));
        values.put("identityMap", ImmutableMap.wrap(backing));
        dao.getObjectMap("identityMap");
        backing.clear();
        final ImmutableMap<String, Object> copiedMap = dao.getObjectMap("identityMap");
        assertEquals(2, copiedMap.size());
        assertEquals("one", ((Value) copiedMap.get(firstKey)).name);
        assertEquals("two", ((Value) copiedMap.get(secondKey)).name);
        ((Value) copiedMap.get(firstKey)).name = "changed";
        assertEquals("one", ((Value) dao.getObjectMap("identityMap").get(firstKey)).name);

        final java.util.Set<String> setBacking = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        setBacking.add(firstKey);
        setBacking.add(secondKey);
        values.put("identitySet", ImmutableSet.wrap(setBacking));
        dao.getGenericCollection("identitySet");
        setBacking.clear();
        final Collection<String> copiedSet = dao.getGenericCollection("identitySet");
        assertEquals(2, copiedSet.size());
        assertEquals(2, copiedSet.stream().count());
        assertEquals(true, copiedSet.contains(firstKey));
        assertEquals(true, copiedSet.contains(secondKey));
        assertEquals(false, copiedSet.contains(new String("same")));
    }

    @Test
    void preservesBiMapBackingPoliciesAndInverseAliases() {
        final String firstKey = new String("key");
        final String secondKey = new String("key");
        final String firstValue = new String("value");
        final String secondValue = new String("value");
        final com.landawn.abacus.util.BiMap<String, String> backing = new com.landawn.abacus.util.BiMap<>(java.util.IdentityHashMap::new,
                java.util.IdentityHashMap::new);
        backing.put(firstKey, firstValue);
        backing.put(secondKey, secondValue);
        final ImmutableBiMap<String, String> source = ImmutableBiMap.wrap(backing);
        values.put("identityBiMap", ImmutableList.of(source, source.inverse()));
        dao.getAnyValue("identityBiMap");
        backing.clear();
        final ImmutableList<?> cached = (ImmutableList<?>) dao.getAnyValue("identityBiMap");
        final ImmutableBiMap<?, ?> forward = (ImmutableBiMap<?, ?>) cached.get(0);
        final ImmutableBiMap<?, ?> reverse = (ImmutableBiMap<?, ?>) cached.get(1);
        assertEquals(2, forward.size());
        assertSame(firstValue, forward.get(firstKey));
        assertSame(secondValue, forward.get(secondKey));
        assertSame(forward, reverse.inverse());
        assertSame(reverse, forward.inverse());
        assertSame(firstKey, reverse.get(firstValue));
    }

    public static final class RegistrationProbe extends Value implements com.esotericsoftware.kryo.KryoCopyable<RegistrationProbe> {
        @Override
        public RegistrationProbe copy(final com.esotericsoftware.kryo.Kryo kryo) {
            assertEquals(2000001, kryo.getRegistration(ImmutableSet.class).getId());
            final RegistrationProbe copy = new RegistrationProbe();
            kryo.reference(copy);
            copy.setLink(kryo.copy(getLink()));
            return copy;
        }
    }

    @Test
    void supportsClassOnlyAndSparseIdRegistrationsWithoutChangingIds() {
        for (final Class<?> type : List.of(ImmutableCollection.class, ImmutableList.class, ImmutableSortedSet.class, ImmutableNavigableSet.class,
                ImmutableMap.class, ImmutableSortedMap.class, ImmutableNavigableMap.class, ImmutableBiMap.class)) {
            com.landawn.abacus.parser.ParserFactory.registerKryo(type);
        }
        for (final Class<?> type : ImmutableList.class.getPermittedSubclasses()) {
            com.landawn.abacus.parser.ParserFactory.registerKryo(type);
        }
        com.landawn.abacus.parser.ParserFactory.registerKryo(ImmutableSet.class, 2000001);
        values.put("registeredCollection", ImmutableCollection.wrap(newValues()));
        dao.getCollection("registeredCollection");
        assertEquals(2, dao.getCollection("registeredCollection").size());
        values.put("registeredList", ImmutableList.wrap(new java.util.LinkedList<>(newValues())));
        dao.getList("registeredList");
        assertEquals(2, dao.getList("registeredList").size());
        final RegistrationProbe probe = new RegistrationProbe();
        probe.setLink(ImmutableSet.wrap(new LinkedHashSet<>(newValues())));
        values.put("registeredId", probe);
        dao.getValue("registeredId");
        assertEquals(2, ((Collection<?>) dao.getValue("registeredId").link).size());
    }

    @Test
    void coldParserSupportsImmutableContainersInsideAnOrdinaryBean() {
        com.landawn.abacus.parser.ParserFactory.registerKryo(ColdParserMarker.class);
        final Value source = new Value(1, "root");
        final Value child = new Value(2, "child");
        source.link = ImmutableList.of(child);
        values.put("beanRoot", source);
        dao.getValue("beanRoot");
        child.name = "changed";
        final Value cached = dao.getValue("beanRoot");
        assertNotSame(source, cached);
        assertEquals("child", ((Value) ((ImmutableList<?>) cached.link).get(0)).name);
    }

    @Test
    void explicitApplicationSerializerWinsAfterTheParserPoolIsRebuilt() {
        final java.util.concurrent.atomic.AtomicInteger copies = new java.util.concurrent.atomic.AtomicInteger();
        com.landawn.abacus.parser.ParserFactory.registerKryo(CustomCollection.class, new com.esotericsoftware.kryo.Serializer<CustomCollection>() {
            @Override
            public CustomCollection copy(final com.esotericsoftware.kryo.Kryo kryo, final CustomCollection original) {
                copies.incrementAndGet();
                final List<Value> backing = new ArrayList<>();
                final CustomCollection copy = new CustomCollection(backing);
                kryo.reference(copy);
                original.forEach(value -> backing.add(kryo.copy(value)));
                return copy;
            }

            @Override
            public void write(final com.esotericsoftware.kryo.Kryo kryo, final com.esotericsoftware.kryo.io.Output output, final CustomCollection object) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CustomCollection read(final com.esotericsoftware.kryo.Kryo kryo, final com.esotericsoftware.kryo.io.Input input,
                    final Class<? extends CustomCollection> type) {
                throw new UnsupportedOperationException();
            }
        });
        values.put("customSerializer", new CustomCollection(newValues()));
        dao.getGenericCollection("customSerializer");
        final Collection<Value> cached = dao.getGenericCollection("customSerializer");
        assertEquals(CustomCollection.class, cached.getClass());
        assertEquals(2, copies.get());
        assertEquals("one", cached.iterator().next().name);
    }

    @Test
    void preservesExplicitStockSerializerImmutableSettingsAndRegistrationIds() throws Exception {
        final com.esotericsoftware.kryo.Kryo kryo = new com.esotericsoftware.kryo.Kryo();
        kryo.setRegistrationRequired(false);
        final com.esotericsoftware.kryo.serializers.CollectionSerializer<Collection<?>> collectionSerializer = new com.esotericsoftware.kryo.serializers.CollectionSerializer<>();
        collectionSerializer.setImmutable(true);
        final com.esotericsoftware.kryo.serializers.MapSerializer<Map<?, ?>> mapSerializer = new com.esotericsoftware.kryo.serializers.MapSerializer<>();
        mapSerializer.setImmutable(true);
        final com.esotericsoftware.kryo.Registration collectionRegistration = kryo.register(ImmutableCollection.class, collectionSerializer, 2000002);
        final com.esotericsoftware.kryo.Registration mapRegistration = kryo.register(ImmutableMap.class, mapSerializer, 2000003);

        // Install DAO defaults in an isolated Kryo without altering ParserFactory's global registrations.
        // The probe copies an ordinary list: stock collection/map copy overrides need not honor isImmutable.
        final java.lang.reflect.Constructor<?> envelope = Class.forName("com.landawn.abacus.jdbc.DaoImpl$KryoCacheCopy").getDeclaredConstructor(Object.class);
        envelope.setAccessible(true);
        final ArrayList<String> source = new ArrayList<>(List.of("value"));
        final Object copied = kryo.copy(envelope.newInstance(source));
        assertEquals(source, copied);
        assertNotSame(source, copied);
        assertSame(collectionRegistration, kryo.getClassResolver().getRegistration(ImmutableCollection.class));
        assertSame(mapRegistration, kryo.getClassResolver().getRegistration(ImmutableMap.class));
        assertSame(collectionSerializer, collectionRegistration.getSerializer());
        assertSame(mapSerializer, mapRegistration.getSerializer());
        assertEquals(2000002, collectionRegistration.getId());
        assertEquals(2000003, mapRegistration.getId());
        assertEquals(true, collectionRegistration.getSerializer().isImmutable());
        assertEquals(true, mapRegistration.getSerializer().isImmutable());
    }

    @Test
    void cacheCopyDefaultsDoNotChangeIndependentKryoParsers() {
        values.put("independent", ImmutableList.wrap(newValues()));
        dao.getList("independent");
        final com.landawn.abacus.parser.KryoParser independent = com.landawn.abacus.parser.ParserFactory.createKryoParser();
        assertThrows(com.esotericsoftware.kryo.KryoException.class, () -> independent.deepCopy(ImmutableCollection.wrap(newValues())));
        final ArrayList<String> source = new ArrayList<>(List.of("still serializable"));
        assertEquals(source, independent.deserialize(independent.serialize(source), ArrayList.class));
    }

    @Test
    void preservesElementAliasesAndCyclesInEachSnapshot() {
        final Value source = new Value(1, "one");
        source.link = source;
        values.put("aliases", ImmutableList.of(source, source));
        dao.getList("aliases");
        final ImmutableList<Value> firstCopy = dao.getList("aliases");
        final ImmutableList<Value> secondCopy = dao.getList("aliases");
        assertSame(firstCopy.get(0), firstCopy.get(1));
        assertSame(firstCopy.get(0), firstCopy.get(0).link);
        assertNotSame(source, firstCopy.get(0));
        assertNotSame(firstCopy.get(0), secondCopy.get(0));
    }

    @Test
    void preservesRootListCycles() {
        final List<Object> backing = new ArrayList<>();
        final ImmutableList<Object> source = ImmutableList.wrap(backing);
        backing.add(source);
        values.put("listCycle", source);
        dao.getObjects("listCycle");
        final ImmutableList<Object> copy = dao.getObjects("listCycle");
        assertNotSame(source, copy);
        assertSame(copy, copy.get(0));
    }

    @Test
    void preservesSublistCyclesAfterItsBackingListFinishesCopying() {
        final List<Object> backing = new ArrayList<>(List.of("before", "placeholder", "after"));
        final ImmutableList<Object> source = ImmutableList.wrap(backing).subList(1, 2);
        backing.set(1, source);
        values.put("sublistCycle", source);
        dao.getObjects("sublistCycle");
        final ImmutableList<Object> copy = dao.getObjects("sublistCycle");
        assertSame(copy, copy.get(0));
        assertEquals(1, copy.size());
    }

    @Test
    void preservesRootMapCyclesAndBeanBackReferences() {
        final Map<String, Object> backing = new LinkedHashMap<>();
        final ImmutableMap<String, Object> source = ImmutableMap.wrap(backing);
        final Value value = new Value(1, "one");
        value.link = source;
        backing.put("self", source);
        backing.put("value", value);
        values.put("mapCycle", source);
        dao.getObjectMap("mapCycle");
        final ImmutableMap<String, Object> copy = dao.getObjectMap("mapCycle");
        assertNotSame(source, copy);
        assertSame(copy, copy.get("self"));
        assertSame(copy, ((Value) copy.get("value")).link);
    }

    @Test
    void snapshotsTheBackingContainerAndSupportsGenericDeclarations() {
        final List<Value> backing = newValues();
        values.put("generic", ImmutableList.wrap(backing));
        dao.<Value> getGenericCollection("generic");
        backing.clear();
        final Collection<Value> cached = dao.getGenericCollection("generic");
        assertEquals(2, cached.size());
        assertEquals("one", cached.iterator().next().name);
        assertThrows(UnsupportedOperationException.class, cached::clear);
    }

    @Test
    void emptySortedContainersRetainTheirComparator() {
        final TreeSet<Value> set = sortedValues();
        set.clear();
        values.put("emptySet", ImmutableNavigableSet.wrap(set));
        dao.getNavigableSet("emptySet");
        assertSame(set.comparator(), dao.getNavigableSet("emptySet").comparator());

        final TreeMap<Long, Value> map = sortedMappedValues();
        map.clear();
        values.put("emptyMap", ImmutableNavigableMap.wrap(map));
        dao.getNavigableMap("emptyMap");
        assertSame(map.comparator(), dao.getNavigableMap("emptyMap").comparator());
    }

    private static void checkCollection(final String key, final Collection<Value> source, final Supplier<? extends Collection<Value>> lookup) {
        values.put(key, source);
        final List<Long> order = source.stream().map(Value::getId).toList();
        final long firstId = source.iterator().next().id;
        final String firstName = source.iterator().next().name;
        findById(lookup.get(), firstId).name = "changed after write";
        final Collection<Value> cached = lookup.get();
        assertEquals(source.size(), cached.size());
        assertEquals(firstName, findById(cached, firstId).name);
        // Every source here is ordered (lists, sorted sets, LinkedHashSet-backed sets), so the copy must iterate
        // the same way; the elements use identity hash codes, so a hash-ordered copy would not.
        assertEquals(order, cached.stream().map(Value::getId).toList());
        assertNotSame(source, cached);
        assertThrows(UnsupportedOperationException.class, cached::clear);
        findById(cached, firstId).name = "changed after read";
        assertEquals(firstName, findById(lookup.get(), firstId).name);
    }

    private static Value findById(final Collection<Value> values, final long id) {
        return values.stream().filter(value -> value.id == id).findFirst().orElseThrow();
    }

    private static void checkMap(final String key, final Map<Long, Value> source, final Supplier<? extends Map<Long, Value>> lookup) {
        values.put(key, source);
        lookup.get().get(1L).name = "changed after write";
        final Map<Long, Value> cached = lookup.get();
        assertEquals("one", cached.get(1L).name);
        // Every source here is ordered and the keys are inserted in reverse hash order, so a hash-ordered copy
        // would iterate differently.
        assertEquals(new ArrayList<>(source.keySet()), new ArrayList<>(cached.keySet()));
        assertNotSame(source, cached);
        assertThrows(UnsupportedOperationException.class, cached::clear);
        cached.get(1L).name = "changed after read";
        assertEquals("one", lookup.get().get(1L).name);
    }

    private static List<Value> newValues() {
        return new ArrayList<>(List.of(new Value(1, "one"), new Value(2, "two")));
    }

    private static TreeSet<Value> sortedValues() {
        final TreeSet<Value> result = new TreeSet<>(Comparator.comparingLong(Value::getId).reversed());
        result.addAll(newValues());
        return result;
    }

    private static Map<Long, Value> mappedValues() {
        // Inserted in reverse hash order (2, then 1) so that a hash-ordered copy is distinguishable.
        final Map<Long, Value> result = new LinkedHashMap<>();
        newValues().reversed().forEach(value -> result.put(value.id, value));
        return result;
    }

    private static TreeMap<Long, Value> sortedMappedValues() {
        final TreeMap<Long, Value> result = new TreeMap<>(Comparator.reverseOrder());
        result.putAll(mappedValues());
        return result;
    }
}
