package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
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
 * Regression tests for JSON cache copies of {@code java.util.Date} values. JSON writes a plain {@code java.util.Date}
 * as the root value, as a value held by an optional/nullable wrapper and as a map key in its whole-second string form
 * ({@code 1970-01-02T10:17:36Z}), so such a cached date used to come back without its milliseconds. Collection
 * elements, map values and bean properties were already copied exactly (as epoch milliseconds).
 *
 * <p>Each test returns the source result on the first call, then replaces the source, so the second call proves it was
 * served (copied) from the cache.</p>
 */
@Tag("integration")
@Tag("2025")
class DaoCacheJsonDatePrecisionTest {
    // 1970-01-02T10:17:36.789Z: not a whole second.
    private static final long MILLIS = 123456789L;

    private static final Map<String, Object> results = new HashMap<>();
    private static com.zaxxer.hikari.HikariDataSource dataSource;
    private static CacheDao dao;

    @BeforeAll
    static void setUp() {
        dataSource = (com.zaxxer.hikari.HikariDataSource) JdbcUtil.createHikariDataSource("jdbc:h2:mem:json_date_precision", "sa", "");
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

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }
    }

    public static class SubDate extends Date {
        private static final long serialVersionUID = 1L;

        public SubDate() {
        }

        public SubDate(final long time) {
            super(time);
        }
    }

    public static class ReverseDateMap extends TreeMap<Date, String> {
        private static final long serialVersionUID = 1L;

        public ReverseDateMap() {
            super(Collections.reverseOrder());
        }
    }

    @SuppressWarnings("unchecked")
    public interface CacheDao extends NonUpdateCrudDao<Value, Long, CacheDao> {
        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Date getDate(final String key) {
            return (Date) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default SubDate getSubDate(final String key) {
            return (SubDate) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default u.Optional<Date> getOptional(final String key) {
            return (u.Optional<Date>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default <V> u.Nullable<V> getNullable(final String key) {
            return (u.Nullable<V>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.Optional<Date> getJdkOptional(final String key) {
            return (java.util.Optional<Date>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Map<Date, String> getDateKeyed(final String key) {
            return (Map<Date, String>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default <K, V> Map<K, V> getUntyped(final String key) {
            return (Map<K, V>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default List<Map<Date, String>> getNested(final String key) {
            return (List<Map<Date, String>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default TreeMap<Date, String> getSorted(final String key) {
            return (TreeMap<Date, String>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default u.Optional<Map<Date, String>> getOptionalMap(final String key) {
            return (u.Optional<Map<Date, String>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ReverseDateMap getReverseDateMap(final String key) {
            return (ReverseDateMap) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default ImmutableMap<Date, String> getImmutable(final String key) {
            return (ImmutableMap<Date, String>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Map<String, Map<Date, String>> getMapValued(final String key) {
            return (Map<String, Map<Date, String>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Timestamp getTimestamp(final String key) {
            return (Timestamp) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Period getPeriod(final String key) {
            return (Period) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default List<Object> getObjects(final String key) {
            return (List<Object>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default List<ZonedDateTime> getZoned(final String key) {
            return (List<ZonedDateTime>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Set<Instant> getInstants(final String key) {
            return (Set<Instant>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Timestamp[] getTimestampArray(final String key) {
            return (Timestamp[]) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default List<Map<String, Object>> getRows(final String key) {
            return (List<Map<String, Object>>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default Map<Instant, Calendar> getInstantKeyed(final String key) {
            return (Map<Instant, Calendar>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default TreeMap<Timestamp, OffsetDateTime> getSortedTimestampKeyed(final String key) {
            return (TreeMap<Timestamp, OffsetDateTime>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default u.Optional<Timestamp> getOptionalTimestamp(final String key) {
            return (u.Optional<Timestamp>) results.get(key);
        }

        @CacheResult(enabled = true, serialization = CacheSerialization.JSON)
        default java.util.Optional<List<OffsetTime>> getJdkOptionalOffsetTimes(final String key) {
            return (java.util.Optional<List<OffsetTime>>) results.get(key);
        }
    }

    /**
     * Returns the second (cached) result, replacing the source after the first call.
     */
    private static <T> T cached(final String key, final T source, final Supplier<T> call) {
        results.put(key, source);
        assertSame(source, call.get());
        results.put(key, null);
        final T cached = call.get();
        assertNotSame(source, cached);
        return cached;
    }

    private static Map<Date, String> dateKeyedMap(final boolean withNullKey) {
        final Map<Date, String> map = new LinkedHashMap<>();
        map.put(new Date(MILLIS), "a");
        map.put(new Date(MILLIS + 1000), "b");
        if (withNullKey) {
            map.put(null, "none");
        }
        return map;
    }

    @Test
    void rootDateKeepsItsMilliseconds() {
        final Date cached = cached("root", new Date(MILLIS), () -> dao.getDate("root"));

        assertEquals(MILLIS, cached.getTime());
        assertEquals(Date.class, cached.getClass());
    }

    // The copy used to be a plain java.util.Date, which the SubDate-returning method could not return.
    @Test
    void rootDateSubclassKeepsItsMillisecondsAndClass() {
        final SubDate cached = cached("subclass", new SubDate(MILLIS), () -> dao.getSubDate("subclass"));

        assertEquals(SubDate.class, cached.getClass());
        assertEquals(MILLIS, cached.getTime());
    }

    @Test
    void heldDatesKeepTheirMilliseconds() {
        assertEquals(MILLIS, cached("optional", u.Optional.of(new Date(MILLIS)), () -> dao.getOptional("optional")).get().getTime());
        assertEquals(MILLIS, cached("nullable", u.Nullable.of(new Date(MILLIS)), () -> dao.<Date> getNullable("nullable")).get().getTime());
        assertEquals(MILLIS, cached("jdkOptional", java.util.Optional.of(new Date(MILLIS)), () -> dao.getJdkOptional("jdkOptional")).get().getTime());
    }

    @Test
    void mapDateKeysKeepTheirMilliseconds() {
        final Map<Date, String> source = dateKeyedMap(false);
        final Map<Date, String> cached = cached("map", source, () -> dao.getDateKeyed("map"));

        assertEquals(source, cached);
        assertEquals(new ArrayList<>(source.keySet()), new ArrayList<>(cached.keySet()));
        assertEquals("a", cached.get(new Date(MILLIS)));

        // A null key sends the map down the entry-by-entry copy.
        final Map<Date, String> withNullKey = dateKeyedMap(true);
        assertEquals(withNullKey, cached("mapWithNullKey", withNullKey, () -> dao.getDateKeyed("mapWithNullKey")));

        // Without a declared key type, the keys' common runtime class is used.
        assertEquals(source, cached("untyped", source, () -> dao.<Date, String> getUntyped("untyped")));
    }

    @Test
    void nestedAndSortedMapDateKeysKeepTheirMilliseconds() {
        final Map<Date, String> child = dateKeyedMap(false);
        assertEquals(List.of(child), cached("nested", new ArrayList<>(List.of(child)), () -> dao.getNested("nested")));

        final TreeMap<Date, String> sorted = new TreeMap<>(child);
        final TreeMap<Date, String> cachedSorted = cached("sorted", sorted, () -> dao.getSorted("sorted"));
        assertEquals(sorted, cachedSorted);
        assertEquals(MILLIS, cachedSorted.firstKey().getTime());

        assertEquals(child, cached("optionalMap", u.Optional.of(child), () -> dao.getOptionalMap("optionalMap")).get());

        final Map<String, Map<Date, String>> mapValued = new HashMap<>(Map.of("child", child));
        assertEquals(mapValued, cached("mapValued", mapValued, () -> dao.getMapValued("mapValued")));
    }

    // These maps were already rebuilt by the tree copy for their comparator or iteration order, but read their Date keys back
    // from the whole-second JSON form; the custom sorted class then failed its copy verification (the keys compared unequal).
    @Test
    void comparatorSortedAndImmutableMapDateKeysKeepTheirMilliseconds() {
        final TreeMap<Date, String> reversed = new TreeMap<>(Collections.reverseOrder());
        reversed.putAll(dateKeyedMap(false));
        final TreeMap<Date, String> cachedReversed = cached("reversed", reversed, () -> dao.getSorted("reversed"));
        assertEquals(reversed, cachedReversed);
        assertSame(reversed.comparator(), cachedReversed.comparator());
        assertEquals(MILLIS + 1000, cachedReversed.firstKey().getTime());

        final ReverseDateMap custom = new ReverseDateMap();
        custom.putAll(dateKeyedMap(false));
        final ReverseDateMap cachedCustom = cached("custom", custom, () -> dao.getReverseDateMap("custom"));
        assertEquals(custom, cachedCustom);
        assertEquals(new ArrayList<>(custom.keySet()), new ArrayList<>(cachedCustom.keySet()));

        final ImmutableMap<Date, String> immutable = ImmutableMap.wrap(dateKeyedMap(false));
        final ImmutableMap<Date, String> cachedImmutable = cached("immutable", immutable, () -> dao.getImmutable("immutable"));
        assertEquals(immutable, cachedImmutable);
        assertEquals(new ArrayList<>(immutable.keySet()), new ArrayList<>(cachedImmutable.keySet()));
    }

    // A zone other than the JVM default, which a value read back from epoch milliseconds would come back in.
    private static final ZoneId ZONE = ZoneId.systemDefault().equals(ZoneId.of("Asia/Tokyo")) ? ZoneId.of("Europe/Paris") : ZoneId.of("Asia/Tokyo");
    private static final ZoneOffset OFFSET = ZONE.getRules().getOffset(Instant.ofEpochSecond(1_600_000_000L));

    private static Timestamp timestamp(final int nanos) {
        final Timestamp timestamp = new Timestamp(MILLIS);
        timestamp.setNanos(nanos);
        return timestamp;
    }

    private static Calendar calendar() {
        final Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone(ZONE));
        calendar.setTimeInMillis(MILLIS);
        return calendar;
    }

    private static ZonedDateTime zoned() {
        return ZonedDateTime.of(2020, 1, 2, 3, 4, 5, 123_456_789, ZONE);
    }

    private static void assertSameCalendar(final Calendar expected, final Object actual) {
        assertNotSame(expected, actual);
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.getTimeInMillis(), ((Calendar) actual).getTimeInMillis());
        assertEquals(expected.getTimeZone().getID(), ((Calendar) actual).getTimeZone().getID());
    }

    // A Timestamp used to lose its sub-millisecond nanos in every position.
    @Test
    void timestampsKeepTheirNanos() {
        final Timestamp source = timestamp(789_123_456);
        final Timestamp cached = cached("timestamp", source, () -> dao.getTimestamp("timestamp"));
        assertEquals(source, cached);
        assertEquals(789_123_456, cached.getNanos());

        final Timestamp[] array = cached("timestampArray", new Timestamp[] { timestamp(1), timestamp(2) }, () -> dao.getTimestampArray("timestampArray"));
        assertEquals(1, array[0].getNanos());
        assertEquals(2, array[1].getNanos());

        final u.Optional<Timestamp> optional = cached("optionalTimestamp", u.Optional.of(timestamp(3)), () -> dao.getOptionalTimestamp("optionalTimestamp"));
        assertEquals(timestamp(3), optional.get());
    }

    // Inside a collection or as a map value, a Calendar, ZonedDateTime, OffsetDateTime or Instant was written as epoch
    // milliseconds: it lost any sub-millisecond part and came back in the JVM default time zone. Untyped (Object) positions
    // read such values back as numbers, and some java.time types could not be written at all.
    @Test
    void dateTimeValuesInContainersAreCopiedExactly() {
        final OffsetDateTime offsetDateTime = OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 123_456_789, OFFSET);
        final Instant instant = Instant.ofEpochSecond(1_000, 123_456_789);
        final OffsetTime offsetTime = OffsetTime.of(3, 4, 5, 123_456_789, OFFSET);
        final Calendar calendar = calendar();
        final Timestamp timestamp = timestamp(456);
        final List<Object> source = new ArrayList<>(
                List.of(timestamp, calendar, zoned(), offsetDateTime, instant, offsetTime, LocalDate.of(2020, 1, 2), Duration.ofNanos(5), "text"));

        final List<Object> cached = cached("objects", source, () -> dao.getObjects("objects"));
        assertEquals(timestamp, cached.get(0));
        assertNotSame(timestamp, cached.get(0));
        assertSameCalendar(calendar, cached.get(1));
        assertEquals(source.subList(2, source.size()), cached.subList(2, cached.size()));

        assertEquals(List.of(zoned()), cached("zoned", new ArrayList<>(List.of(zoned())), () -> dao.getZoned("zoned")));
        assertEquals(Set.of(instant), cached("instants", new HashSet<>(Set.of(instant)), () -> dao.getInstants("instants")));

        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("name", "a");
        row.put("created", timestamp(789));
        row.put("updated", zoned());
        final List<Map<String, Object>> rows = cached("rows", new ArrayList<>(List.of(row)), () -> dao.getRows("rows"));
        assertEquals(row.keySet(), rows.get(0).keySet());
        assertEquals("a", rows.get(0).get("name"));
        assertEquals(timestamp(789), rows.get(0).get("created"));
        assertEquals(zoned(), rows.get(0).get("updated"));
        // Other untyped values keep the JSON parser's default types.
        assertEquals(1, ((Number) rows.get(0).get("id")).intValue());
    }

    @Test
    void dateTimeMapKeysAndHeldValuesAreCopiedExactly() {
        final Instant instant = Instant.ofEpochSecond(1_000, 123_456_789);
        final Map<Instant, Calendar> instantKeyed = new LinkedHashMap<>();
        instantKeyed.put(instant, calendar());
        instantKeyed.put(instant.plusNanos(1), calendar());
        final Map<Instant, Calendar> cachedInstantKeyed = cached("instantKeyed", instantKeyed, () -> dao.getInstantKeyed("instantKeyed"));
        assertEquals(new ArrayList<>(instantKeyed.keySet()), new ArrayList<>(cachedInstantKeyed.keySet()));
        assertSameCalendar(instantKeyed.get(instant), cachedInstantKeyed.get(instant));

        final OffsetDateTime offsetDateTime = OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 123_456_789, OFFSET);
        final TreeMap<Timestamp, OffsetDateTime> sorted = new TreeMap<>(Collections.reverseOrder());
        sorted.put(timestamp(1), offsetDateTime);
        sorted.put(timestamp(2), offsetDateTime.plusNanos(1));
        final TreeMap<Timestamp, OffsetDateTime> cachedSorted = cached("sortedTimestampKeyed", sorted,
                () -> dao.getSortedTimestampKeyed("sortedTimestampKeyed"));
        assertSame(sorted.comparator(), cachedSorted.comparator());
        assertEquals(new ArrayList<>(sorted.entrySet()), new ArrayList<>(cachedSorted.entrySet()));
        assertEquals(2, cachedSorted.firstKey().getNanos());

        final List<OffsetTime> offsetTimes = List.of(OffsetTime.of(3, 4, 5, 6, OFFSET));
        assertEquals(offsetTimes, cached("jdkOptionalOffsetTimes", java.util.Optional.of(new ArrayList<>(offsetTimes)),
                () -> dao.getJdkOptionalOffsetTimes("jdkOptionalOffsetTimes")).get());
    }

    // An immutable java.time root value is returned as it is; one the JSON parser can't write used to fail the copy.
    @Test
    void rootJavaTimeValueIsShared() {
        final Period period = Period.of(1, 2, 3);
        results.put("period", period);
        assertSame(period, dao.getPeriod("period"));
        results.put("period", null);
        assertSame(period, dao.getPeriod("period"));
    }
}
