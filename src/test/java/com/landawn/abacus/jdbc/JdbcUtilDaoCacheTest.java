package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.util.Throwables;

class JdbcUtilDaoCacheTest extends JdbcUtilTestSupport {

    @Test
    public void testScopedExecutionHelpersRejectNullCallbacks() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.runWithSqlLogDisabled((Throwables.Runnable<Exception>) null));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.callWithSqlLogDisabled((Throwables.Callable<Object, Exception>) null));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.runIgnoringSpringTransaction((Throwables.Runnable<Exception>) null));
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.callIgnoringSpringTransaction((Throwables.Callable<Object, Exception>) null));
    }

    @Test
    public void testDaoFactoriesValidateInterfaceAndSourceBeforeOptions() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcUtil.createDao(null, null, (com.landawn.abacus.query.SqlDialect) null)).getMessage()
                .contains("daoInterface"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> JdbcUtil.createDao(com.landawn.abacus.jdbc.dao.DaoBase.class, null, (com.landawn.abacus.query.SqlDialect) null)).getMessage()
                        .contains("ds"));
        verifyNoInteractions(mockDataSource);
    }

    @Test
    public void testOpenDaoCacheScope() {
        assertNull(JdbcUtil.localThreadCache_TL.get());

        final Jdbc.DaoCache scopedCache;

        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            scopedCache = scope.cache();
            assertNotNull(scopedCache);
            assertSame(scopedCache, JdbcUtil.localThreadCache_TL.get());
        }

        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    @Test
    public void testOpenDaoCacheScopeWithCache() {
        final Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap();

        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope(cache)) {
            assertSame(cache, scope.cache());
            assertSame(cache, JdbcUtil.localThreadCache_TL.get());
        }

        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    @Test
    public void testOpenDaoCacheScopeDoesNotClearCallerOwnedCache() {
        final Map<String, Object> backingMap = new java.util.concurrent.ConcurrentHashMap<>();
        backingMap.put("before", "keep");
        final Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap(backingMap);

        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope(cache)) {
            backingMap.put("during", "keep-too");
            assertSame(cache, scope.cache());
        }

        assertEquals(Map.of("before", "keep", "during", "keep-too"), backingMap);
        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    @Test
    public void testOpenDaoCacheScopeRestoresOuterCache() {
        final Jdbc.DaoCache outerCache = Jdbc.DaoCache.createByMap();
        final Jdbc.DaoCache innerCache = Jdbc.DaoCache.createByMap();

        try (JdbcUtil.DaoCacheScope outerScope = JdbcUtil.openDaoCacheScope(outerCache)) {
            assertSame(outerCache, JdbcUtil.localThreadCache_TL.get());

            try (JdbcUtil.DaoCacheScope innerScope = JdbcUtil.openDaoCacheScope(innerCache)) {
                assertSame(innerCache, JdbcUtil.localThreadCache_TL.get());
            }

            assertSame(outerCache, JdbcUtil.localThreadCache_TL.get());
        }

        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    @Test
    public void testOpenDaoCacheScopeRejectsNullCache() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.openDaoCacheScope(null));
    }

    //    @Test
    //    public void testCreateDao() {
    //        interface TestDao extends Dao {
    //        }
    //
    //        TestDao dao = JdbcUtil.createDao(TestDao.class, mockDataSource);
    //        assertNotNull(dao);
    //    }
    //
    //    @Test
    //    public void testCreateDaoWithSqlMapper() {
    //        interface TestDao extends Dao {}
    //        SqlMapper sqlMapper = mock(SqlMapper.class);
    //
    //        TestDao dao = JdbcUtil.createDao(TestDao.class, mockDataSource, PSC, sqlMapper);
    //        assertNotNull(dao);
    //    }
    //
    //    @Test
    //    public void testCreateDaoWithExecutor() {
    //        interface TestDao extends Dao {}
    //        ExecutorService executor = Executors.newSingleThreadExecutor();
    //
    //        try {
    //            TestDao dao = JdbcUtil.createDao(TestDao.class, mockDataSource, PSC, executor);
    //            assertNotNull(dao);
    //        } finally {
    //            executor.shutdown();
    //        }
    //    }
    //
    //    @Test
    //    public void testCreateDaoWithTableName() {
    //        interface TestDao extends Dao {}
    //
    //        TestDao dao = JdbcUtil.createDao(TestDao.class, "custom_table", mockDataSource, PSC);
    //        assertNotNull(dao);
    //    }

    @Test
    @SuppressWarnings("deprecation")
    public void testStartDaoCacheOnCurrentThread() {
        Jdbc.DaoCache cache = JdbcUtil.openDaoCacheOnCurrentThread();
        assertNotNull(cache);
        // The thread-local should now hold the cache
        assertNotNull(JdbcUtil.localThreadCache_TL.get(), "thread-local cache should be set after open");
        assertEquals(cache, JdbcUtil.localThreadCache_TL.get(), "thread-local cache should be the returned instance");

        JdbcUtil.closeDaoCacheOnCurrentThread();
        // After closing, the thread-local should be cleared
        assertNull(JdbcUtil.localThreadCache_TL.get(), "thread-local cache should be null after close");
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testStartDaoCacheOnCurrentThreadWithCache() {
        Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap();
        Jdbc.DaoCache result = JdbcUtil.openDaoCacheOnCurrentThread(cache);

        assertEquals(cache, result);

        JdbcUtil.closeDaoCacheOnCurrentThread();
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testLegacyDaoCacheLifecycleRejectedInsideScope() {
        final Jdbc.DaoCache legacyCache = JdbcUtil.openDaoCacheOnCurrentThread();

        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            assertThrows(IllegalStateException.class, JdbcUtil::openDaoCacheOnCurrentThread);
            assertThrows(IllegalStateException.class, () -> JdbcUtil.openDaoCacheOnCurrentThread(Jdbc.DaoCache.createByMap()));
            assertThrows(IllegalStateException.class, JdbcUtil::closeDaoCacheOnCurrentThread);
            assertSame(scope.cache(), JdbcUtil.localThreadCache_TL.get());
        }

        assertSame(legacyCache, JdbcUtil.localThreadCache_TL.get());
        JdbcUtil.closeDaoCacheOnCurrentThread();
        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testCloseDaoCacheOnCurrentThread() {
        JdbcUtil.openDaoCacheOnCurrentThread();
        JdbcUtil.closeDaoCacheOnCurrentThread();
        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    // createCacheKey builds a non-null key embedding the method name and table.
    @Test
    public void testCreateCacheKey() {
        final Logger logger = LoggerFactory.getLogger(JdbcUtilTest.class);
        final String key = JdbcUtil.createCacheKey("user_account", "UserDao.list", new Object[] { "Alice", 1, List.of(1, 2) }, logger);
        assertNotNull(key);
        assertTrue(key.startsWith("UserDao.list#user_account#"));
    }

    // createCacheKey tolerates null args (treated as an empty argument array).
    @Test
    public void testCreateCacheKey_NullArgs() {
        final Logger logger = LoggerFactory.getLogger(JdbcUtilTest.class);
        final String key = JdbcUtil.createCacheKey("t", "Dao.m", null, logger);
        assertNotNull(key);
        assertTrue(key.startsWith("Dao.m#t#"));
    }

    @Test
    public void testLegacyCacheOpeningChecksScopeBeforeCacheArgument() {
        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            assertThrows(IllegalStateException.class, () -> JdbcUtil.openDaoCacheOnCurrentThread(null));
            assertSame(scope.cache(), JdbcUtil.localThreadCache_TL.get());
        }
    }

    @Test
    public void testDaoCacheScopesRejectOutOfOrderClose() {
        final JdbcUtil.DaoCacheScope outerScope = JdbcUtil.openDaoCacheScope();
        final JdbcUtil.DaoCacheScope innerScope = JdbcUtil.openDaoCacheScope();

        try {
            final IllegalStateException thrown = assertThrows(IllegalStateException.class, outerScope::close);
            assertTrue(thrown.getMessage().contains("reverse order"));
            assertSame(innerScope.cache(), JdbcUtil.localThreadCache_TL.get());
        } finally {
            innerScope.close();
            outerScope.close();
        }

        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    @Test
    public void testDaoCacheScopesRejectOutOfOrderCloseWithSameCache() {
        final Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap();
        final JdbcUtil.DaoCacheScope outerScope = JdbcUtil.openDaoCacheScope(cache);
        final JdbcUtil.DaoCacheScope innerScope = JdbcUtil.openDaoCacheScope(cache);

        try {
            assertThrows(IllegalStateException.class, outerScope::close);
            assertSame(cache, JdbcUtil.localThreadCache_TL.get());
        } finally {
            innerScope.close();
            outerScope.close();
        }

        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    @Test
    public void testDaoCacheScopeRejectsCloseFromDifferentThread() throws InterruptedException {
        final JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        final Thread otherThread = new Thread(() -> {
            try {
                scope.close();
            } catch (final Throwable e) {
                failure.set(e);
            }
        });

        try {
            otherThread.start();
            otherThread.join();

            assertTrue(failure.get() instanceof IllegalStateException);
            assertSame(scope.cache(), JdbcUtil.localThreadCache_TL.get());
        } finally {
            scope.close();
        }

        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    @Test
    public void testDaoCacheScopeCloseIsIdempotent() {
        final JdbcUtil.DaoCacheScope outerScope = JdbcUtil.openDaoCacheScope();
        final Jdbc.DaoCache previousCache = outerScope.cache();
        final JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope();

        scope.close();
        assertSame(previousCache, JdbcUtil.localThreadCache_TL.get());

        scope.close();
        assertSame(previousCache, JdbcUtil.localThreadCache_TL.get());

        outerScope.close();
        assertNull(JdbcUtil.localThreadCache_TL.get());
    }

    @Test
    public void testManualDaoCacheLifecycleMethodsAreDeprecated() throws NoSuchMethodException {
        assertTrue(JdbcUtil.class.getMethod("openDaoCacheOnCurrentThread").isAnnotationPresent(Deprecated.class));
        assertTrue(JdbcUtil.class.getMethod("openDaoCacheOnCurrentThread", Jdbc.DaoCache.class).isAnnotationPresent(Deprecated.class));
        assertTrue(JdbcUtil.class.getMethod("closeDaoCacheOnCurrentThread").isAnnotationPresent(Deprecated.class));
        assertFalse(JdbcUtil.class.getMethod("openDaoCacheScope").isAnnotationPresent(Deprecated.class));
    }
}
