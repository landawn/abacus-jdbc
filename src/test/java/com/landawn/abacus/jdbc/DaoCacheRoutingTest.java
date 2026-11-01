package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.RefreshCache;
import com.landawn.abacus.jdbc.dao.ReadOnlyDao;

class DaoCacheRoutingTest extends TestBase {
    private static final ThreadLocal<DataSource> ROUTE = new ThreadLocal<>();
    private static final ThreadLocal<Map<DataSource, Integer>> CALLS = ThreadLocal.withInitial(IdentityHashMap::new);

    @ParameterizedTest(name = "thread-local cache={0}")
    @ValueSource(booleans = { false, true })
    void cachedInvocation_routeChanges_keepsResultsSeparateByDataSourceIdentity(final boolean localCache) throws SQLException {
        final DataSource first = dataSource();
        final DataSource second = dataSource();
        final DataSource constructionSource = dataSource();
        final Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap();
        ROUTE.set(first);
        CALLS.get().put(second, 100);

        try {
            final RoutedDao dao = JdbcUtil.createDao(RoutedDao.class, constructionSource, JdbcUtil.DaoCreationOptions.builder().cache(cache).build());
            try (JdbcUtil.DaoCacheScope scope = localCache ? JdbcUtil.openDaoCacheScope() : null) {
                assertEquals(1, dao.findValue());
                assertEquals(1, dao.findValue());
                ROUTE.set(second);
                assertEquals(101, dao.findValue(), "The second route must execute rather than reuse the first route's cached value");
                assertEquals(101, CALLS.get().get(second));
                ROUTE.set(first);
                assertEquals(1, dao.findValue());
                assertEquals(1, CALLS.get().get(first), "The first route must keep its own cached result");
                ROUTE.set(constructionSource);
                assertEquals(1, dao.findValue());
                assertEquals(1, CALLS.get().get(constructionSource), "Routing back to the construction source has its own cache entry");
                assertEquals(localCache ? 0 : 3, ((Jdbc.DaoCacheByMap) cache).cache().size());
            }
        } finally {
            ROUTE.remove();
            CALLS.remove();
        }
    }

    @ParameterizedTest(name = "thread-local cache={0}")
    @ValueSource(booleans = { false, true })
    void cachedInvocation_manyLiveRoutes_reusesStableNamespaces(final boolean localCache) throws SQLException {
        final DataSource constructionSource = dataSource();
        final List<DataSource> routes = new ArrayList<>();
        for (int i = 0; i < 66; i++) {
            final DataSource route = dataSource();
            routes.add(route);
            CALLS.get().put(route, i * 100);
        }
        ROUTE.set(routes.get(0));
        final Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap();

        try {
            final RoutedDao dao = JdbcUtil.createDao(RoutedDao.class, constructionSource, JdbcUtil.DaoCreationOptions.builder().cache(cache).build());
            try (JdbcUtil.DaoCacheScope scope = localCache ? JdbcUtil.openDaoCacheScope() : null) {
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i < routes.size(); i++) {
                        ROUTE.set(routes.get(i));
                        assertEquals(i * 100 + 1, dao.findValue());
                        assertEquals(i * 100 + 1, CALLS.get().get(routes.get(i)), "A live route must retain its original cache entry");
                    }
                }
                ROUTE.set(routes.get(0));
                assertEquals(1, dao.findValue());
                assertEquals(localCache ? 0 : routes.size(), ((Jdbc.DaoCacheByMap) cache).cache().size(),
                        "Revisiting routes must not accumulate unreachable result-cache entries");
            }
        } finally {
            ROUTE.remove();
            CALLS.remove();
        }
    }

    @ParameterizedTest(name = "thread-local cache={0}")
    @ValueSource(booleans = { false, true })
    void refreshCache_writeOnOneRoute_invalidatesTableResultsOfEveryRoute(final boolean localCache) throws SQLException {
        final DataSource first = dataSource();
        final DataSource second = dataSource();
        CALLS.get().put(second, 100);
        ROUTE.set(first);

        try {
            final RoutedDao dao = JdbcUtil.createDao(RoutedDao.class, dataSource(),
                    JdbcUtil.DaoCreationOptions.builder().cache(Jdbc.DaoCache.createByMap()).build());
            try (JdbcUtil.DaoCacheScope scope = localCache ? JdbcUtil.openDaoCacheScope() : null) {
                assertEquals(1, dao.findValue());
                ROUTE.set(second);
                assertEquals(101, dao.findValue());
                dao.updateValue();
                assertEquals(102, dao.findValue(), "The writing route must not serve its stale cached result");
                ROUTE.set(first);
                assertEquals(2, dao.findValue(), "Invalidation is per table, so the other route's cached result is dropped too");
            }
        } finally {
            ROUTE.remove();
            CALLS.remove();
        }
    }

    @Test
    void routedCacheIds_liveSources_keepStableDistinctNamespaces() throws SQLException {
        final DaoImpl.RoutedCacheIds ids = new DaoImpl.RoutedCacheIds("RoutedDao@1");
        final DataSource first = dataSource();
        final DataSource second = dataSource();

        final String firstId = ids.get(first);
        assertEquals(firstId, ids.get(first));
        assertNotEquals(firstId, ids.get(second), "Equal but distinct pools need distinct namespaces");
        assertTrue(firstId.startsWith("RoutedDao@1/"), firstId);
        assertEquals(2, ids.size());
    }

    @Test
    void routedCacheIds_collectedSource_isReleasedAndExpunged() throws Exception {
        final DaoImpl.RoutedCacheIds ids = new DaoImpl.RoutedCacheIds("RoutedDao@1");
        final DataSource live = dataSource();
        ids.get(live);
        final WeakReference<DataSource> retired = registerTransientSource(ids);

        for (int attempt = 0; attempt < 50 && retired.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }

        assertNull(retired.get(), "A namespace must not keep a retired data source reachable");
        assertEquals(2, ids.size());

        // Registering another source expunges the collected one.
        ids.get(dataSource());
        assertEquals(2, ids.size());
        assertEquals(ids.get(live), ids.get(live));
    }

    private static WeakReference<DataSource> registerTransientSource(final DaoImpl.RoutedCacheIds ids) throws SQLException {
        final DataSource transientSource = dataSource();
        ids.get(transientSource);
        return new WeakReference<>(transientSource);
    }

    private static DataSource dataSource() throws SQLException {
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseProductVersion()).thenReturn("2");
        return new EqualDataSource(connection);
    }

    interface RoutedDao extends ReadOnlyDao<Value, RoutedDao> {
        @Override
        default DataSource dataSource() {
            return ROUTE.get();
        }

        @RefreshCache
        default void updateValue() {
            // Stands for a write through the current route; the read below counts executions.
        }

        @CacheResult(enabled = true)
        default int findValue() {
            return CALLS.get().merge(dataSource(), 1, Integer::sum);
        }
    }

    public static class Value {
        public int getId() {
            return 1;
        }

        public void setId(final int id) {
        }
    }

    // Deliberately equal pools require identity-based routing; DataSource does not define equality semantics.
    private record EqualDataSource(Connection connection) implements DataSource {
        @Override
        public boolean equals(final Object obj) {
            return obj instanceof EqualDataSource;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public Connection getConnection(final String username, final String password) {
            return connection;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(final PrintWriter writer) {
        }

        @Override
        public void setLoginTimeout(final int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(final Class<T> type) throws SQLException {
            throw new SQLException("Not a wrapper");
        }

        @Override
        public boolean isWrapperFor(final Class<?> type) {
            return false;
        }
    }
}
