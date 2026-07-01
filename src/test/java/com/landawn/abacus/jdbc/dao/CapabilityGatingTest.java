package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.annotation.CacheResult;

/**
 * Behavioral coverage for the capability-based DAO redesign: the {@link DaoUtil#isCacheable} cache-eligibility
 * gate (cache permitted on read-only / no-update DAOs but not on a full {@link Dao}) and the
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

    interface NuCrudDao extends NoUpdateCrudDao<Account, Long, NuCrudDao> {
    }

    @CacheResult
    interface CachedReadOnlyDao extends ReadOnlyDao<Account, CachedReadOnlyDao> {
    }

    @CacheResult
    interface CachedNoUpdateDao extends NoUpdateDao<Account, CachedNoUpdateDao> {
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
        assertTrue(DaoUtil.isCacheable(NoUpdateDao.class));
        assertTrue(DaoUtil.isCacheable(ReadOnlyDao.class));
        assertTrue(DaoUtil.isCacheable(NoUpdateCrudDao.class));
        assertTrue(DaoUtil.isCacheable(ReadOnlyCrudDao.class));
        assertTrue(DaoUtil.isCacheable(UncheckedReadOnlyDao.class));
        assertFalse(DaoUtil.isCacheable(Dao.class));
        assertFalse(DaoUtil.isCacheable(CrudDao.class));
    }

    @Test
    public void testCacheAllowedOnReadOnlyAndNoUpdateDao() throws SQLException {
        assertDoesNotThrow(() -> JdbcUtil.createDao(CachedReadOnlyDao.class, newDataSource()));
        assertDoesNotThrow(() -> JdbcUtil.createDao(CachedNoUpdateDao.class, newDataSource()));
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
    public void testNoUpdateCrudDao_PrepareGate() throws SQLException {
        final NuCrudDao dao = JdbcUtil.createDao(NuCrudDao.class, newDataSource());

        assertNotNull(dao.prepareQuery("SELECT * FROM account"));
        assertNotNull(dao.prepareQuery("INSERT INTO account(id) VALUES (?)"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("UPDATE account SET name = 'x'"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("DELETE FROM account"));
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
        // NoUpdateCrudDao keeps read + insert (crud), not update/delete (crud).
        assertTrue(CrudReadOps.class.isAssignableFrom(NoUpdateCrudDao.class));
        assertTrue(CrudInsertOps.class.isAssignableFrom(NoUpdateCrudDao.class));
        assertFalse(CrudUpdateOps.class.isAssignableFrom(NoUpdateCrudDao.class));
        assertFalse(CrudDeleteOps.class.isAssignableFrom(NoUpdateCrudDao.class));
        // ReadOnlyCrudDao keeps read (crud) only.
        assertTrue(CrudReadOps.class.isAssignableFrom(ReadOnlyCrudDao.class));
        assertFalse(CrudInsertOps.class.isAssignableFrom(ReadOnlyCrudDao.class));
    }
}
