package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.annotation.CacheResult;

/**
 * Behavioral coverage for the capability-based DAO redesign: the {@link DaoUtil#isCacheable} cache-eligibility
 * gate (cache permitted on read-only / non-update DAOs but not on a full {@link Dao}) and the
 * centralized {@code prepareQuery}/{@code prepareNamedQuery} SQL-kind gate enforced by the
 * {@code DaoImpl} proxy for CRUD-level restricted DAOs. Plus structural assertions that lock down the
 * capability composition graph.
 */
public class CapabilityGatingTest extends TestBase {

    public static final class Account {
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

    interface FullDao extends Dao<Account, FullDao> {
    }

    interface RoCrudDao extends ReadOnlyCrudDao<Account, Long, RoCrudDao> {
    }

    interface NuCrudDao extends NonUpdateCrudDao<Account, Long, NuCrudDao> {
    }

    interface UncheckedNuCrudDao extends UncheckedNonUpdateCrudDao<Account, Long, UncheckedNuCrudDao> {
    }

    @CacheResult
    interface CachedReadOnlyDao extends ReadOnlyDao<Account, CachedReadOnlyDao> {
    }

    @CacheResult
    interface CachedNonUpdateDao extends NonUpdateDao<Account, CachedNonUpdateDao> {
    }

    @CacheResult
    interface CachedFullDao extends Dao<Account, CachedFullDao> {
    }

    private static DataSource newDataSource() throws SQLException {
        final DataSource ds = mock(DataSource.class);
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData meta = mock(DatabaseMetaData.class);
        final PreparedStatement stmt = mock(PreparedStatement.class);

        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("MySQL");
        when(meta.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(conn.prepareStatement(anyString(), anyInt())).thenReturn(stmt);
        when(conn.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(stmt);

        return ds;
    }

    // ----- cache-eligibility gate -----

    @Test
    public void testCacheEligibilityGate() {
        // The gate accepts exactly the read-restricted roots and their variants; every restricted DAO
        // is therefore cacheable, while a full Dao is not.
        assertTrue(DaoUtil.isCacheable(NonUpdateDao.class));
        assertTrue(DaoUtil.isCacheable(ReadOnlyDao.class));
        assertTrue(DaoUtil.isCacheable(NonUpdateCrudDao.class));
        assertTrue(DaoUtil.isCacheable(ReadOnlyCrudDao.class));
        assertTrue(DaoUtil.isCacheable(UncheckedReadOnlyDao.class));
        assertFalse(DaoUtil.isCacheable(Dao.class));
        assertFalse(DaoUtil.isCacheable(CrudDao.class));
    }

    @Test
    public void testCacheAllowedOnReadOnlyAndNonUpdateDao() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.createDao(CachedReadOnlyDao.class, newDataSource()));
        assertDoesNotThrow(() -> JdbcUtil.createDao(CachedNonUpdateDao.class, newDataSource()));
    }

    interface ReadOnlyAndFullDao extends ReadOnlyDao<Account, ReadOnlyAndFullDao>, Dao<Account, ReadOnlyAndFullDao> {
    }

    interface ReadOnlyAndNonUpdateDao extends ReadOnlyDao<Account, ReadOnlyAndNonUpdateDao>, NonUpdateDao<Account, ReadOnlyAndNonUpdateDao> {
    }

    interface ReadOnlyCrudAndCrudDao extends ReadOnlyCrudDao<Account, Long, ReadOnlyCrudAndCrudDao>, CrudDao<Account, Long, ReadOnlyCrudAndCrudDao> {
    }

    interface UncheckedReadOnlyAndUncheckedDao
            extends UncheckedReadOnlyDao<Account, UncheckedReadOnlyAndUncheckedDao>, UncheckedDao<Account, UncheckedReadOnlyAndUncheckedDao> {
    }

    interface NonUpdateAndFullDao extends NonUpdateDao<Account, NonUpdateAndFullDao>, Dao<Account, NonUpdateAndFullDao> {
    }

    interface UncheckedAndCheckedReadOnlyDao
            extends UncheckedReadOnlyDao<Account, UncheckedAndCheckedReadOnlyDao>, ReadOnlyDao<Account, UncheckedAndCheckedReadOnlyDao> {
    }

