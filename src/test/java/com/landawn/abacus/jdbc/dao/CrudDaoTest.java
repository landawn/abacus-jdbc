package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.u.Optional;

public class CrudDaoTest extends TestBase {

    interface TestCrudDao extends CrudDao<TestEntity, Long, PSC, TestCrudDao> {
    }

    static final class TestEntity {
    }

    interface IdentifiedCrudDao extends CrudDao<IdentifiedEntity, Long, PSC, IdentifiedCrudDao> {
    }

    interface IdAnnotatedCrudDao extends CrudDao<IdAnnotatedEntity, Long, PSC, IdAnnotatedCrudDao> {
    }

    static final class IdAnnotatedEntity {
        @Id
        private Long id;
        private String name;

        public Long getId() { return id; }
        public void setId(final Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(final String name) { this.name = name; }
    }

    static final class IdentifiedEntity {
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    @Test
    public void testIdExtractor() {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertNull(dao.idExtractor());
    }

    @Test
    public void testGenerateId_UnsupportedOperation() {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class, dao::generateId);
    }

    @Test
    public void testBatchInsert_UsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<Long> ids = List.of(1L);

        when(dao.batchInsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(ids);

        assertEquals(ids, dao.batchInsert(entities));
        verify(dao).batchInsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testGet_WrapsEntityInOptional() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(9L)).thenReturn(entity);

