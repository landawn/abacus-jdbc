package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.RefreshCache;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;

/**
 * Type-level {@code @CacheResult} and {@code @RefreshCache} annotations on several interfaces of a DAO apply like
 * {@code @PerfLog}/{@code @SqlLogEnabled}: a method uses the first one, in interface order (the DAO interface first),
 * whose filter matches its name. The first annotation found used to be applied whatever its filter, so a super-interface's
 * annotation never applied to a method that only its filter matched.
 *
 * <p>Each test changes the table between two calls: an unchanged second result proves it was served from the cache.</p>
 */
@Tag("integration")
@Tag("2025")
class DaoCacheTypeLevelFilterTest {
    private static com.zaxxer.hikari.HikariDataSource dataSource;
    private static FilterRowDao dao;

    @BeforeAll
    static void setUp() {
        dataSource = (com.zaxxer.hikari.HikariDataSource) JdbcUtil.createHikariDataSource("jdbc:h2:mem:cache_type_level_filter;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @BeforeEach
    void resetTableAndCache() throws SQLException {
        // A new cache, and so a new DAO proxy, per test: entries cached by another test must not be served.
        dao = JdbcUtil.createDao(FilterRowDao.class, dataSource, JdbcUtil.DaoCreationOptions.builder().cache(Jdbc.DaoCache.createByMap()).build());

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS filter_row");
            st.execute("CREATE TABLE filter_row (id BIGINT PRIMARY KEY, name VARCHAR(64))");
            st.execute("INSERT INTO filter_row (id, name) VALUES (1, 'a')");
        }
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    public static class FilterRow {
        @Id
        private long id;
        private String name;

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

    @CacheResult(enabled = true, filter = { "findInherited", "findOptedOut" })
    @RefreshCache(filter = "addRow")
    public interface BaseFilterRowDao<TD extends BaseFilterRowDao<TD>> extends NonUpdateCrudDao<FilterRow, Long, TD> {
        @Query("SELECT COUNT(*) FROM filter_row")
        int findInheritedCount() throws SQLException;

        @Query("SELECT COUNT(*) FROM filter_row")
        int findOptedOutCount() throws SQLException;

        @Query("SELECT COUNT(*) FROM filter_row")
        int findOwnCount() throws SQLException;

        @Query("INSERT INTO filter_row (id, name) VALUES (:id, :name)")
        void addRow(@Bind("id") long id, @Bind("name") String name) throws SQLException;
    }

    // The DAO interface's own annotations come first; their filters match other methods only, except the disabled one.
    @CacheResult(enabled = true, filter = "findOwn")
    @RefreshCache(filter = "insertNothing")
    public interface FilterRowDao extends OptOutFilterRowDao, BaseFilterRowDao<FilterRowDao> {
    }

    @CacheResult(enabled = false, filter = "findOptedOut")
    public interface OptOutFilterRowDao {
    }

    @Test
    void superInterfaceAnnotationAppliesToTheMethodsOnlyItsFilterMatches() throws SQLException {
        assertEquals(1, dao.findInheritedCount());
        execute("INSERT INTO filter_row (id, name) VALUES (2, 'b')");
        assertEquals(1, dao.findInheritedCount(), "cached through the super-interface's @CacheResult");
    }

    // Guard: the DAO interface's own annotation still applies to the methods its filter matches.
    @Test
    void ownAnnotationStillApplies() throws SQLException {
        assertEquals(1, dao.findOwnCount());
        execute("INSERT INTO filter_row (id, name) VALUES (2, 'b')");
        assertEquals(1, dao.findOwnCount());
    }

    // The first matching annotation applies even when it is disabled: it opts its methods out of a later one.
    @Test
    void firstMatchingDisabledAnnotationOptsOut() throws SQLException {
        assertEquals(1, dao.findOptedOutCount());
        execute("INSERT INTO filter_row (id, name) VALUES (2, 'b')");
        assertEquals(2, dao.findOptedOutCount());
    }

    @Test
    void superInterfaceRefreshCacheAppliesToTheMethodsOnlyItsFilterMatches() throws SQLException {
        assertEquals(1, dao.findOwnCount());
        dao.addRow(2, "b");
        assertEquals(2, dao.findOwnCount(), "addRow must invalidate through the super-interface's @RefreshCache");
    }
}
