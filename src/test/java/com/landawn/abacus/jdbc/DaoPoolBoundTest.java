package com.landawn.abacus.jdbc;

import static com.landawn.abacus.query.Dsl.PSC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.annotation.Cache;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.DaoBase;
import com.landawn.abacus.jdbc.dao.JoinEntityHelperIntegrationTest;
import com.landawn.abacus.jdbc.dao.ReadOnlyCrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedCrudJoinEntityHelper;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple3;

/**
 * Tests for the bounded process-wide DAO proxy pool of {@link DaoImpl}: per-call {@code createDao} with new data sources
 * (or other configuration objects) must not accumulate proxies, while evicted proxies keep working and what their
 * creation registered or allocated is released.
 *
 * <p>Each test runs against an emptied pool with a small capacity and restores the pool's previous entries afterwards,
 * so it neither evicts nor sees the DAOs of other test classes.</p>
 */
class DaoPoolBoundTest extends TestBase {

    public static class PooledEntity {
        @Id
        private long id;

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }
    }

    interface PooledEntityDao extends CrudDao<PooledEntity, Long, PooledEntityDao> {
    }

    /** A cache that records how often it is closed. */
    public static final class RecordingCache implements Jdbc.DaoCache, AutoCloseable {
        static final AtomicInteger created = new AtomicInteger();
        static final AtomicInteger closed = new AtomicInteger();

        public RecordingCache(final int capacity, final long evictDelay) {
            created.incrementAndGet();
        }

        @Override
        public Object get(final String defaultCacheKey, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            return null;
        }

        @Override
        public boolean put(final String defaultCacheKey, final Object result, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            return false;
        }

        @Override
        public boolean put(final String defaultCacheKey, final Object result, final long liveTime, final long maxIdleTime, final Object daoProxy,
                final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            return false;
        }

        @Override
        public void update(final String defaultCacheKey, final Object result, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }

    @Cache(impl = RecordingCache.class)
    interface CachedPooledEntityDao extends ReadOnlyCrudDao<PooledEntity, Long, CachedPooledEntityDao> {
    }

    // Used only by this class, so no other test shares its retained join metadata.
    public interface PooledJoinUserDao extends UncheckedCrudDao<JoinEntityHelperIntegrationTest.JoinUser, Long, PooledJoinUserDao>,
            UncheckedCrudJoinEntityHelper<JoinEntityHelperIntegrationTest.JoinUser, Long, PooledJoinUserDao> {
    }

    @Test
    void poolIsBoundedAndKeepsRecentlyUsedProxies() throws Exception {
        withIsolatedDaoPool(2, () -> {
            final DataSource ds1 = mockDataSource();
            final DataSource ds2 = mockDataSource();
            final PooledEntityDao dao1 = createDao(PooledEntityDao.class, ds1);
            final PooledEntityDao dao2 = createDao(PooledEntityDao.class, ds2);

            // A repeated call returns the pooled proxy and marks it as recently used.
            assertSame(dao1, createDao(PooledEntityDao.class, ds1));

            // Per-call data sources: the pool stays within its capacity.
            final List<PooledEntityDao> created = new ArrayList<>();

            for (int i = 0; i < 10; i++) {
                created.add(createDao(PooledEntityDao.class, mockDataSource()));
                assertTrue(pooledDaos().size() <= 2, "pool size: " + pooledDaos().size());
            }

            // dao2 was evicted first (dao1 got a second chance). A new call creates a new proxy, while the evicted one keeps working.
            assertFalse(pooledDaos().contains(dao2));
            final PooledEntityDao recreated = createDao(PooledEntityDao.class, ds2);
            assertNotSame(dao2, recreated);
            assertSame(recreated, createDao(PooledEntityDao.class, ds2));
            assertSame(ds2, dao2.dataSource());
            assertSame(PooledEntity.class, dao2.targetEntityClass());
            assertEquals("pooled_entity", dao2.targetTableName());
            assertTrue(created.stream().allMatch(dao -> dao.targetEntityClass() == PooledEntity.class));
        });
    }

    @Test
    void evictedProxyIsNoLongerResolvedForJoins() throws Exception {
        withIsolatedDaoPool(1, () -> {
            final DataSource ds = mockDataSource();
            final PooledEntityDao dao = createDao(PooledEntityDao.class, ds);
            final PooledEntityDao fallback = createDao(PooledEntityDao.class, mockDataSource()); // evicts dao

            assertFalse(pooledDaos().contains(dao));
            assertSame(fallback, DaoImpl.getApplicableDaoForJoinEntity(PooledEntity.class, ds, fallback));

            // Resolved while pooled, then evicted: the join DAO cache no longer retains (or returns) it.
            final DataSource ds2 = mockDataSource();
            final PooledEntityDao dao2 = createDao(PooledEntityDao.class, ds2); // evicts fallback
            assertSame(dao2, DaoImpl.getApplicableDaoForJoinEntity(PooledEntity.class, ds2, fallback));
            assertTrue(joinEntityDaoPool().containsValue(dao2));

            createDao(PooledEntityDao.class, mockDataSource()); // evicts dao2

            assertFalse(joinEntityDaoPool().containsValue(dao2));
            assertSame(fallback, DaoImpl.getApplicableDaoForJoinEntity(PooledEntity.class, ds2, fallback));
        });
    }

    @Test
    void cacheCreatedForAnEvictedProxyIsClosedOnlyOnceTheProxyIsUnreachable() throws Exception {
        withIsolatedDaoPool(1, () -> {
            final int closedBefore = RecordingCache.closed.get();
            final int createdBefore = RecordingCache.created.get();
            CachedPooledEntityDao dao = createDao(CachedPooledEntityDao.class, mockDataSource());
            assertEquals(createdBefore + 1, RecordingCache.created.get());

            createDao(PooledEntityDao.class, mockDataSource()); // evicts dao
            assertFalse(pooledDaos().contains(dao));

            // Still in use: its cache must stay open.
            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(50);
            }

            assertEquals(closedBefore, RecordingCache.closed.get());
            assertSame(PooledEntity.class, dao.targetEntityClass());

            dao = null; // NOSONAR

            for (int i = 0; i < 200 && RecordingCache.closed.get() == closedBefore; i++) {
                System.gc();
                Thread.sleep(50);
            }

            assertEquals(closedBefore + 1, RecordingCache.closed.get(), "the cache created for an unreachable evicted proxy must be closed");
        });
    }

    @Test
    void cacheOfAPooledProxyIsNotClosed() throws Exception {
        withIsolatedDaoPool(4, () -> {
            final int closedBefore = RecordingCache.closed.get();
            createDao(CachedPooledEntityDao.class, mockDataSource()); // stays pooled although the caller drops it

            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(50);
            }

            assertEquals(closedBefore, RecordingCache.closed.get());
        });
    }

    @Test
    void evictedJoinDaoReleasesItsRetainedJoinMetadata() throws Exception {
        withIsolatedDaoPool(2, () -> {
            final PooledJoinUserDao dao1 = JdbcUtil.createDao(PooledJoinUserDao.class, h2DataSource());
            final PooledJoinUserDao dao2 = JdbcUtil.createDao(PooledJoinUserDao.class, h2DataSource());
            final Object key = Tuple.of(PooledJoinUserDao.class, dao1.targetEntityClass(), dao1.targetTableName());

            assertTrue(daoJoinMetadataPool().containsKey(key));

            JdbcUtil.createDao(PooledEntityDao.class, mockDataSource()); // evicts dao1; dao2 still retains the metadata
            assertFalse(pooledDaos().contains(dao1));
            assertTrue(daoJoinMetadataPool().containsKey(key));

            JdbcUtil.createDao(PooledEntityDao.class, mockDataSource()); // evicts dao2
            assertFalse(pooledDaos().contains(dao2));
            assertFalse(daoJoinMetadataPool().containsKey(key), "the last evicted proxy must release the retained join metadata");

            // Both evicted proxies keep their own join metadata.
            assertFalse(JoinInfo.getEntityJoinInfo(dao1.targetDaoInterface(), dao1.targetEntityClass(), dao1.targetTableName()).isEmpty());
            assertEquals(dao2.targetTableName(), dao1.targetTableName());
        });
    }

    private static <T extends DaoBase> T createDao(final Class<T> daoInterface, final DataSource ds) {
        return DaoImpl.createDao(daoInterface, null, ds, PSC, null, null, null);
    }

    /**
     * Runs {@code body} against an empty DAO pool of the given capacity, then restores the pool's previous entries,
     * queue and capacity.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void withIsolatedDaoPool(final int capacity, final Throwables.Runnable<Exception> body) throws Exception {
        final Map pool = (Map) staticField(DaoImpl.class, "daoPool");
        final ArrayDeque queue = (ArrayDeque) staticField(DaoImpl.class, "daoPoolQueue");
        final Map saved;
        final List savedQueue;
        final int previousCapacity;

        synchronized (queue) {
            saved = new LinkedHashMap(pool);
            savedQueue = new ArrayList(queue);
            pool.clear();
            queue.clear();
            previousCapacity = DaoImpl.setDaoPoolCapacity(capacity);
        }

        try {
            body.run();
        } finally {
            synchronized (queue) {
                pool.clear();
                queue.clear();
                pool.putAll(saved);
                queue.addAll(savedQueue);
                DaoImpl.setDaoPoolCapacity(previousCapacity);
            }
        }
    }

    private static List<Object> pooledDaos() throws Exception {
        final List<Object> daos = new ArrayList<>();

        for (final Object pooledDao : ((Map<?, ?>) staticField(DaoImpl.class, "daoPool")).values()) {
            final Field daoField = pooledDao.getClass().getDeclaredField("dao");
            daoField.setAccessible(true);
            daos.add(daoField.get(pooledDao));
        }

        return daos;
    }

    private static Map<?, ?> joinEntityDaoPool() throws Exception {
        return (Map<?, ?>) staticField(DaoImpl.class, "joinEntityDaoPool");
    }

    private static Map<?, ?> daoJoinMetadataPool() throws Exception {
        return (Map<?, ?>) staticField(JoinInfo.class, "daoJoinMetadataPool");
    }

    private static Object staticField(final Class<?> cls, final String name) throws Exception {
        final Field field = cls.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static DataSource mockDataSource() throws SQLException {
        final DataSource ds = mock(DataSource.class);
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData meta = mock(DatabaseMetaData.class);

        org.mockito.Mockito.when(ds.getConnection()).thenReturn(conn);
        org.mockito.Mockito.when(conn.getMetaData()).thenReturn(meta);
        org.mockito.Mockito.when(meta.getDatabaseProductName()).thenReturn("MySQL");
        org.mockito.Mockito.when(meta.getDatabaseProductVersion()).thenReturn("8.0");

        return ds;
    }

    private static DataSource h2DataSource() {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dao_pool_bound_" + System.nanoTime());
        return ds;
    }
}