    // A restriction level combined with a write capability it forbids used to let the restriction win silently (raw SQL gated
    // to SELECT, no INSERT SQL while insert/save stayed on the type); the combination is rejected at DAO creation.
    @Test
    public void testRestrictionLevelCombinedWithWriteCapabilityRejected() throws SQLException {
        final DataSource ds = newDataSource();

        for (final Class<? extends DaoBase> daoClass : List.<Class<? extends DaoBase>> of(ReadOnlyAndFullDao.class, ReadOnlyAndNonUpdateDao.class,
                ReadOnlyCrudAndCrudDao.class, UncheckedReadOnlyAndUncheckedDao.class, NonUpdateAndFullDao.class)) {
            final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> JdbcUtil.createDao((Class) daoClass, ds));
            assertTrue(ex.getMessage().contains("extends both"), ex.getMessage());
        }

        // Guard: the same restriction level through checked and Unchecked twins is fine.
        assertDoesNotThrow(() -> JdbcUtil.createDao(UncheckedAndCheckedReadOnlyDao.class, ds));
    }

    @Test
    public void testCacheRejectedOnFullDao() throws SQLException {
        final DataSource ds = newDataSource();
        final UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, () -> JdbcUtil.createDao(CachedFullDao.class, ds));
        assertTrue(ex.getMessage().contains("Cacheable"), "message should explain caching is only for Cacheable DAOs: " + ex.getMessage());
    }

    // ----- Centralized prepareQuery SQL-kind gate at the CRUD level -----

    @Test
    public void testReadOnlyCrudDao_PrepareGate() throws SQLException {
        final RoCrudDao dao = JdbcUtil.createDao(RoCrudDao.class, newDataSource());

        assertNotNull(dao.prepareQuery("SELECT * FROM account"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("INSERT INTO account(id) VALUES (?)"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("UPDATE account SET name = 'x'"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("DELETE FROM account"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("UPDATE account SET name = :name"));
    }

    @Test
    public void testNonUpdateCrudDao_PrepareGate() throws SQLException {
        final NuCrudDao dao = JdbcUtil.createDao(NuCrudDao.class, newDataSource());

        assertNotNull(dao.prepareQuery("SELECT * FROM account"));
        assertNotNull(dao.prepareQuery("INSERT INTO account(id) VALUES (?)"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("UPDATE account SET name = 'x'"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("DELETE FROM account"));
    }

    // insert/batchInsert with a named SQL prepare it through JdbcUtil, not the proxy's gated prepareNamedQuery: a non-update
    // CRUD DAO (checked and Unchecked) executed an UPDATE or DELETE passed to them.
    @Test
    public void testNonUpdateCrudDao_InsertWithSqlGate() throws SQLException {
        final org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:capability_gating_insert_sql_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcUtil.executeUpdate(ds, "CREATE TABLE account (id BIGINT PRIMARY KEY, name VARCHAR(32))");
        JdbcUtil.executeUpdate(ds, "INSERT INTO account (id, name) VALUES (1, 'a')");

        final NuCrudDao dao = JdbcUtil.createDao(NuCrudDao.class, ds);
        final UncheckedNuCrudDao uncheckedDao = JdbcUtil.createDao(UncheckedNuCrudDao.class, ds);
        final Account account = new Account();
        account.setId(1);
        account.setName("changed");

        final String deleteSql = "DELETE FROM account WHERE id = :id";
        final String updateSql = "UPDATE account SET name = :name WHERE id = :id";

        assertThrows(UnsupportedOperationException.class, () -> dao.insert(deleteSql, account));
        assertThrows(UnsupportedOperationException.class, () -> dao.insert(updateSql, account));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchInsert(deleteSql, List.of(account), 1));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchInsert(updateSql, List.of(account, account), 1));
        // Validated like an argument, before the early return for no entities.
        assertThrows(UnsupportedOperationException.class, () -> dao.batchInsert(deleteSql, List.of(), 1));
        assertThrows(UnsupportedOperationException.class, () -> uncheckedDao.insert(deleteSql, account));
        assertThrows(UnsupportedOperationException.class, () -> uncheckedDao.batchInsert(updateSql, List.of(account), 1));

        assertEquals("a", dao.queryForString("name", 1L).orElseNull());

        // INSERT statements still run.
        final Account second = new Account();
        second.setId(2);
        second.setName("b");
        final Account third = new Account();
        third.setId(3);
        third.setName("c");

        assertEquals(2L, dao.insert("INSERT INTO account (id, name) VALUES (:id, :name)", second));
        assertEquals(List.of(3L), uncheckedDao.batchInsert("INSERT INTO account (id, name) VALUES (:id, :name)", List.of(third), 1));
        assertEquals(3, dao.count(com.landawn.abacus.query.Filters.alwaysTrue()));
    }

    @Test
    public void testFullDao_PrepareNotGated() throws SQLException {
        final FullDao dao = JdbcUtil.createDao(FullDao.class, newDataSource());

        // A full Dao places no SQL-kind restriction on prepareQuery.
        assertNotNull(dao.prepareQuery("SELECT * FROM account"));
        assertNotNull(dao.prepareQuery("UPDATE account SET name = 'x'"));
        assertNotNull(dao.prepareQuery("DELETE FROM account"));
    }

    // ----- Capability composition graph -----

    @Test
    public void testConditionLevelComposition() {
        // Dao = ReadOps + InsertOps + UpdateOps + DeleteOps; restricted variants drop capabilities.
        assertTrue(ReadOps.class.isAssignableFrom(Dao.class));
        assertTrue(InsertOps.class.isAssignableFrom(Dao.class));
        assertTrue(UpdateOps.class.isAssignableFrom(Dao.class));
        assertTrue(DeleteOps.class.isAssignableFrom(Dao.class));
        // All capability interfaces share the infrastructure root DaoBase (accessors + prepare* builders).
        assertTrue(DaoBase.class.isAssignableFrom(ReadOps.class));
        assertTrue(DaoBase.class.isAssignableFrom(InsertOps.class));
        assertTrue(DaoBase.class.isAssignableFrom(UpdateOps.class));
        assertTrue(DaoBase.class.isAssignableFrom(DeleteOps.class));
        // Read capability is decoupled from the write capabilities: write ops no longer extend ReadOps.
        assertFalse(ReadOps.class.isAssignableFrom(InsertOps.class));
        assertFalse(ReadOps.class.isAssignableFrom(UpdateOps.class));
        assertFalse(ReadOps.class.isAssignableFrom(DeleteOps.class));
    }

    @Test
    public void testCrudLevelComposition() {
        assertTrue(Dao.class.isAssignableFrom(CrudDao.class));
        assertTrue(CrudReadOps.class.isAssignableFrom(CrudDao.class));
        assertTrue(CrudInsertOps.class.isAssignableFrom(CrudDao.class));
        assertTrue(CrudUpdateOps.class.isAssignableFrom(CrudDao.class));
        assertTrue(CrudDeleteOps.class.isAssignableFrom(CrudDao.class));
        // NonUpdateCrudDao keeps read + insert (crud), not update/delete (crud).
        assertTrue(CrudReadOps.class.isAssignableFrom(NonUpdateCrudDao.class));
        assertTrue(CrudInsertOps.class.isAssignableFrom(NonUpdateCrudDao.class));
        assertFalse(CrudUpdateOps.class.isAssignableFrom(NonUpdateCrudDao.class));
        assertFalse(CrudDeleteOps.class.isAssignableFrom(NonUpdateCrudDao.class));
        // ReadOnlyCrudDao keeps read (crud) only.
        assertTrue(CrudReadOps.class.isAssignableFrom(ReadOnlyCrudDao.class));
        assertFalse(CrudInsertOps.class.isAssignableFrom(ReadOnlyCrudDao.class));
    }

    @Test
    public void testRestrictedVariantsOmitEveryMutatingCapability() {
        // The restricted DAOs gate mutation by type composition, so every mutating capability must be absent
        // from each restricted root, and each Unchecked twin must mirror its checked counterpart exactly.
        for (final Class<?> nonUpdate : new Class<?>[] { NonUpdateDao.class, NonUpdateCrudDao.class, UncheckedNonUpdateDao.class,
                UncheckedNonUpdateCrudDao.class }) {
            assertTrue(ReadOps.class.isAssignableFrom(nonUpdate), nonUpdate.getSimpleName());
            assertTrue(InsertOps.class.isAssignableFrom(nonUpdate), nonUpdate.getSimpleName());
            assertFalse(UpdateOps.class.isAssignableFrom(nonUpdate), nonUpdate.getSimpleName());
            assertFalse(DeleteOps.class.isAssignableFrom(nonUpdate), nonUpdate.getSimpleName());
            assertFalse(Dao.class.isAssignableFrom(nonUpdate), nonUpdate.getSimpleName());
        }

        for (final Class<?> readOnly : new Class<?>[] { ReadOnlyDao.class, ReadOnlyCrudDao.class, UncheckedReadOnlyDao.class,
                UncheckedReadOnlyCrudDao.class }) {
            assertTrue(ReadOps.class.isAssignableFrom(readOnly), readOnly.getSimpleName());
            assertFalse(InsertOps.class.isAssignableFrom(readOnly), readOnly.getSimpleName());
            assertFalse(UpdateOps.class.isAssignableFrom(readOnly), readOnly.getSimpleName());
            assertFalse(DeleteOps.class.isAssignableFrom(readOnly), readOnly.getSimpleName());
            assertFalse(NonUpdateDao.class.isAssignableFrom(readOnly), readOnly.getSimpleName());
        }

        // Unchecked twins carry the unchecked read/insert capabilities their checked counterparts declare checked.
        assertTrue(UncheckedReadOps.class.isAssignableFrom(UncheckedNonUpdateDao.class));
        assertTrue(UncheckedInsertOps.class.isAssignableFrom(UncheckedNonUpdateDao.class));
        assertTrue(UncheckedCrudReadOps.class.isAssignableFrom(UncheckedNonUpdateCrudDao.class));
        assertTrue(UncheckedCrudInsertOps.class.isAssignableFrom(UncheckedNonUpdateCrudDao.class));
        assertFalse(UncheckedCrudUpdateOps.class.isAssignableFrom(UncheckedNonUpdateCrudDao.class));
        assertFalse(UncheckedCrudDeleteOps.class.isAssignableFrom(UncheckedNonUpdateCrudDao.class));
        assertTrue(UncheckedReadOps.class.isAssignableFrom(UncheckedReadOnlyDao.class));
        assertTrue(UncheckedCrudReadOps.class.isAssignableFrom(UncheckedReadOnlyCrudDao.class));
        assertFalse(UncheckedCrudInsertOps.class.isAssignableFrom(UncheckedReadOnlyCrudDao.class));

        // Read-only join helpers keep the load side only; the delete side is absent from the type.
        for (final Class<?> helper : new Class<?>[] { ReadOnlyJoinEntityHelper.class, ReadOnlyCrudJoinEntityHelper.class,
                UncheckedReadOnlyJoinEntityHelper.class, UncheckedReadOnlyCrudJoinEntityHelper.class }) {
            assertTrue(JoinEntityReadOps.class.isAssignableFrom(helper), helper.getSimpleName());
            assertFalse(JoinEntityDeleteOps.class.isAssignableFrom(helper), helper.getSimpleName());
        }

        assertTrue(UncheckedJoinEntityReadOps.class.isAssignableFrom(UncheckedReadOnlyJoinEntityHelper.class));
        assertTrue(UncheckedCrudJoinEntityReadOps.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelper.class));
        assertTrue(UncheckedJoinEntityReadOps.class.isAssignableFrom(UncheckedJoinEntityHelper.class));
        assertTrue(UncheckedJoinEntityDeleteOps.class.isAssignableFrom(UncheckedJoinEntityHelper.class));
    }

    @Test
    public void testGeneratedIdHooksBelongToInsertCapability() throws NoSuchMethodException {
        assertNotNull(CrudInsertOps.class.getDeclaredMethod("idExtractor"));
        assertNotNull(CrudInsertOps.class.getDeclaredMethod("generateId"));
        assertThrows(NoSuchMethodException.class, () -> CrudReadOps.class.getDeclaredMethod("idExtractor"));
        assertThrows(NoSuchMethodException.class, () -> CrudReadOps.class.getDeclaredMethod("generateId"));
        assertThrows(NoSuchMethodException.class, () -> ReadOnlyCrudDao.class.getMethod("idExtractor"));
        assertThrows(NoSuchMethodException.class, () -> ReadOnlyCrudDao.class.getMethod("generateId"));

        assertNotNull(UncheckedCrudInsertOps.class.getDeclaredMethod("generateId"));
        assertThrows(NoSuchMethodException.class, () -> UncheckedCrudReadOps.class.getDeclaredMethod("generateId"));
    }
}
