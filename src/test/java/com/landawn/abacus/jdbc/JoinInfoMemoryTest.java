package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.jdbc.dao.JoinEntityHelperIntegrationTest;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedCrudJoinEntityHelper;
import com.landawn.abacus.query.Dsl;
import com.landawn.abacus.util.Tuple;

@Tag("2025")
class JoinInfoMemoryTest {
    @Test
    void entitiesWithoutJoinsDoNotRetainTableKeys() throws Exception {
        final Map<?, ?> pool = metadataPool();
        final int sizeBefore = pool.size();
        final Map<String, JoinInfo> first = JoinInfo.getEntityJoinInfo(getClass(), JoinInfoTest.OrderEntity.class, "empty_0");

        for (int i = 1; i <= 10_000; i++) {
            assertSame(first, JoinInfo.getEntityJoinInfo(getClass(), JoinInfoTest.OrderEntity.class, "empty_" + i));
            assertTrue(JoinInfo.getJoinEntityPropNamesByType(getClass(), JoinInfoTest.OrderEntity.class, "empty_" + i, getClass()).isEmpty());
        }

        assertEquals(sizeBefore, pool.size());
    }

    @Test
    void tableChurnIsBoundedAndEvictedMetadataRemainsUsable() throws Exception {
        final String table = "bounded_first";
        final Map<String, JoinInfo> first = JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table);
        final List<String> names = JoinInfo.getJoinEntityPropNamesByType(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table,
                JoinInfoTest.OrderEntity.class);
        final String sql = first.get("orders").selectSqlPlan(Dsl.PSC)._1.apply(null);