        Optional<TestEntity> result = dao.get(9L);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).gett(9L);
    }

    @Test
    public void testBatchGet_UsesDefaultSelectionAndBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L, 2L);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchGet(ids, (java.util.Collection<String>) null)).thenReturn(entities);
        when(dao.batchGet(ids, (java.util.Collection<String>) null, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(entities);

        assertEquals(entities, dao.batchGet(ids));
        assertEquals(entities, dao.batchGet(ids, (java.util.Collection<String>) null));
    }

    @Test
    public void testBatchInsert_NamedInsertUsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<Long> ids = List.of(3L);

        when(dao.batchInsert("insertUser", entities, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(ids);

        assertEquals(ids, dao.batchInsert("insertUser", entities));
        verify(dao).batchInsert("insertUser", entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testGet_WithSelectPropNamesWrapsEntityInOptional() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(10L, List.of("name"))).thenReturn(entity);

        Optional<TestEntity> result = dao.get(10L, List.of("name"));

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
    }

    @Test
    public void testUpsert_UpdatePath_IgnoresId() throws SQLException {
        IdentifiedCrudDao dao = Mockito.mock(IdentifiedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdentifiedEntity entity = new IdentifiedEntity();
        entity.setId(100L);
        entity.setName("updated");
        IdentifiedEntity dbEntity = new IdentifiedEntity();
        dbEntity.setId(1L);
        dbEntity.setName("original");

        when(dao.findOnlyOne(Mockito.any())).thenReturn(Optional.of(dbEntity));
        when(dao.update(dbEntity)).thenReturn(1);

        IdentifiedEntity result = dao.upsert(entity, Mockito.mock(com.landawn.abacus.query.condition.Condition.class));

        assertSame(dbEntity, result);
        assertEquals(1L, dbEntity.getId());
        assertEquals("updated", dbEntity.getName());
    }

    @Test
    public void testBatchUpsert_EmptyEntities() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertEquals(0, dao.batchUpsert(List.of(), 2).size());
    }

    @Test
    public void testUpsert_InsertPath_WhenEntityNotFound() throws SQLException {
        IdentifiedCrudDao dao = Mockito.mock(IdentifiedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdentifiedEntity entity = new IdentifiedEntity();
        entity.setId(5L);
        entity.setName("new");

        when(dao.findOnlyOne(Mockito.any())).thenReturn(Optional.empty());

        IdentifiedEntity result = dao.upsert(entity, Mockito.mock(com.landawn.abacus.query.condition.Condition.class));

        assertSame(entity, result);
        verify(dao).insert(entity);
    }

    @Test
    public void testBatchInsert_WithPropNames_UsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<Long> ids = List.of(1L);
        List<String> props = List.of("name");

        when(dao.batchInsert(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(ids);

        assertEquals(ids, dao.batchInsert(entities, props));
        verify(dao).batchInsert(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchGet_WithBatchSize_UsesNullSelectProps() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L, 2L);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchGet(ids, (java.util.Collection<String>) null, 500)).thenReturn(entities);

        assertEquals(entities, dao.batchGet(ids, 500));
        verify(dao).batchGet(ids, (java.util.Collection<String>) null, 500);
    }

    @Test
    public void testBatchGet_WithSelectPropsOnly_UsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L);
        List<TestEntity> entities = List.of(new TestEntity());
        List<String> props = List.of("id", "name");

        when(dao.batchGet(ids, props, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(entities);

        assertEquals(entities, dao.batchGet(ids, props));
        verify(dao).batchGet(ids, props, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchUpdate_UsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(1);

        assertEquals(1, dao.batchUpdate(entities));
        verify(dao).batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchUpdate_WithPropNames_UsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<String> props = List.of("name");

        when(dao.batchUpdate(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(1);

        assertEquals(1, dao.batchUpdate(entities, props));
        verify(dao).batchUpdate(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchUpsert_UsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<TestEntity> result = List.of(new TestEntity());

        Mockito.doReturn(result).when(dao).batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertEquals(result, dao.batchUpsert(entities));
        verify(dao).batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchRefresh_UsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        Mockito.doReturn(1).when(dao).batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertEquals(1, dao.batchRefresh(entities));
        verify(dao).batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchRefresh_WithPropNames_UsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<String> props = List.of("name");

        Mockito.doReturn(1).when(dao).batchRefresh(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertEquals(1, dao.batchRefresh(entities, props));
        verify(dao).batchRefresh(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchDelete_UsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(1);

        assertEquals(1, dao.batchDelete(entities));
        verify(dao).batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchDeleteByIds_UsesDefaultBatchSize() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L, 2L);

        when(dao.batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(2);

        assertEquals(2, dao.batchDeleteByIds(ids));
        verify(dao).batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testNotExists_DelegatesToExists() throws SQLException {
        TestCrudDao dao = Mockito.mock(TestCrudDao.class, Mockito.CALLS_REAL_METHODS);

        when(dao.exists(5L)).thenReturn(false);
        assertTrue(dao.notExists(5L));

        when(dao.exists(5L)).thenReturn(true);
        assertFalse(dao.notExists(5L));
    }

    @Test
    public void testBatchUpsert_WithoutBatchSize_UsesDefault() throws SQLException {
        IdAnnotatedCrudDao dao = Mockito.mock(IdAnnotatedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<IdAnnotatedEntity> entities = List.of(new IdAnnotatedEntity());

        Mockito.doReturn(entities).when(dao).batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertEquals(entities, dao.batchUpsert(entities));
        verify(dao).batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    // upsert(entity) — single-arg variant resolves idPropNames from class then delegates
    // (lines 1100-1108).
    @Test
    public void testUpsert_SingleArg_DelegatesToUpsertWithIdPropNames() throws SQLException {
        IdAnnotatedCrudDao dao = Mockito.mock(IdAnnotatedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(7L);
        entity.setName("X");

        Mockito.doReturn(entity).when(dao).upsert(Mockito.same(entity), Mockito.anyList());

        assertSame(entity, dao.upsert(entity));
        verify(dao).upsert(Mockito.same(entity), Mockito.anyList());
    }

    // upsert(entity, cond) — entity class with no @Id triggers the empty-idPropNameList branch
    // and copies all fields (lines 1142-1143).
    static final class NoIdEntity {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    interface NoIdCrudDao extends CrudDao<NoIdEntity, Long, PSC, NoIdCrudDao> {
    }

    @Test
    public void testUpsert_UpdatePath_NoIdField_CopiesAllFields() throws SQLException {
        NoIdCrudDao dao = Mockito.mock(NoIdCrudDao.class, Mockito.CALLS_REAL_METHODS);
        NoIdEntity entity = new NoIdEntity();
        entity.setName("updated");
        NoIdEntity dbEntity = new NoIdEntity();
        dbEntity.setName("original");

        when(dao.findOnlyOne(Mockito.any())).thenReturn(Optional.of(dbEntity));
        when(dao.update(dbEntity)).thenReturn(1);

        NoIdEntity result = dao.upsert(entity, Mockito.mock(com.landawn.abacus.query.condition.Condition.class));

        assertSame(dbEntity, result);
        assertEquals("updated", dbEntity.getName());
    }

    // batchUpsert(entities, batchSize) — non-empty path resolves id prop names and delegates
    // (lines 1196-1201).
    @Test
    public void testBatchUpsert_NonEmptyEntities_DelegatesToFullSignature() throws SQLException {
        IdAnnotatedCrudDao dao = Mockito.mock(IdAnnotatedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<IdAnnotatedEntity> entities = List.of(new IdAnnotatedEntity());
        List<IdAnnotatedEntity> result = List.of(new IdAnnotatedEntity());

        Mockito.doReturn(result).when(dao).batchUpsert(Mockito.same(entities), Mockito.anyList(), Mockito.eq(2));

        assertEquals(result, dao.batchUpsert(entities, 2));
        verify(dao).batchUpsert(Mockito.same(entities), Mockito.anyList(), Mockito.eq(2));
    }

    // batchUpsert(entities, uniquePropNamesForQuery) — delegates with default batch size
    // (line 1221).
    @Test
    public void testBatchUpsert_WithUniquePropNames_UsesDefaultBatchSize() throws SQLException {
        IdAnnotatedCrudDao dao = Mockito.mock(IdAnnotatedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<IdAnnotatedEntity> entities = List.of(new IdAnnotatedEntity());
        List<String> uniqueProps = List.of("name");
        List<IdAnnotatedEntity> result = List.of(new IdAnnotatedEntity());

        Mockito.doReturn(result).when(dao).batchUpsert(entities, uniqueProps, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertEquals(result, dao.batchUpsert(entities, uniqueProps));
        verify(dao).batchUpsert(entities, uniqueProps, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    // batchUpsert(entities, uniquePropNamesForQuery, batchSize) — empty entities short-circuits
    // (line 1247).
    @Test
    public void testBatchUpsert_FullSig_EmptyEntities_ReturnsEmpty() throws SQLException {
        IdAnnotatedCrudDao dao = Mockito.mock(IdAnnotatedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertEquals(0, dao.batchUpsert(List.of(), List.of("name"), 5).size());
    }

    // batchUpsert(entities, uniquePropNamesForQuery, batchSize) — unknown property name throws
    // (lines 1258-1259).
    @Test
    public void testBatchUpsert_FullSig_UnknownUniqueProp_Throws() {
        IdAnnotatedCrudDao dao = Mockito.mock(IdAnnotatedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<IdAnnotatedEntity> entities = List.of(new IdAnnotatedEntity());

        assertThrows(IllegalArgumentException.class, () -> dao.batchUpsert(entities, List.of("nonExistent"), 5));
    }

    // batchUpsert validates batchSize must be positive.
    @Test
    public void testBatchUpsert_FullSig_NonPositiveBatchSize_Throws() {
        IdAnnotatedCrudDao dao = Mockito.mock(IdAnnotatedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(IllegalArgumentException.class, () -> dao.batchUpsert(List.of(new IdAnnotatedEntity()), List.of("name"), 0));
    }

    // batchUpsert validates uniquePropNamesForQuery must not be empty.
    @Test
    public void testBatchUpsert_FullSig_EmptyUniqueProps_Throws() {
        IdAnnotatedCrudDao dao = Mockito.mock(IdAnnotatedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(IllegalArgumentException.class, () -> dao.batchUpsert(List.of(new IdAnnotatedEntity()), List.of(), 5));
    }
}
