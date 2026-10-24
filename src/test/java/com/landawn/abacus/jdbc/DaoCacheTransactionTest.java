package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.RefreshCache;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple.Tuple3;

/**
 * Transaction review, finding 12: the DAO result cache was not transaction-aware. A result read in a transaction was stored
 * in the cache, which other threads share, although it could hold uncommitted work (also after a rollback had discarded it);
 * a cached result was served in a transaction although it missed the transaction's own writes; and a write in a transaction
 * invalidated the cache only right away, before the commit, so another thread could cache the old rows again, which then
 * outlived the commit. The cache is kept out of transactions on the DAO's data source now (a {@code JdbcUtil} transaction, or
 * a Spring-managed one that owns its connection), and such a write invalidates the cache again once the transaction has
 * committed.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DaoCacheTransactionTest extends TestBase {

    private final JdbcDataSource dataSource = new JdbcDataSource();
    private Connection keeper;
    private CachedItemDao dao;

    @BeforeAll
    void createDatabase() throws SQLException {
        dataSource.setURL("jdbc:h2:mem:dao_cache_transaction_" + UUID.randomUUID());
        keeper = dataSource.getConnection();

        try (Statement statement = keeper.createStatement()) {
            statement.execute("CREATE TABLE cache_tx_item (id BIGINT PRIMARY KEY, name VARCHAR(20))");
        }
    }

    @BeforeEach
    void resetRowsAndCache() throws SQLException {
        try (Statement statement = keeper.createStatement()) {
            statement.executeUpdate("DELETE FROM cache_tx_item");
            statement.executeUpdate("INSERT INTO cache_tx_item (id, name) VALUES (1, 'a')");
        }

        dao = JdbcUtil.createDao(CachedItemDao.class, dataSource, JdbcUtil.DaoCreationOptions.builder().cache(Jdbc.DaoCache.createByMap()).build());
    }

    @AfterAll
    void closeDatabase() throws SQLException {
        keeper.close();
    }

    @ParameterizedTest(name = "spring={0}")
    @ValueSource(booleans = { false, true })
    void aResultReadInATransaction_isNotCached(final boolean spring) throws SQLException {
        inTransaction(spring, false, () -> {
            dao.addItem(2);
            assertEquals(2, dao.cachedCount(), "the transaction's own write");
        });

        assertEquals(1, dao.cachedCount(), "the result read in the rolled-back transaction was not cached");
    }

    @ParameterizedTest(name = "spring={0}")
    @ValueSource(booleans = { false, true })
    void aWriteInATransaction_invalidatesTheCacheAgainOnceItHasCommitted(final boolean spring) throws SQLException {
        // From a cold cache (round 17 review: primed, the read below was served the result cached before the write, which a write
        // joining a JdbcUtil transaction no longer invalidates right away, so the store meanwhile was not exercised).
        inTransaction(spring, true, () -> {
            dao.addItem(2);

            // Work outside the transaction (as on another thread) reads the committed rows, and caches the result meanwhile in a
            // JdbcUtil transaction (in a Spring one, the transaction's connection holder keeps the caches out: see the external
            // review tests below).
            assertEquals(1, (int) JdbcUtil.callOutsideTransaction(dataSource, () -> dao.cachedCount()));

            assertEquals(2, dao.cachedCount(), "the transaction's own write, not the cached result");
        });

        assertEquals(2, dao.cachedCount(), "the cache was invalidated again once the transaction had committed");
    }

    // Round 14 review: the cache of an openDaoCacheScope was not kept out of transactions: a result read in a transaction was
    // stored there, and served after the transaction had rolled back.
    @ParameterizedTest(name = "spring={0}")
    @ValueSource(booleans = { false, true })
    void aResultReadInATransaction_isNotCachedByADaoCacheScope(final boolean spring) throws SQLException {
        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            assertEquals(1, dao.countItems()); // cached in the scope

            inTransaction(spring, false, () -> {
                dao.addItem(2);
                assertEquals(2, dao.countItems(), "the transaction's own write");
            });

            assertEquals(1, dao.countItems(), "the result read in the rolled-back transaction was not cached");
        }
    }

    // A write in a transaction invalidates the scope's cache again once the transaction has committed: the cache of a scope
    // may be shared by several threads (openDaoCacheScope(cache)).
    @ParameterizedTest(name = "spring={0}")
    @ValueSource(booleans = { false, true })
    void aWriteInATransaction_invalidatesADaoCacheScopeAgainOnceItHasCommitted(final boolean spring) throws SQLException {
        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            inTransaction(spring, true, () -> {
                dao.addItem(2);

                // Work outside the transaction (as on another thread sharing the cache) reads the committed rows and caches them.
                assertEquals(1, (int) JdbcUtil.callOutsideTransaction(dataSource, () -> dao.countItems()));
            });

            assertEquals(2, dao.countItems(), "the cache was invalidated again once the transaction had committed");
        }
    }

    // Round 14 review: every write in a transaction registered its own update of the cache to run after the commit, holding the
    // write's arguments (a batch's entities) until then: a chunked import in one transaction kept every chunk in memory, and
    // the commit scanned the cache once per chunk. A built-in cache, which invalidates by table, is updated once after the
    // commit now, without the arguments; another cache still once per write, with them. Round 16 review: a write that joins
    // the transaction (a @Query method here) no longer updates the caches right away as well: until the commit it changes
    // nothing other threads can read, and reads in the transaction bypass the caches. Round 17 review: in a JdbcUtil
    // transaction of its own only, whose commit is observed exactly; in a Spring transaction, right away too (see
    // aWriteJoiningAnEnclosingSpringTransactionFromAnotherManagersTransactionThatRollsBack_invalidatesTheCache).
    @ParameterizedTest(name = "spring={0}")
    @ValueSource(booleans = { false, true })
    void writesInATransaction_updateABuiltInCacheOnceAfterTheCommit(final boolean spring) throws Exception {
        final Jdbc.DaoCache builtIn = Mockito.spy(Jdbc.DaoCache.create(100, 60_000));
        final Jdbc.DaoCache custom = Mockito.mock(Jdbc.DaoCache.class);

        try {
            for (final Jdbc.DaoCache cache : List.of(builtIn, custom)) {
                final CachedItemDao cachedDao = JdbcUtil.createDao(CachedItemDao.class, dataSource, JdbcUtil.DaoCreationOptions.builder().cache(cache).build());

                inTransaction(spring, true, () -> {
                    cachedDao.addItem(cache == builtIn ? 2 : 4);
                    cachedDao.addItem(cache == builtIn ? 3 : 5);
                });
            }

            verify(builtIn, times(spring ? 2 : 0)).update(anyString(), any(), any(), notNull(), any()); // right away: in Spring only
            verify(builtIn, times(1)).update(anyString(), isNull(), any(), isNull(), any()); // once after the commit
            // After the commit, once per write (and right away too, in Spring).
            verify(custom, times(spring ? 4 : 2)).update(anyString(), any(), any(), notNull(), any());
        } finally {
            ((AutoCloseable) builtIn).close();
        }
    }

    // External review (P2): a read that fetched the rows before a write, and stored its result after the write's invalidation,
    // kept the old rows cached until they expired (the race of a cache filled on read). A read stores its result only if no
    // invalidation of its table ran since it began, and undoes a store that an invalidation overtook.
    @Test
    void aResultFetchedBeforeAWriteAndStoredAfterItsInvalidation_isNotKept() throws Exception {
        final Jdbc.DaoCache delegate = Jdbc.DaoCache.createByMap();
        final java.util.concurrent.CountDownLatch storing = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch written = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean pauseNextStore = new java.util.concurrent.atomic.AtomicBoolean(true);

        final Jdbc.DaoCache pausing = new Jdbc.DaoCache() {
            @Override
            public Object get(final String key, final Object daoProxy, final Object[] args,
                    final Tuple3<java.lang.reflect.Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return delegate.get(key, daoProxy, args, methodSignature);
            }

            @Override
            public boolean put(final String key, final Object result, final Object daoProxy, final Object[] args,
                    final Tuple3<java.lang.reflect.Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return delegate.put(key, result, daoProxy, args, methodSignature);
            }

            @Override
            public boolean put(final String key, final Object result, final long liveTime, final long maxIdleTime, final Object daoProxy, final Object[] args,
                    final Tuple3<java.lang.reflect.Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                if (pauseNextStore.getAndSet(false)) {
                    // The read has fetched its rows: the write runs and invalidates now, before the result is stored.
                    storing.countDown();

                    try {
                        written.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                return delegate.put(key, result, liveTime, maxIdleTime, daoProxy, args, methodSignature);
            }

            @Override
            public void update(final String key, final Object result, final Object daoProxy, final Object[] args,
                    final Tuple3<java.lang.reflect.Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                delegate.update(key, result, daoProxy, args, methodSignature);
            }
        };

        final CachedItemDao cachedDao = JdbcUtil.createDao(CachedItemDao.class, dataSource, JdbcUtil.DaoCreationOptions.builder().cache(pausing).build());
        final java.util.concurrent.CompletableFuture<Integer> reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return cachedDao.cachedCount();
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertTrue(storing.await(10, java.util.concurrent.TimeUnit.SECONDS), "the read has fetched its rows");
        cachedDao.addItem(2); // commits (auto-commit) and invalidates the table
        written.countDown();

        assertEquals(1, reader.get(10, java.util.concurrent.TimeUnit.SECONDS), "fetched before the write");
        assertEquals(2, cachedDao.cachedCount(), "the result read before the write was not kept");
    }

    // Round 16 review: the invalidation generations were keyed by the exact table name, while the built-in caches match the
    // table of their entries ignoring case: a write through a DAO naming the table in another letter case left the generation
    // of a read through the first DAO unchanged, and the read kept the rows it had fetched before the write cached.
    @Test
    void aWriteThroughADaoNamingTheTableInAnotherCase_keepsAReadOverlappingItFromStoringOldRows() throws Exception {
        final Jdbc.DaoCache delegate = Jdbc.DaoCache.createByMap();
        final java.util.concurrent.CountDownLatch storing = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch written = new java.util.concurrent.CountDownLatch(1);
        final Jdbc.DaoCache pausing = pausingCache(delegate, storing, written);

        final CachedItemDao reading = JdbcUtil.createDao(CachedItemDao.class, dataSource,
                JdbcUtil.DaoCreationOptions.builder().cache(pausing).targetTableName("cache_tx_item").build());
        final CachedItemDao writing = JdbcUtil.createDao(CachedItemDao.class, dataSource,
                JdbcUtil.DaoCreationOptions.builder().cache(pausing).targetTableName("CACHE_TX_ITEM").build());
        final java.util.concurrent.CompletableFuture<Integer> reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return reading.cachedCount();
            } catch (final SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertTrue(storing.await(10, java.util.concurrent.TimeUnit.SECONDS), "the read has fetched its rows");
        writing.addItem(2); // commits (auto-commit) and invalidates the table, named in upper case
        written.countDown();

        assertEquals(1, reader.get(10, java.util.concurrent.TimeUnit.SECONDS), "fetched before the write");
        assertEquals(2, reading.cachedCount(), "the result read before the write was not kept");
    }

    // Round 16 review (tests): a built-in cache removes no single entry, so a result stored by a read that an invalidation
    // overtook between its last check and its store is removed by invalidating the table.
    @Test
    void aResultStoredAfterAnInvalidationOvertookItsRead_isRemovedFromABuiltInCache() throws Exception {
        final Jdbc.DaoCache builtIn = Mockito.spy(Jdbc.DaoCache.create(100, 60_000));
        final java.util.concurrent.CountDownLatch storing = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch written = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean pauseNextStore = new java.util.concurrent.atomic.AtomicBoolean(true);

        Mockito.doAnswer(invocation -> {
            if (pauseNextStore.getAndSet(false)) {
                storing.countDown();
                written.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }

            return invocation.callRealMethod();
        }).when(builtIn).put(anyString(), any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());

        try {
            final CachedItemDao cachedDao = JdbcUtil.createDao(CachedItemDao.class, dataSource, JdbcUtil.DaoCreationOptions.builder().cache(builtIn).build());
            final java.util.concurrent.CompletableFuture<Integer> reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return cachedDao.cachedCount();
                } catch (final SQLException e) {
                    throw new IllegalStateException(e);
                }
            });

            assertTrue(storing.await(10, java.util.concurrent.TimeUnit.SECONDS), "the read has fetched its rows");
            cachedDao.addItem(2);
            written.countDown();

            assertEquals(1, reader.get(10, java.util.concurrent.TimeUnit.SECONDS), "fetched before the write");
            assertEquals(2, cachedDao.cachedCount(), "the stored result was removed again");
            // The invalidation removing the result stored after the write's (by table, without arguments; the write's own has
            // the write's arguments).
            verify(builtIn, times(1)).update(anyString(), isNull(), any(), isNull(), any());
        } finally {
            ((AutoCloseable) builtIn).close();
        }
    }

    // Round 16 review: a write in a transaction that joins it updates the cache once the transaction has committed only. One
    // whose own propagation commits it on its own (REQUIRES_NEW) still invalidates right away: its rows are committed, and the
    // update after the commit of the enclosing transaction, which here rolls back, never runs.
    @ParameterizedTest(name = "spring={0}")
    @ValueSource(booleans = { false, true })
    void aWriteCommittingOnItsOwnInsideATransaction_invalidatesTheCacheRightAway(final boolean spring) throws SQLException {
        assertEquals(1, dao.cachedCount()); // cached

        inTransaction(spring, false, () -> dao.addItemOnItsOwn(2));

        assertEquals(2, dao.cachedCount(), "committed on its own: the cache was invalidated right away");
    }

    // Round 16 review: a DAO interface may override dataSource() with a default method, whose data source its statements then
    // run on, while the result cache was kept out of transactions on the DAO's own data source: a result read in a transaction
    // on the overriding one was cached, and served after the transaction rolled back.
    @Test
    void aDaoOverridingItsDataSource_keepsItsCacheOutOfTransactionsOnThatDataSource() throws SQLException {
        final JdbcDataSource own = new JdbcDataSource();
        own.setURL("jdbc:h2:mem:dao_cache_own_" + UUID.randomUUID());
        OverridingDataSourceDao.TARGET.set(dataSource);

        try {
            final OverridingDataSourceDao overriding = JdbcUtil.createDao(OverridingDataSourceDao.class, own,
                    JdbcUtil.DaoCreationOptions.builder().cache(Jdbc.DaoCache.createByMap()).build());

            inTransaction(false, false, () -> {
                JdbcUtil.executeUpdate(dataSource, "INSERT INTO cache_tx_item (id, name) VALUES (2, 'b')");
                assertEquals(2, overriding.cachedCount(), "the transaction's own write");
            });

            assertEquals(1, overriding.cachedCount(), "the result read in the rolled-back transaction was not cached");
        } finally {
            OverridingDataSourceDao.TARGET.set(null);
        }
    }

    // Round 17 review: a write joining a Spring transaction (a built-in or @Query method) left the invalidation of the caches to
    // the commit, which Spring reports for the innermost transaction on the thread only: a write to the data source of an
    // enclosing transaction from inside another transaction manager's transaction lost it when that inner one rolled back,
    // while the enclosing one committed the row, and a cache by map served the old count for good. In a Spring transaction it
    // invalidates right away as well (as before round 16).
    @Test
    void aWriteJoiningAnEnclosingSpringTransactionFromAnotherManagersTransactionThatRollsBack_invalidatesTheCache() throws SQLException {
        final JdbcDataSource other = new JdbcDataSource();
        other.setURL("jdbc:h2:mem:dao_cache_other_" + UUID.randomUUID());
        assertEquals(1, dao.cachedCount()); // cached

        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(outer -> {
            new TransactionTemplate(new DataSourceTransactionManager(other)).executeWithoutResult(inner -> {
                try {
                    dao.addItem(2); // joins the enclosing transaction, which owns the connection of the DAO's data source
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }

                inner.setRollbackOnly();
            });
        });

        assertEquals(2, countRows(), "the enclosing transaction committed the row");
        assertEquals(2, dao.cachedCount(), "the cache was invalidated");
    }

    // External review: the invalidation of a write in a Spring-managed transaction followed Spring's synchronization of the
    // innermost transaction on the thread, so for a write joining an enclosing transaction from inside another transaction
    // manager's transaction it was lost (that inner one rolled back) or ran too early (it committed first): a read outside the
    // transaction between the write and the enclosing commit stored the rows as they were before, which the cache by map kept
    // for good. While the transaction that wrote the table runs, its connection holder keeps the shared caches out of reads of
    // the table, whatever the synchronization.
    @ParameterizedTest(name = "innerRollsBack={0}")
    @ValueSource(booleans = { false, true })
    void aReadBetweenAWriteFromAnotherManagersTransactionAndTheEnclosingCommit_storesNothing(final boolean innerRollsBack) throws SQLException {
        final JdbcDataSource other = new JdbcDataSource();
        other.setURL("jdbc:h2:mem:dao_cache_other_" + UUID.randomUUID());
        assertEquals(1, dao.cachedCount()); // cached

        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(outer -> {
            new TransactionTemplate(new DataSourceTransactionManager(other)).executeWithoutResult(inner -> {
                try {
                    dao.addItem(2); // joins the enclosing transaction, which owns the connection of the DAO's data source
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }

                if (innerRollsBack) {
                    inner.setRollbackOnly();
                }
            });

            // A read outside the transaction (as on another thread) sees the rows committed before it.
            try {
                assertEquals(1, (int) JdbcUtil.callOutsideTransaction(dataSource, () -> dao.cachedCount()));
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }
        });

        assertEquals(2, countRows(), "the enclosing transaction committed the row");
        assertEquals(2, dao.cachedCount(), "the read in between stored nothing");
    }

    // External review: with Spring's transaction synchronization disabled, a write in a Spring transaction ran both of its
    // invalidations right away, before the commit: a read outside the transaction in between stored the old rows for good.
    @Test
    void aReadBetweenAWriteAndTheCommitOfASpringTransactionWithoutSynchronization_storesNothing() throws SQLException {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactionManager.setTransactionSynchronization(org.springframework.transaction.support.AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        final TransactionTemplate unsynchronized = new TransactionTemplate(transactionManager);

        // The DAO's cache.
        assertEquals(1, dao.cachedCount()); // cached

        unsynchronized.executeWithoutResult(status -> {
            try {
                dao.addItem(2);
                assertEquals(1, (int) JdbcUtil.callOutsideTransaction(dataSource, () -> dao.cachedCount()));
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }
        });

        assertEquals(2, dao.cachedCount(), "the read in between stored nothing in the DAO's cache");

        // The cache of an openDaoCacheScope, which the scope's thread fills.
        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            assertEquals(2, dao.countItems()); // cached in the scope

            unsynchronized.executeWithoutResult(status -> {
                try {
                    dao.addItem(3);
                    assertEquals(2, (int) JdbcUtil.callOutsideTransaction(dataSource, () -> dao.countItems()));
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }
            });

            assertEquals(3, dao.countItems(), "nor in the scope's");
        }
    }

    // Round 17 review: the built-in default overloads (batchInsert(Collection) here, which delegates to the abstract
    // batchInsert(Collection, int)) were taken for the application's code, which may commit on its own, so a write through them
    // in a JdbcUtil transaction updated the cache right away as well, once per call.
    @Test
    void aWriteThroughABuiltInDefaultOverloadInAJdbcUtilTransaction_updatesTheCacheAfterTheCommitOnly() throws Exception {
        final Jdbc.DaoCache builtIn = Mockito.spy(Jdbc.DaoCache.create(100, 60_000));

        try {
            final BatchingItemDao batching = JdbcUtil.createDao(BatchingItemDao.class, dataSource,
                    JdbcUtil.DaoCreationOptions.builder().cache(builtIn).build());
            assertEquals(1, batching.cachedCount()); // cached

            inTransaction(false, true, () -> assertEquals(2, batching.batchInsert(List.of(item(2), item(3))).size()));

            verify(builtIn, times(0)).update(anyString(), any(), any(), notNull(), any()); // not right away
            verify(builtIn, times(1)).update(anyString(), isNull(), any(), isNull(), any()); // once after the commit
            assertEquals(3, batching.cachedCount());
        } finally {
            ((AutoCloseable) builtIn).close();
        }
    }

    // Round 17 review (tests): a write whose propagation leaves the transaction (NOT_SUPPORTED) commits on its own, so it
    // invalidates the cache right away, also when the transaction then rolls back.
    @ParameterizedTest(name = "spring={0}")
    @ValueSource(booleans = { false, true })
    void aWriteLeavingTheTransactionInsideIt_invalidatesTheCacheRightAway(final boolean spring) throws SQLException {
        assertEquals(1, dao.cachedCount()); // cached

        inTransaction(spring, false, () -> dao.addItemOutsideTransaction(2));

        assertEquals(2, countRows(), "committed on its own");
        assertEquals(2, dao.cachedCount(), "the cache was invalidated right away");
    }

    // Round 17 review (tests): with Spring's transaction synchronization disabled, the commit of a Spring transaction can't be
    // observed: a write in it invalidates the cache right away only.
    @Test
    void aWriteInASpringTransactionWithoutSynchronization_invalidatesTheCacheRightAway() throws SQLException {
        final DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactionManager.setTransactionSynchronization(org.springframework.transaction.support.AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        assertEquals(1, dao.cachedCount()); // cached

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                dao.addItem(2);
                assertEquals(2, dao.cachedCount(), "the transaction's own write, not the cached result");
            } catch (final SQLException e) {
                throw new IllegalArgumentException(e);
            }
        });

        assertEquals(2, dao.cachedCount());
    }

    // Round 17 review: a default override of dataSource() did not inherit @NonDBOperation, so it was processed as a database
    // operation: a type-level cache annotation whose filter matched it wrapped it, and the cache wrapper, which calls
    // dataSource() for its transaction checks, recursed until a StackOverflowError (as did every cached method calling it).
    @Test
    void aDefaultOverrideOfDataSource_isNoDatabaseOperation() throws SQLException {
        final JdbcDataSource own = new JdbcDataSource();
        own.setURL("jdbc:h2:mem:dao_cache_own_" + UUID.randomUUID());
        OverridingDataSourceDao.TARGET.set(dataSource);

        try {
            final FilteredOverridingDataSourceDao filtered = JdbcUtil.createDao(FilteredOverridingDataSourceDao.class, own,
                    JdbcUtil.DaoCreationOptions.builder().cache(Jdbc.DaoCache.createByMap()).build());

            assertSame(dataSource, filtered.dataSource());
            assertEquals(1, filtered.cachedCount());
            JdbcUtil.executeUpdate(dataSource, "INSERT INTO cache_tx_item (id, name) VALUES (2, 'b')");
            assertEquals(1, filtered.cachedCount(), "served from the cache");

            inTransaction(false, false, () -> assertEquals(2, filtered.cachedCount(), "in a transaction on the overriding data source"));
        } finally {
            OverridingDataSourceDao.TARGET.set(null);
        }
    }

    // Round 17 review: the invalidation generations hashed a table name by UTF-16 char, while equalsIgnoreCase compares a
    // supplementary character (a surrogate pair) as a code point: two names it takes for equal had different generations.
    @Test
    void tableNamesEqualIgnoringCase_shareAGeneration() throws Exception {
        final Class<?> generations = Class.forName(DaoImpl.class.getName() + "$CacheGenerations");
        final java.lang.reflect.Method slotOf = generations.getDeclaredMethod("slotOf", String.class);
        slotOf.setAccessible(true);

        // Deseret capital and small long I.
        final String upper = "t_\uD801\uDC00";
        final String lower = "t_\uD801\uDC28";
        assertTrue(upper.equalsIgnoreCase(lower));
        assertEquals(slotOf.invoke(null, upper), slotOf.invoke(null, lower));
        assertEquals(slotOf.invoke(null, "users"), slotOf.invoke(null, "USERS"));
    }

    // Round 17 review: the failure of a custom cache's update() for a write joining a JdbcUtil transaction, which updates the
    // cache once the transaction has committed only, is logged there, not thrown from the write; in a Spring transaction, the
    // update right away throws it from the write.
    @ParameterizedTest(name = "spring={0}")
    @ValueSource(booleans = { false, true })
    void aCustomCacheUpdateFailure_isThrownFromTheWriteOnlyWhenItUpdatesRightAway(final boolean spring) throws Exception {
        final Jdbc.DaoCache failing = Mockito.mock(Jdbc.DaoCache.class);
        Mockito.doThrow(new IllegalStateException("cache down")).when(failing).update(anyString(), any(), any(), any(), any());
        final CachedItemDao cachedDao = JdbcUtil.createDao(CachedItemDao.class, dataSource, JdbcUtil.DaoCreationOptions.builder().cache(failing).build());

        if (spring) {
            final IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> inTransaction(true, true, () -> cachedDao.addItem(2)));
            assertEquals("cache down", thrown.getMessage());
            assertEquals(1, countRows(), "the failure rolled the Spring transaction back");
        } else {
            inTransaction(false, true, () -> cachedDao.addItem(2));
            assertEquals(2, countRows(), "committed; the update after the commit failed and was logged");
        }

        verify(failing, Mockito.atLeastOnce()).update(anyString(), any(), any(), notNull(), any());
    }

    private int countRows() throws SQLException {
        try (Statement statement = keeper.createStatement();
             java.sql.ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM cache_tx_item")) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static CachedItem item(final long id) {
        final CachedItem item = new CachedItem();
        item.setId(id);
        item.setName("x" + id);
        return item;
    }

    private static Jdbc.DaoCache pausingCache(final Jdbc.DaoCache delegate, final java.util.concurrent.CountDownLatch storing,
            final java.util.concurrent.CountDownLatch written) {
        final java.util.concurrent.atomic.AtomicBoolean pauseNextStore = new java.util.concurrent.atomic.AtomicBoolean(true);

        return new Jdbc.DaoCache() {
            @Override
            public Object get(final String key, final Object daoProxy, final Object[] args,
                    final Tuple3<java.lang.reflect.Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return delegate.get(key, daoProxy, args, methodSignature);
            }

            @Override
            public boolean put(final String key, final Object result, final Object daoProxy, final Object[] args,
                    final Tuple3<java.lang.reflect.Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                return delegate.put(key, result, daoProxy, args, methodSignature);
            }

            @Override
            public boolean put(final String key, final Object result, final long liveTime, final long maxIdleTime, final Object daoProxy, final Object[] args,
                    final Tuple3<java.lang.reflect.Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                if (pauseNextStore.getAndSet(false)) {
                    storing.countDown();

                    try {
                        written.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                return delegate.put(key, result, liveTime, maxIdleTime, daoProxy, args, methodSignature);
            }

            @Override
            public void update(final String key, final Object result, final Object daoProxy, final Object[] args,
                    final Tuple3<java.lang.reflect.Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                delegate.update(key, result, daoProxy, args, methodSignature);
            }
        };
    }

    private void inTransaction(final boolean spring, final boolean commit, final Throwables.Runnable<SQLException> work) throws SQLException {
        if (spring) {
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
                try {
                    work.run();
                } catch (final SQLException e) {
                    throw new IllegalArgumentException(e);
                }

                if (!commit) {
                    status.setRollbackOnly();
                }
            });
        } else {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                work.run();

                if (commit) {
                    tran.commit();
                }
            } finally {
                tran.rollbackIfNotCommitted();
            }
        }
    }

    @Table("cache_tx_item")
    public static class CachedItem {
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

    public interface CachedItemDao extends NonUpdateCrudDao<CachedItem, Long, CachedItemDao> {
        @CacheResult(enabled = true)
        @Query("SELECT COUNT(*) FROM cache_tx_item")
        int cachedCount() throws SQLException;

        @RefreshCache
        @Query("INSERT INTO cache_tx_item (id, name) VALUES (:id, 'x')")
        void addItem(@Bind("id") long id) throws SQLException;

        // Not cached by the DAO's cache: by the cache of an openDaoCacheScope only.
        @Query("SELECT COUNT(*) FROM cache_tx_item")
        int countItems() throws SQLException;

        @RefreshCache
        @com.landawn.abacus.jdbc.annotation.Transactional(propagation = Propagation.REQUIRES_NEW)
        @Query("INSERT INTO cache_tx_item (id, name) VALUES (:id, 'x')")
        void addItemOnItsOwn(@Bind("id") long id) throws SQLException;

        @RefreshCache
        @com.landawn.abacus.jdbc.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
        @Query("INSERT INTO cache_tx_item (id, name) VALUES (:id, 'x')")
        void addItemOutsideTransaction(@Bind("id") long id) throws SQLException;
    }

    @RefreshCache(filter = "batchInsert")
    public interface BatchingItemDao extends NonUpdateCrudDao<CachedItem, Long, BatchingItemDao> {
        @CacheResult(enabled = true)
        @Query("SELECT COUNT(*) FROM cache_tx_item")
        int cachedCount() throws SQLException;
    }

    @CacheResult(enabled = true, filter = { "cached", "dataSource" })
    public interface FilteredOverridingDataSourceDao extends NonUpdateCrudDao<CachedItem, Long, FilteredOverridingDataSourceDao> {
        @Query("SELECT COUNT(*) FROM cache_tx_item")
        int cachedCount() throws SQLException;

        @Override
        default javax.sql.DataSource dataSource() {
            return OverridingDataSourceDao.TARGET.get();
        }
    }

    public interface OverridingDataSourceDao extends NonUpdateCrudDao<CachedItem, Long, OverridingDataSourceDao> {
        java.util.concurrent.atomic.AtomicReference<javax.sql.DataSource> TARGET = new java.util.concurrent.atomic.AtomicReference<>();

        @CacheResult(enabled = true)
        @Query("SELECT COUNT(*) FROM cache_tx_item")
        int cachedCount() throws SQLException;

        @Override
        default javax.sql.DataSource dataSource() {
            return TARGET.get();
        }
    }
}