        for (int i = 0; i < 2_048; i++) {
            final String rotatingTable = "bounded_partition_" + i;
            JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, rotatingTable);
            assertEquals(List.of("orders"), JoinInfo.getJoinEntityPropNamesByType(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, rotatingTable,
                    JoinInfoTest.OrderEntity.class));
        }

        assertTrue(metadataPool().size() <= 1_024);
        assertEquals(List.of("orders"), names);
        assertThrows(UnsupportedOperationException.class, () -> names.add("other"));
        assertThrows(UnsupportedOperationException.class, () -> first.remove("orders"));
        assertEquals(sql, first.get("orders").selectSqlPlan(Dsl.PSC)._1.apply(null));
        final Map<String, JoinInfo> rebuilt = JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table);
        assertNotSame(first, rebuilt);
        assertEquals(table, rebuilt.get("orders").tableName);
        assertEquals(sql, rebuilt.get("orders").selectSqlPlan(Dsl.PSC)._1.apply(null));
        assertTrue(rebuilt.get("orders").allowNullOrDefaultJoinKeys);
    }

    @Test
    void recentlyUsedEntriesSurviveTableChurn() throws Exception {
        final Map<String, JoinInfo> hot = JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "hot_table");

        for (int i = 0; i < 4_096; i++) {
            JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "cold_partition_" + i);
            assertSame(hot, JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "hot_table"));
        }

        assertTrue(metadataPool().size() <= 1_024);
    }

    @Test
    void retainedDaoMetadataIsNotEvicted() throws Exception {
        final Map<String, JoinInfo> retained = JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "dao_owned");
        JoinInfo.retainDaoJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "dao_owned", retained);

        for (int i = 0; i < 2_048; i++) {
            JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "dao_churn_" + i);
        }

        assertSame(retained, JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "dao_owned"));
        assertEquals(List.of("orders"),
                JoinInfo.getJoinEntityPropNamesByType(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "dao_owned", JoinInfoTest.OrderEntity.class));
    }

    @Test
    void retainedDaoMetadataFreesItsBoundedCacheSlotAndQueuedKey() throws Exception {
        final Object key = Tuple.of(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "dao_slot");
        final Map<String, JoinInfo> retained = JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "dao_slot");
        assertTrue(metadataPool().containsKey(key));
        assertTrue(metadataQueue().contains(key));

        JoinInfo.retainDaoJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "dao_slot", retained);

        assertFalse(metadataPool().containsKey(key), "a registered DAO's metadata must not also occupy a bounded-cache slot");
        assertFalse(metadataQueue().contains(key), "nor leave its key in the eviction queue");
        assertSame(retained, JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "dao_slot"));
        assertFalse(metadataPool().containsKey(key), "lookups of retained metadata must not add it to the bounded cache again");

        // Registering many DAO combinations, as an application with a DAO per table name does, leaves no queued keys
        // behind, including while the cache stays below capacity, when no eviction scan would drop them.
        for (int i = 0; i < 100; i++) {
            final String table = "dao_slot_" + i;
            JoinInfo.retainDaoJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table,
                    JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table));
        }

        assertEquals(metadataPool().size(), metadataQueue().size(), "the eviction queue holds exactly the cached keys");
    }

    // Each pooled DAO proxy retains its combination once; the metadata is released when the last one leaves the DAO pool
    // (e.g. evicted DAOs created per tenant table), so retained metadata cannot accumulate either.
    @Test
    void retainedDaoMetadataIsReleasedByItsLastDao() throws Exception {
        final String table = "dao_released";
        final Object key = Tuple.of(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table);
        final Map<String, JoinInfo> first = JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table);
        final Map<String, JoinInfo> second = JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table);
        final Field field = JoinInfo.class.getDeclaredField("daoJoinMetadataPool");
        field.setAccessible(true);
        final Map<?, ?> daoMetadataPool = (Map<?, ?>) field.get(null);

        JoinInfo.retainDaoJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table, first);
        JoinInfo.retainDaoJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table, second);

        JoinInfo.releaseDaoJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table, second);
        assertTrue(daoMetadataPool.containsKey(key), "still retained by the other DAO");
        assertSame(first, JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table));

        JoinInfo.releaseDaoJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table, first);
        assertFalse(daoMetadataPool.containsKey(key), "released by its last DAO");

        // Later lookups use the bounded cache again; the released maps stay usable.
        final Map<String, JoinInfo> rebuilt = JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table);
        assertTrue(metadataPool().containsKey(key));
        assertEquals(table, rebuilt.get("orders").tableName);
        assertEquals(table, first.get("orders").tableName);

        // Releasing a combination that is not retained is a no-op.
        JoinInfo.releaseDaoJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, table, first);
        assertFalse(daoMetadataPool.containsKey(key));
    }

    static final String REENTRANT_TABLE = "reentrant_user";

    // Its many-to-many link class, ReentrantUserRoleLink, looks up this join metadata from its static initializer.
    public static final class ReentrantUser {
        private long userId;

        @JoinedBy("userId = ReentrantUserRoleLink.userId, ReentrantUserRoleLink.roleId = roleId")
        private List<RoleLookupEntity> roles;

        public long getUserId() {
            return userId;
        }

        public void setUserId(final long userId) {
            this.userId = userId;
        }

        public List<RoleLookupEntity> getRoles() {
            return roles;
        }

        public void setRoles(final List<RoleLookupEntity> roles) {
            this.roles = roles;
        }
    }

    @Test
    void lookupFromAnEntityInitializerDuringTheBuildSucceeds() throws Exception {
        final Map<String, JoinInfo> joinInfo = JoinInfo.getEntityJoinInfo(JoinInfoMemoryTest.class, ReentrantUser.class, REENTRANT_TABLE);

        // Built inside the map's computeIfAbsent callback, the nested lookup failed with IllegalStateException("Recursive update").
        assertSame(joinInfo, ReentrantUserRoleLink.NESTED_LOOKUP, String.valueOf(ReentrantUserRoleLink.NESTED_LOOKUP));
        assertSame(joinInfo, JoinInfo.getEntityJoinInfo(JoinInfoMemoryTest.class, ReentrantUser.class, REENTRANT_TABLE));
        assertEquals(metadataPool().size(), metadataQueue().size());
    }

    static final String RACE_TABLE = "promotion_race_user";
    static final CountDownLatch RACE_BUILDING = new CountDownLatch(1);
    static final CountDownLatch RACE_REGISTERED = new CountDownLatch(1);

    // Its many-to-many link class, RaceUserRoleLink, holds a lookup building this join metadata until the test has
    // registered the same key for a DAO.
    public static final class RaceUser {
        private long userId;

        @JoinedBy("userId = RaceUserRoleLink.userId, RaceUserRoleLink.roleId = roleId")
        private List<RoleLookupEntity> roles;

        public long getUserId() {
            return userId;
        }

        public void setUserId(final long userId) {
            this.userId = userId;
        }

        public List<RoleLookupEntity> getRoles() {
            return roles;
        }

        public void setRoles(final List<RoleLookupEntity> roles) {
            this.roles = roles;
        }
    }

    @Test
    void lookupThatMissedBeforeDaoRegistrationDoesNotRefillTheBoundedCache() throws Exception {
        final Object key = Tuple.of(JoinInfoMemoryTest.class, RaceUser.class, RACE_TABLE);
        // retainDaoJoinInfo stores whatever map the DAO captured. This one is built up front: building RaceUser's map
        // here would wait for the bean metadata the racing lookup is in the middle of building.
        final Map<String, JoinInfo> registered = JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "race_registered");
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            final Future<Map<String, JoinInfo>> lookup = executor
                    .submit(() -> JoinInfo.getEntityJoinInfo(JoinInfoMemoryTest.class, RaceUser.class, RACE_TABLE));
            assertTrue(RACE_BUILDING.await(10, TimeUnit.SECONDS));

            // The lookup has missed both caches and is building the metadata: register the key for a DAO meanwhile.
            JoinInfo.retainDaoJoinInfo(JoinInfoMemoryTest.class, RaceUser.class, RACE_TABLE, registered);
            RACE_REGISTERED.countDown();

            assertSame(registered, lookup.get(20, TimeUnit.SECONDS));
        } finally {
            RACE_REGISTERED.countDown();
            executor.shutdownNow();
        }

        assertFalse(metadataPool().containsKey(key), "a lookup finishing after the registration must not cache the key again");
        assertFalse(metadataQueue().contains(key));
        assertEquals(metadataPool().size(), metadataQueue().size());
    }

    @Test
    void daoCreationBuildsItsJoinPlansAndRetainsItsMetadata() throws Exception {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:join_retention_" + System.nanoTime());
        final JoinEntityHelperIntegrationTest.JoinUserDao dao = JdbcUtil.createDao(JoinEntityHelperIntegrationTest.JoinUserDao.class, ds);
        final Map<String, JoinInfo> daoJoinInfo = JoinInfo.getEntityJoinInfo(dao.targetDaoInterface(), dao.targetEntityClass(), dao.targetTableName());
        final Field plansField = JoinInfo.class.getDeclaredField("planDslPool");
        plansField.setAccessible(true);

        assertFalse(daoJoinInfo.isEmpty());
        assertFalse(metadataPool().containsKey(Tuple.of(dao.targetDaoInterface(), dao.targetEntityClass(), dao.targetTableName())));

        for (final JoinInfo info : daoJoinInfo.values()) {
            assertFalse(((Map<?, ?>) plansField.get(info)).isEmpty(), "DAO creation must build the plans of its dialect");
        }

        for (int i = 0; i < 2_048; i++) {
            JoinInfo.getEntityJoinInfo(JoinInfoTest.UserDao.class, JoinInfoTest.UserEntity.class, "dao_creation_churn_" + i);
        }

        assertSame(daoJoinInfo, JoinInfo.getEntityJoinInfo(dao.targetDaoInterface(), dao.targetEntityClass(), dao.targetTableName()));
    }

    // Used only by daoCreationFailsWhenItsJoinPlansCannotBeBuilt, so no other test shares its join metadata.
    public interface PlanFailureUserDao extends UncheckedCrudDao<JoinEntityHelperIntegrationTest.JoinUser, Long, PlanFailureUserDao>,
            UncheckedCrudJoinEntityHelper<JoinEntityHelperIntegrationTest.JoinUser, Long, PlanFailureUserDao> {
    }

    @Test
    @SuppressWarnings("unchecked")
    void daoCreationFailsWhenItsJoinPlansCannotBeBuilt() throws Exception {
        final Class<JoinEntityHelperIntegrationTest.JoinUser> entityClass = JoinEntityHelperIntegrationTest.JoinUser.class;
        final String tableName = "join_user";
        // Pin the metadata createDao looks up, so the bounded cache cannot replace the instrumented JoinInfo objects.
        final Map<String, JoinInfo> joinInfo = JoinInfo.getEntityJoinInfo(PlanFailureUserDao.class, entityClass, tableName);
        JoinInfo.retainDaoJoinInfo(PlanFailureUserDao.class, entityClass, tableName, joinInfo);
        assertTrue(joinInfo.size() > 1);

        // Fail only the last join, after the earlier joins of the same DAO have been planned successfully.
        final JoinInfo failing = new ArrayList<>(joinInfo.values()).get(joinInfo.size() - 1);
        final Field builderField = JoinInfo.class.getDeclaredField("planBuilder");
        builderField.setAccessible(true);
        final com.landawn.abacus.util.function.Consumer<Dsl> builder = (com.landawn.abacus.util.function.Consumer<Dsl>) builderField.get(failing);
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:join_plan_failure_" + System.nanoTime());

        builderField.set(failing, (com.landawn.abacus.util.function.Consumer<Dsl>) dsl -> {
            throw new IllegalStateException("injected join plan failure");
        });

        try {
            final IllegalStateException e = assertThrows(IllegalStateException.class, () -> JdbcUtil.createDao(PlanFailureUserDao.class, ds));
            assertEquals("injected join plan failure", e.getMessage());
            assertFalse(registeredDaos().stream().anyMatch(PlanFailureUserDao.class::isInstance), "a DAO whose join plans failed must not be registered");
        } finally {
            builderField.set(failing, builder);
        }

        final Field plansField = JoinInfo.class.getDeclaredField("planDslPool");
        plansField.setAccessible(true);
        assertTrue(((Map<?, ?>) plansField.get(failing)).isEmpty(), "the failed build must not publish plans");

        // Once the plans can be built, creating the same DAO succeeds and publishes it.
        final PlanFailureUserDao dao = JdbcUtil.createDao(PlanFailureUserDao.class, ds);
        assertEquals(tableName, dao.targetTableName());
        assertSame(joinInfo, JoinInfo.getEntityJoinInfo(PlanFailureUserDao.class, entityClass, tableName));
        assertTrue(registeredDaos().stream().anyMatch(it -> it == dao));

        for (final JoinInfo info : joinInfo.values()) {
            assertFalse(((Map<?, ?>) plansField.get(info)).isEmpty());
        }
    }

    // Used only by invalidDaoIsRejectedBeforeItsJoinPlansAreBuilt: the unannotated abstract method fails DAO validation.
    public interface UnannotatedMethodUserDao extends UncheckedCrudDao<JoinEntityHelperIntegrationTest.JoinUser, Long, UnannotatedMethodUserDao>,
            UncheckedCrudJoinEntityHelper<JoinEntityHelperIntegrationTest.JoinUser, Long, UnannotatedMethodUserDao> {
        List<JoinEntityHelperIntegrationTest.JoinUser> listWithoutQuery();
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidDaoIsRejectedBeforeItsJoinPlansAreBuilt() throws Exception {
        final Class<JoinEntityHelperIntegrationTest.JoinUser> entityClass = JoinEntityHelperIntegrationTest.JoinUser.class;
        final String tableName = "join_user";
        final Map<String, JoinInfo> joinInfo = JoinInfo.getEntityJoinInfo(UnannotatedMethodUserDao.class, entityClass, tableName);
        JoinInfo.retainDaoJoinInfo(UnannotatedMethodUserDao.class, entityClass, tableName, joinInfo);
        final Field builderField = JoinInfo.class.getDeclaredField("planBuilder");
        builderField.setAccessible(true);
        final Map<JoinInfo, Object> builders = new java.util.IdentityHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:join_plan_order_" + System.nanoTime());

        for (final JoinInfo info : joinInfo.values()) {
            builders.put(info, builderField.get(info));
            builderField.set(info, (com.landawn.abacus.util.function.Consumer<Dsl>) dsl -> {
                builds.incrementAndGet();
                throw new IllegalStateException("join plans must not be built for a DAO that fails validation");
            });
        }

        try {
            // The validation failure, not a plan failure, is reported, and no plan is built for the rejected DAO.
            final UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                    () -> JdbcUtil.createDao(UnannotatedMethodUserDao.class, ds));
            assertTrue(e.getMessage().contains("listWithoutQuery"), e.getMessage());
            assertEquals(0, builds.get());
            assertFalse(registeredDaos().stream().anyMatch(UnannotatedMethodUserDao.class::isInstance));
        } finally {
            for (final Map.Entry<JoinInfo, Object> entry : builders.entrySet()) {
                builderField.set(entry.getKey(), entry.getValue());
            }
        }
    }

    private static java.util.Collection<?> registeredDaos() throws Exception {
        final Field field = DaoImpl.class.getDeclaredField("daoPool");
        field.setAccessible(true);
        final List<Object> daos = new java.util.ArrayList<>();

        // The pool holds an entry per registered proxy; its 'dao' field is the proxy.
        for (final Object pooledDao : ((Map<?, ?>) field.get(null)).values()) {
            final Field daoField = pooledDao.getClass().getDeclaredField("dao");
            daoField.setAccessible(true);
            daos.add(daoField.get(pooledDao));
        }

        return daos;
    }

    @Test
    void predefinedPlansAreLazyAndPublishedCompletelyUnderConcurrentUse() throws Exception {
        final JoinInfo info = new JoinInfo(JoinInfoTest.UserEntity.class, "lazy_plans", "orders", true);
        final Field plansField = JoinInfo.class.getDeclaredField("planDslPool");
        plansField.setAccessible(true);
        final Map<?, ?> plans = (Map<?, ?>) plansField.get(info);
        assertTrue(plans.isEmpty());
        final CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(8)) {
            final List<Future<Object[]>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return new Object[] { info.selectSqlPlan(Dsl.PSC), info.batchSelectSqlPlan(Dsl.PSC), info.deleteSqlPlan(Dsl.PSC),
                            info.batchDeleteSqlPlan(Dsl.PSC) };
                }));
            }
            start.countDown();
            final Object[] first = futures.get(0).get(10, TimeUnit.SECONDS);
            for (final Future<Object[]> future : futures) {
                final Object[] result = future.get(10, TimeUnit.SECONDS);
                for (int i = 0; i < first.length; i++) {
                    assertSame(first[i], result[i]);
                }
            }
        }

        assertEquals(1, plans.size());
        info.selectSqlPlan(Dsl.PAC);
        assertEquals(2, plans.size());
    }

    private static Map<?, ?> metadataPool() throws Exception {
        final Field field = JoinInfo.class.getDeclaredField("joinMetadataPool");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    private static Collection<?> metadataQueue() throws Exception {
        final Field field = JoinInfo.class.getDeclaredField("joinMetadataKeys");
        field.setAccessible(true);
        return (Collection<?>) field.get(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedPlanConstructionDoesNotRetainUnpublishedPlans() throws Exception {
        final JoinInfo info = new JoinInfo(JoinInfoTest.UserEntity.class, "failed_plans", "orders", true);
        final Object published = info.selectSqlPlan(Dsl.PSC);
        final Field builderField = JoinInfo.class.getDeclaredField("planBuilder");
        builderField.setAccessible(true);
        final com.landawn.abacus.util.function.Consumer<Dsl> builder = (com.landawn.abacus.util.function.Consumer<Dsl>) builderField.get(info);
        builderField.set(info, (com.landawn.abacus.util.function.Consumer<Dsl>) dsl -> {
            builder.accept(dsl);
            throw new IllegalStateException("injected plan construction failure");
        });

        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalStateException.class, () -> info.selectSqlPlan(Dsl.forDialect(Dsl.PAC.sqlDialect())));
        }

        for (final String name : List.of("selectSqlBuilderAndParamSetterPool", "batchSelectSqlBuilderAndParamSetterPool", "deleteSqlAndParamSetterPool",
                "batchDeleteSqlBuilderAndParamSetterPool", "planDslPool")) {
            final Field field = JoinInfo.class.getDeclaredField(name);
            field.setAccessible(true);
            assertEquals(1, ((Map<?, ?>) field.get(info)).size(), name);
        }
        assertSame(published, info.selectSqlPlan(Dsl.PSC));
    }
}

