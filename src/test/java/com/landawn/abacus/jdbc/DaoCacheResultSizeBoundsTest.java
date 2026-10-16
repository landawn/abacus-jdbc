package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;

/**
 * Regression tests for {@code @CacheResult(minSize, maxSize)}. The bounds used to apply only to {@code Collection} and
 * {@code Dataset} results, so a {@code Map} result (e.g. from {@code @MappedByKey}) or an object array of any size was
 * always cached and {@code maxSize} did not guard its memory. A primitive array such as a {@code byte[]} column value is
 * a single value and stays unbounded.
 */
@Tag("integration")
@Tag("2025")
class DaoCacheResultSizeBoundsTest {
    private static final AtomicInteger calls = new AtomicInteger();
    private static com.zaxxer.hikari.HikariDataSource dataSource;
    private static BoundedDao dao;

    @BeforeAll
    static void setUp() {
        dataSource = (com.zaxxer.hikari.HikariDataSource) JdbcUtil.createHikariDataSource("jdbc:h2:mem:cache_result_size_bounds", "sa", "");
        dao = JdbcUtil.createDao(BoundedDao.class, dataSource);
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
    }

    @BeforeEach
    void resetCalls() {
        calls.set(0);
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

    static List<String> values(final int size) {
        final List<String> list = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            list.add("v" + i);
        }

        return list;
    }

    public interface BoundedDao extends NonUpdateCrudDao<Value, Long, BoundedDao> {
        @CacheResult(enabled = true, minSize = 2, maxSize = 3)
        default List<String> getList(final int size) {
            calls.incrementAndGet();
            return DaoCacheResultSizeBoundsTest.values(size);
        }

        @CacheResult(enabled = true, minSize = 2, maxSize = 3)
        default Map<String, Integer> getMap(final int size) {
            calls.incrementAndGet();
            final Map<String, Integer> map = new LinkedHashMap<>();

            for (int i = 0; i < size; i++) {
                map.put("k" + i, i);
            }

            return map;
        }

        @CacheResult(enabled = true, minSize = 2, maxSize = 3)
        default String[] getArray(final int size) {
            calls.incrementAndGet();
            return DaoCacheResultSizeBoundsTest.values(size).toArray(new String[0]);
        }

        @CacheResult(enabled = true, minSize = 2, maxSize = 3)
        default byte[] getBytes(final int size) {
            calls.incrementAndGet();
            return new byte[size];
        }
    }

    /**
     * Calls the method twice with the same size and returns how many times its body ran: 1 if the first result was
     * cached, 2 if it was not.
     */
    private static int bodyRunsForTwoCalls(final int size, final IntFunction<Object> method) {
        calls.set(0);
        method.apply(size);
        method.apply(size);
        return calls.get();
    }

    @Test
    void collectionResultsAreBounded() {
        assertEquals(2, bodyRunsForTwoCalls(1, dao::getList), "below minSize");
        assertEquals(1, bodyRunsForTwoCalls(2, dao::getList), "within bounds");
        assertEquals(2, bodyRunsForTwoCalls(4, dao::getList), "above maxSize");
    }

    @Test
    void mapResultsAreBoundedByTheirEntryCount() {
        assertEquals(2, bodyRunsForTwoCalls(1, dao::getMap), "below minSize");
        assertEquals(1, bodyRunsForTwoCalls(3, dao::getMap), "within bounds");
        assertEquals(2, bodyRunsForTwoCalls(4, dao::getMap), "above maxSize");
    }

    @Test
    void objectArrayResultsAreBoundedByTheirLength() {
        assertEquals(2, bodyRunsForTwoCalls(1, dao::getArray), "below minSize");
        assertEquals(1, bodyRunsForTwoCalls(2, dao::getArray), "within bounds");
        assertEquals(2, bodyRunsForTwoCalls(4, dao::getArray), "above maxSize");
    }

    @Test
    void primitiveArrayResultsAreSingleValuesAndNotBounded() {
        assertEquals(1, bodyRunsForTwoCalls(1, dao::getBytes), "shorter than minSize");
        assertEquals(1, bodyRunsForTwoCalls(100, dao::getBytes), "longer than maxSize");
    }
}
