package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.dao.ReadOnlyCrudDao;

/**
 * DAO result-cache keys identify the DAO proxy, and with it its interface and {@code DataSource}: a cache shared by
 * several DAOs (the thread-local cache of {@link JdbcUtil#openDaoCacheScope()}, or one cache passed to several
 * {@code createDao} calls) must never return a result read through another DAO or {@code DataSource}.
 *
 * <p>The two databases hold different rows under the same ids and table name.</p>
 */
@Tag("integration")
@Tag("2025")
class DaoCacheKeyIdentityTest {
    private static com.zaxxer.hikari.HikariDataSource dsA;
    private static com.zaxxer.hikari.HikariDataSource dsB;

    @BeforeAll
    static void setUp() throws SQLException {
        dsA = (com.zaxxer.hikari.HikariDataSource) JdbcUtil.createHikariDataSource("jdbc:h2:mem:cache_key_identity_a;DB_CLOSE_DELAY=-1", "sa", "");
        dsB = (com.zaxxer.hikari.HikariDataSource) JdbcUtil.createHikariDataSource("jdbc:h2:mem:cache_key_identity_b;DB_CLOSE_DELAY=-1", "sa", "");
        init(dsA, "alice-A");
        init(dsB, "alice-B");
    }

    private static void init(final DataSource ds, final String name) throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS identity_user");
            st.execute("CREATE TABLE identity_user (id BIGINT PRIMARY KEY, name VARCHAR(64), email VARCHAR(64))");
            st.execute("INSERT INTO identity_user (id, name, email) VALUES (1, '" + name + "', '" + name + "@example.com')");
        }
    }

    @AfterAll
    static void tearDown() {
        dsA.close();
        dsB.close();
    }

    public static class IdentityUser {
        @Id
        private long id;
        private String name;
        private String email;

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

        public String getEmail() {
            return email;
        }

        public void setEmail(final String email) {
            this.email = email;
        }
    }

    // Another entity on the same table: its DAO inherits the same built-in read methods.
    @Table("identity_user")
    public static class IdentityUserSummary {
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

    public interface IdentityUserDao extends ReadOnlyCrudDao<IdentityUser, Long, IdentityUserDao> {
        @Query("SELECT name FROM identity_user WHERE id = :id")
        String findName(@Bind("id") long id) throws SQLException;

        @CacheResult(enabled = true)
        @Query("SELECT name FROM identity_user WHERE id = :id")
        String findCachedName(@Bind("id") long id) throws SQLException;
    }

    public interface IdentityUserSummaryDao extends ReadOnlyCrudDao<IdentityUserSummary, Long, IdentityUserSummaryDao> {
    }

    @Test
    void threadLocalCacheScopeKeepsDataSourcesApart() throws SQLException {
        final IdentityUserDao daoA = JdbcUtil.createDao(IdentityUserDao.class, dsA);
        final IdentityUserDao daoB = JdbcUtil.createDao(IdentityUserDao.class, dsB);

        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            assertEquals("alice-A", daoA.findName(1));
            assertEquals("alice-B", daoB.findName(1));
            assertEquals("alice-A", daoA.getOrNull(1L).getName());
            assertEquals("alice-B", daoB.getOrNull(1L).getName());

            // Each DAO is still served from the scope's cache.
            assertEquals("alice-A", daoA.findName(1));
            assertEquals("alice-B", daoB.findName(1));
        }
    }

    @Test
    void cacheSharedThroughCreationOptionsKeepsDataSourcesApart() throws SQLException {
        final Jdbc.DaoCache sharedCache = Jdbc.DaoCache.createByMap();
        final IdentityUserDao daoA = JdbcUtil.createDao(IdentityUserDao.class, dsA, JdbcUtil.DaoCreationOptions.builder().cache(sharedCache).build());
        final IdentityUserDao daoB = JdbcUtil.createDao(IdentityUserDao.class, dsB, JdbcUtil.DaoCreationOptions.builder().cache(sharedCache).build());

        assertEquals("alice-A", daoA.findCachedName(1));
        assertEquals("alice-B", daoB.findCachedName(1));
        assertEquals("alice-A", daoA.findCachedName(1));
        assertEquals("alice-B", daoB.findCachedName(1));
        assertEquals(2, ((Jdbc.DaoCacheByMap) sharedCache).cache().size());
    }

    // Built-in reads are declared once (e.g. by CrudReadOps), so the declaring method alone named the same key for
    // every DAO of the same table: a DAO of another entity was handed the first DAO's cached entity.
    @Test
    void threadLocalCacheScopeKeepsDaoInterfacesApart() throws SQLException {
        final IdentityUserDao userDao = JdbcUtil.createDao(IdentityUserDao.class, dsA);
        final IdentityUserSummaryDao summaryDao = JdbcUtil.createDao(IdentityUserSummaryDao.class, dsA);

        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            assertEquals("alice-A@example.com", userDao.getOrNull(1L).getEmail());

            final Object summary = summaryDao.getOrNull(1L);
            assertEquals(IdentityUserSummary.class, summary.getClass());
            assertEquals("alice-A", ((IdentityUserSummary) summary).getName());
            assertNull(userDao.getOrNull(2L));
        }
    }
}