// The many-to-many link classes are resolved by name in the source entity's package, so they are top-level classes.
// Building join metadata loads a link class without initializing it, but then parses its bean metadata, which
// instantiates the class (abacus-common probes whether it is immutable): its static initializer runs during the build.

// Looks up the join metadata being built, as a link entity creating its own DAO in a static field looks up that DAO's.
final class ReentrantUserRoleLink {
    static final Object NESTED_LOOKUP;

    static {
        Object result;

        try {
            result = JoinInfo.getEntityJoinInfo(JoinInfoMemoryTest.class, JoinInfoMemoryTest.ReentrantUser.class, JoinInfoMemoryTest.REENTRANT_TABLE);
        } catch (final Throwable e) {
            result = e;
        }

        NESTED_LOOKUP = result;
    }

    private long userId;
    private long roleId;

    public long getUserId() {
        return userId;
    }

    public void setUserId(final long userId) {
        this.userId = userId;
    }

    public long getRoleId() {
        return roleId;
    }

    public void setRoleId(final long roleId) {
        this.roleId = roleId;
    }
}

// Signals that a lookup is building the join metadata, then holds it until the test has registered the key for a DAO.
final class RaceUserRoleLink {
    static {
        JoinInfoMemoryTest.RACE_BUILDING.countDown();

        try {
            JoinInfoMemoryTest.RACE_REGISTERED.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long userId;
    private long roleId;

    public long getUserId() {
        return userId;
    }

    public void setUserId(final long userId) {
        this.userId = userId;
    }

    public long getRoleId() {
        return roleId;
    }

    public void setRoleId(final long roleId) {
        this.roleId = roleId;
    }
}
