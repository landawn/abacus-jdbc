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
 * Behavioral coverage for the capability-based DAO redesign: the {@link Cacheable} cache-eligibility
 * marker (cache permitted on read-only / no-update DAOs but not on a full {@link Dao}) and the
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

    // ----- Cacheable marker / cache-eligibility gate -----

    @Test
    public void testCacheableMarkerStructure() {
        // The marker permits exactly the read-restricted condition-level roots; every restricted DAO
        // is therefore Cacheable, while a full Dao is not.
        assertTrue(Cacheable.class.isAssignableFrom(NoUpdateDao.class));
        assertTrue(Cacheable.class.isAssignableFrom(ReadOnlyDao.class));
        assertTrue(Cacheable.class.isAssignableFrom(NoUpdateCrudDao.class));
        assertTrue(Cacheable.class.isAssignableFrom(ReadOnlyCrudDao.class));
        assertTrue(Cacheable.class.isAssignableFrom(UncheckedReadOnlyDao.class));
        assertFalse(Cacheable.class.isAssignableFrom(Dao.class));
        assertFalse(Cacheable.class.isAssignableFrom(CrudDao.class));
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
        // Dao = Readable + Insertable + Updatable + Deletable; restricted variants drop capabilities.
        assertTrue(InsertableDao.class.isAssignableFrom(Dao.class));
        assertTrue(UpdatableDao.class.isAssignableFrom(Dao.class));
        assertTrue(DeletableDao.class.isAssignableFrom(Dao.class));
        assertTrue(ReadableDao.class.isAssignableFrom(InsertableDao.class));
        assertTrue(ReadableDao.class.isAssignableFrom(UpdatableDao.class));
        assertTrue(ReadableDao.class.isAssignableFrom(DeletableDao.class));
    }

    @Test
    public void testCrudLevelComposition() {
        assertTrue(Dao.class.isAssignableFrom(CrudDao.class));
        assertTrue(ReadableCrudDao.class.isAssignableFrom(CrudDao.class));
        assertTrue(InsertableCrudDao.class.isAssignableFrom(CrudDao.class));
        assertTrue(UpdatableCrudDao.class.isAssignableFrom(CrudDao.class));
        assertTrue(DeletableCrudDao.class.isAssignableFrom(CrudDao.class));
        // NoUpdateCrudDao keeps read + insert (crud), not update/delete (crud).
        assertTrue(ReadableCrudDao.class.isAssignableFrom(NoUpdateCrudDao.class));
        assertTrue(InsertableCrudDao.class.isAssignableFrom(NoUpdateCrudDao.class));
        assertFalse(UpdatableCrudDao.class.isAssignableFrom(NoUpdateCrudDao.class));
        assertFalse(DeletableCrudDao.class.isAssignableFrom(NoUpdateCrudDao.class));
        // ReadOnlyCrudDao keeps read (crud) only.
        assertTrue(ReadableCrudDao.class.isAssignableFrom(ReadOnlyCrudDao.class));
        assertFalse(InsertableCrudDao.class.isAssignableFrom(ReadOnlyCrudDao.class));
    }
}
