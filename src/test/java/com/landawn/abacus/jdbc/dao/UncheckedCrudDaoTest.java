package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.jdbc.JdbcUtil;
import static com.landawn.abacus.query.Dsl.PSC;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.u.Optional;

public class UncheckedCrudDaoTest extends TestBase {

    interface TestUncheckedCrudDao extends UncheckedCrudDao<TestEntity, Long, TestUncheckedCrudDao> {
    }

    static final class TestEntity {
    }

    interface IdentifiedUncheckedCrudDao extends UncheckedCrudDao<IdentifiedEntity, Long, IdentifiedUncheckedCrudDao> {
    }

    interface IdAnnotatedUncheckedCrudDao extends UncheckedCrudDao<IdAnnotatedEntity, Long, IdAnnotatedUncheckedCrudDao> {
    }

    static final class IdAnnotatedEntity {
        @Id
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
    public void testGenerateId_UnsupportedOperation() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, dao::generateId);
        assertEquals("ID generation is not supported by default", e.getMessage());
    }

    @Test
    public void testBatchInsert_UsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<Long> ids = List.of(1L);

        when(dao.batchInsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(ids);

        assertEquals(ids, dao.batchInsert(entities));
        verify(dao).batchInsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testGet_WrapsEntityInOptional() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(12L)).thenReturn(entity);

        Optional<TestEntity> result = dao.get(12L);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).gett(12L);
    }

    @Test
    public void testBatchGet_UsesDefaultSelectionAndBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(3L, 4L);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchGet(ids, (java.util.Collection<String>) null)).thenReturn(entities);
        when(dao.batchGet(ids, (java.util.Collection<String>) null, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(entities);

        assertEquals(entities, dao.batchGet(ids));
        assertEquals(entities, dao.batchGet(ids, (java.util.Collection<String>) null));
    }

    @Test
    public void testBatchInsert_NamedInsertUsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<Long> ids = List.of(5L);

        when(dao.batchInsert("insertUser", entities, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(ids);

        assertEquals(ids, dao.batchInsert("insertUser", entities));
        verify(dao).batchInsert("insertUser", entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testGet_WithSelectPropNamesWrapsEntityInOptional() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(15L, List.of("name"))).thenReturn(entity);

        Optional<TestEntity> result = dao.get(15L, List.of("name"));

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
    }

    @Test
    public void testUpsert_UpdatePath_IgnoresId() {
        IdentifiedUncheckedCrudDao dao = Mockito.mock(IdentifiedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
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
    public void testBatchUpsert_EmptyEntities() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertEquals(0, dao.batchUpsert(List.of(), 2).size());
    }

    @Test
    public void testUpsert_InsertPath_WhenEntityNotFound() {
        IdentifiedUncheckedCrudDao dao = Mockito.mock(IdentifiedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdentifiedEntity entity = new IdentifiedEntity();
        entity.setId(5L);
        entity.setName("new");

        when(dao.findOnlyOne(Mockito.any())).thenReturn(Optional.empty());

        IdentifiedEntity result = dao.upsert(entity, Mockito.mock(com.landawn.abacus.query.condition.Condition.class));

        assertSame(entity, result);
        verify(dao).insert(entity);
    }

    @Test
    public void testIdExtractor() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertNull(dao.idExtractor());
    }

    @Test
    public void testBatchInsert_WithPropNames_UsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<Long> ids = List.of(1L);
        List<String> props = List.of("name");

        when(dao.batchInsert(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(ids);

        assertEquals(ids, dao.batchInsert(entities, props));
        verify(dao).batchInsert(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchGet_WithBatchSize_UsesNullSelectProps() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L, 2L);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchGet(ids, (java.util.Collection<String>) null, 500)).thenReturn(entities);

        assertEquals(entities, dao.batchGet(ids, 500));
        verify(dao).batchGet(ids, (java.util.Collection<String>) null, 500);
    }

    @Test
    public void testBatchGet_WithSelectPropsOnly_UsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L);
        List<TestEntity> entities = List.of(new TestEntity());
        List<String> props = List.of("id", "name");

        when(dao.batchGet(ids, props, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(entities);

        assertEquals(entities, dao.batchGet(ids, props));
        verify(dao).batchGet(ids, props, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchUpdate_UsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(1);

        assertEquals(1, dao.batchUpdate(entities));
        verify(dao).batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchUpdate_WithPropNames_UsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<String> props = List.of("name");

        when(dao.batchUpdate(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(1);

        assertEquals(1, dao.batchUpdate(entities, props));
        verify(dao).batchUpdate(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchUpsert_UsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<TestEntity> result = List.of(new TestEntity());

        Mockito.doReturn(result).when(dao).batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertEquals(result, dao.batchUpsert(entities));
        verify(dao).batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchRefresh_UsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        Mockito.doReturn(1).when(dao).batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertEquals(1, dao.batchRefresh(entities));
        verify(dao).batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchRefresh_WithPropNames_UsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<String> props = List.of("name");

        Mockito.doReturn(1).when(dao).batchRefresh(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertEquals(1, dao.batchRefresh(entities, props));
        verify(dao).batchRefresh(entities, props, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchDelete_UsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(1);

        assertEquals(1, dao.batchDelete(entities));
        verify(dao).batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testBatchDeleteByIds_UsesDefaultBatchSize() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L, 2L);

        when(dao.batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(2);

        assertEquals(2, dao.batchDeleteByIds(ids));
        verify(dao).batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testNotExists_DelegatesToExists() {
        TestUncheckedCrudDao dao = Mockito.mock(TestUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        when(dao.exists(5L)).thenReturn(false);
        assertTrue(dao.notExists(5L));

        when(dao.exists(5L)).thenReturn(true);
        assertFalse(dao.notExists(5L));
    }

    @Test
    public void testUpsert_WithUniquePropNames_BuildsCondAndDelegates() {
        IdentifiedUncheckedCrudDao dao = Mockito.mock(IdentifiedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdentifiedEntity entity = new IdentifiedEntity();
        entity.setName("Alice");

        Mockito.doReturn(entity).when(dao).upsert(Mockito.same(entity), Mockito.any(Condition.class));

        IdentifiedEntity result = dao.upsert(entity, List.of("name"));

        assertSame(entity, result);
        verify(dao).upsert(Mockito.same(entity), Mockito.any(Condition.class));
    }

    @Test
    public void testBatchUpsert_WithoutBatchSize_UsesDefault() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<IdAnnotatedEntity> entities = List.of(new IdAnnotatedEntity());

        Mockito.doReturn(entities).when(dao).batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertEquals(entities, dao.batchUpsert(entities));
        verify(dao).batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    // upsert(entity) — single-arg variant uses the id property names from the class.
    @Test
    public void testUpsert_SingleArg_DelegatesToUpsertWithIdPropNames() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(7L);

        Mockito.doReturn(entity).when(dao).upsert(Mockito.same(entity), Mockito.anyList());

        assertSame(entity, dao.upsert(entity));
        verify(dao).upsert(Mockito.same(entity), Mockito.anyList());
    }

    // upsert(entity, cond) — entity class with no id triggers the empty-idPropNameList branch.
    static final class NoIdEntity {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    interface NoIdUncheckedCrudDao extends UncheckedCrudDao<NoIdEntity, Long, NoIdUncheckedCrudDao> {
    }

    @Test
    public void testUpsert_UpdatePath_NoIdField_CopiesAllFields() {
        NoIdUncheckedCrudDao dao = Mockito.mock(NoIdUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        NoIdEntity entity = new NoIdEntity();
        entity.setName("updated");
        NoIdEntity dbEntity = new NoIdEntity();
        dbEntity.setName("original");

        when(dao.findOnlyOne(Mockito.any())).thenReturn(Optional.of(dbEntity));
        when(dao.update(dbEntity)).thenReturn(1);

        NoIdEntity result = dao.upsert(entity, Mockito.mock(Condition.class));

        assertSame(dbEntity, result);
        assertEquals("updated", dbEntity.getName());
    }

    // batchUpsert(entities, batchSize) — non-empty path resolves id and delegates.
    @Test
    public void testBatchUpsert_NonEmpty_DelegatesToFullSignature() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<IdAnnotatedEntity> entities = List.of(new IdAnnotatedEntity());
        List<IdAnnotatedEntity> result = List.of(new IdAnnotatedEntity());

        Mockito.doReturn(result).when(dao).batchUpsert(Mockito.same(entities), Mockito.anyList(), Mockito.eq(2));

        assertEquals(result, dao.batchUpsert(entities, 2));
        verify(dao).batchUpsert(Mockito.same(entities), Mockito.anyList(), Mockito.eq(2));
    }

    // batchUpsert(entities, uniquePropNames) — delegates with default batch size.
    @Test
    public void testBatchUpsert_WithUniquePropNames_UsesDefaultBatchSize() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<IdAnnotatedEntity> entities = List.of(new IdAnnotatedEntity());
        List<String> uniqueProps = List.of("name");
        List<IdAnnotatedEntity> result = List.of(new IdAnnotatedEntity());

        Mockito.doReturn(result).when(dao).batchUpsert(entities, uniqueProps, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertEquals(result, dao.batchUpsert(entities, uniqueProps));
        verify(dao).batchUpsert(entities, uniqueProps, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    // batchUpsert with empty entities returns empty.
    @Test
    public void testBatchUpsert_FullSig_EmptyEntities_ReturnsEmpty() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertEquals(0, dao.batchUpsert(List.of(), List.of("name"), 5).size());
    }

    // batchUpsert with unknown unique prop throws IllegalArgument.
    @Test
    public void testBatchUpsert_FullSig_UnknownUniqueProp_Throws() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<IdAnnotatedEntity> entities = List.of(new IdAnnotatedEntity());

        assertThrows(IllegalArgumentException.class, () -> dao.batchUpsert(entities, List.of("nonExistent"), 5));
    }

    @Test
    public void testBatchUpsert_FullSig_SecondPropUnknown_Throws() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<IdAnnotatedEntity> entities = List.of(new IdAnnotatedEntity());

        assertThrows(IllegalArgumentException.class, () -> dao.batchUpsert(entities, List.of("id", "nonExistent"), 5));
    }

    @Test
    public void testBatchUpsert_FullSig_NonPositiveBatchSize_Throws() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(IllegalArgumentException.class, () -> dao.batchUpsert(List.of(new IdAnnotatedEntity()), List.of("name"), 0));
    }

    @Test
    public void testBatchUpsert_FullSig_EmptyUniqueProps_Throws() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(IllegalArgumentException.class, () -> dao.batchUpsert(List.of(new IdAnnotatedEntity()), List.of(), 5));
    }

    // refresh variants validate input and short-circuit empty collections.
    @Test
    public void testRefresh_NullEntity() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(IllegalArgumentException.class, () -> dao.refresh(null));
    }

    @Test
    public void testRefresh_NotFound() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(3L);

        when(dao.gett(Mockito.eq(3L), Mockito.anyCollection())).thenReturn(null);

        assertFalse(dao.refresh(entity, List.of("name")));
    }

    @Test
    public void testBatchRefresh_EmptyEntities() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertEquals(0, dao.batchRefresh(List.of(), 5));
    }

    @Test
    public void testBatchRefresh_WithPropNames_EmptyEntities() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        assertEquals(0, dao.batchRefresh(List.of(), List.of("name"), 5));
    }

    // refresh(entity) — single-arg delegates to refresh(entity, propNamesToRefresh)
    @Test
    public void testRefresh_SingleArg_DelegatesToRefreshWithProps() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(1L);

        Mockito.doReturn(true).when(dao).refresh(Mockito.same(entity), Mockito.anyCollection());

        assertTrue(dao.refresh(entity));
        verify(dao).refresh(Mockito.same(entity), Mockito.anyCollection());
    }

    // refresh(entity, propNamesToRefresh) — found branch (dbEntity != null)
    @Test
    public void testRefresh_WithPropNames_Found() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(3L);
        entity.setName("stale");
        IdAnnotatedEntity dbEntity = new IdAnnotatedEntity();
        dbEntity.setId(3L);
        dbEntity.setName("fresh");

        when(dao.gett(Mockito.eq(3L), Mockito.anyCollection())).thenReturn(dbEntity);

        assertTrue(dao.refresh(entity, List.of("name")));
        assertEquals("fresh", entity.getName());
    }

    // batchRefresh(entities, batchSize) — non-empty delegates to batchRefresh with props
    @Test
    public void testBatchRefresh_WithBatchSize_NonEmpty_Delegates() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        List<IdAnnotatedEntity> entities = List.of(new IdAnnotatedEntity());

        Mockito.doReturn(1).when(dao).batchRefresh(Mockito.same(entities), Mockito.anyCollection(), Mockito.eq(5));

        assertEquals(1, dao.batchRefresh(entities, 5));
        verify(dao).batchRefresh(Mockito.same(entities), Mockito.anyCollection(), Mockito.eq(5));
    }

    // batchRefresh(entities, propNamesToRefresh, batchSize) — dbEntities empty
    @Test
    public void testBatchRefresh_FullSig_DbEntitiesEmpty_ReturnsZero() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(1L);

        when(dao.batchGet(Mockito.anyCollection(), Mockito.anyCollection(), Mockito.eq(5))).thenReturn(List.of());

        assertEquals(0, dao.batchRefresh(List.of(entity), List.of("name"), 5));
    }

    // batchRefresh(entities, propNamesToRefresh, batchSize) — non-empty refreshes entities
    @Test
    public void testBatchRefresh_FullSig_NonEmpty_RefreshesEntities() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(1L);
        entity.setName("stale");
        IdAnnotatedEntity dbEntity = new IdAnnotatedEntity();
        dbEntity.setId(1L);
        dbEntity.setName("fresh");

        when(dao.batchGet(Mockito.anyCollection(), Mockito.anyCollection(), Mockito.eq(5))).thenReturn(List.of(dbEntity));

        assertEquals(1, dao.batchRefresh(List.of(entity), List.of("name"), 5));
        assertEquals("fresh", entity.getName());
    }

    // batchUpsert(entities, uniquePropNamesForQuery, batchSize) — single unique prop, insert only
    @Test
    public void testBatchUpsert_FullSig_SingleUniqueProp_InsertOnly() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(1L);
        entity.setName("Alice");

        when(dao.list(Mockito.any(Condition.class))).thenReturn(List.of());
        when(dao.batchInsert(Mockito.anyCollection(), Mockito.eq(2))).thenReturn(List.of(1L));

        List<IdAnnotatedEntity> result = dao.batchUpsert(List.of(entity), List.of("name"), 2);

        assertEquals(1, result.size());
        assertSame(entity, result.get(0));
        verify(dao).batchInsert(Mockito.anyCollection(), Mockito.eq(2));
    }

    // batchUpsert(entities, uniquePropNamesForQuery, batchSize) — single unique prop, update only
    @Test
    public void testBatchUpsert_FullSig_SingleUniqueProp_UpdateOnly() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(1L);
        entity.setName("Alice");
        IdAnnotatedEntity dbEntity = new IdAnnotatedEntity();
        dbEntity.setId(10L);
        dbEntity.setName("Alice");

        when(dao.list(Mockito.any(Condition.class))).thenReturn(List.of(dbEntity));
        when(dao.batchUpdate(Mockito.anyCollection(), Mockito.eq(2))).thenReturn(1);

        List<IdAnnotatedEntity> result = dao.batchUpsert(List.of(entity), List.of("name"), 2);

        assertEquals(1, result.size());
        verify(dao).batchUpdate(Mockito.anyCollection(), Mockito.eq(2));
    }

    // batchUpsert(entities, uniquePropNamesForQuery, batchSize) — multi unique prop, insert only
    @Test
    public void testBatchUpsert_FullSig_MultiUniqueProp_InsertOnly() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(1L);
        entity.setName("Alice");

        when(dao.list(Mockito.any(Condition.class))).thenReturn(List.of());
        when(dao.batchInsert(Mockito.anyCollection(), Mockito.eq(2))).thenReturn(List.of(1L));

        List<IdAnnotatedEntity> result = dao.batchUpsert(List.of(entity), List.of("id", "name"), 2);

        assertEquals(1, result.size());
        verify(dao).batchInsert(Mockito.anyCollection(), Mockito.eq(2));
    }

    // batchUpsert(entities, uniquePropNamesForQuery, batchSize) — multi unique prop, update only
    @Test
    public void testBatchUpsert_FullSig_MultiUniqueProp_UpdateOnly() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);
        IdAnnotatedEntity entity = new IdAnnotatedEntity();
        entity.setId(1L);
        entity.setName("Alice");
        IdAnnotatedEntity dbEntity = new IdAnnotatedEntity();
        dbEntity.setId(1L);
        dbEntity.setName("Alice");

        when(dao.list(Mockito.any(Condition.class))).thenReturn(List.of(dbEntity));
        when(dao.batchUpdate(Mockito.anyCollection(), Mockito.eq(2))).thenReturn(1);

        List<IdAnnotatedEntity> result = dao.batchUpsert(List.of(entity), List.of("id", "name"), 2);

        assertEquals(1, result.size());
        verify(dao).batchUpdate(Mockito.anyCollection(), Mockito.eq(2));
    }

    // batchUpsert(entities, uniquePropNamesForQuery, batchSize) — insert-only spanning multiple
    // sub-batches must run within a transaction (mirrors the fix already in CrudDao.batchUpsert).
    // Before the fix the dataSource()/beginTransaction branch was skipped for the insert-only
    // (and update-only) cases regardless of size, so a partial failure could not be rolled back.
    @Test
    public void testBatchUpsert_InsertOnly_ExceedsBatchSize_UsesTransaction() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        IdAnnotatedEntity e1 = new IdAnnotatedEntity();
        e1.setId(1L);
        e1.setName("Alice");
        IdAnnotatedEntity e2 = new IdAnnotatedEntity();
        e2.setId(2L);
        e2.setName("Bob");
        IdAnnotatedEntity e3 = new IdAnnotatedEntity();
        e3.setId(3L);
        e3.setName("Carol");

        // No existing rows -> all three entities go to the insert-only group; batchSize 2 -> 3 > 2.
        when(dao.list(Mockito.any(Condition.class))).thenReturn(List.of());

        final RuntimeException sentinel = new RuntimeException("dataSource() invoked - transaction branch taken");
        when(dao.dataSource()).thenThrow(sentinel);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> dao.batchUpsert(List.of(e1, e2, e3), List.of("name"), 2));

        assertSame(sentinel, thrown);
        verify(dao).dataSource();
    }

    // batchUpsert(entities, uniquePropNamesForQuery, batchSize) — update-only spanning multiple
    // sub-batches must likewise run within a transaction.
    @Test
    public void testBatchUpsert_UpdateOnly_ExceedsBatchSize_UsesTransaction() {
        IdAnnotatedUncheckedCrudDao dao = Mockito.mock(IdAnnotatedUncheckedCrudDao.class, Mockito.CALLS_REAL_METHODS);

        IdAnnotatedEntity e1 = new IdAnnotatedEntity();
        e1.setId(1L);
        e1.setName("Alice");
        IdAnnotatedEntity e2 = new IdAnnotatedEntity();
        e2.setId(2L);
        e2.setName("Bob");
        IdAnnotatedEntity e3 = new IdAnnotatedEntity();
        e3.setId(3L);
        e3.setName("Carol");

        IdAnnotatedEntity db1 = new IdAnnotatedEntity();
        db1.setId(1L);
        db1.setName("Alice");
        IdAnnotatedEntity db2 = new IdAnnotatedEntity();
        db2.setId(2L);
        db2.setName("Bob");
        IdAnnotatedEntity db3 = new IdAnnotatedEntity();
        db3.setId(3L);
        db3.setName("Carol");

        // All three match existing rows -> update-only group; batchSize 2 -> 3 > 2.
        // batchUpsert splits the entities into sub-batches of batchSize and calls list(...) once
        // per sub-batch ([e1,e2] then [e3]), flat-mapping the results into an id-keyed map with a
        // throwing merger; return the matching rows per consecutive call so ids stay unique.
        when(dao.list(Mockito.any(Condition.class))).thenReturn(List.of(db1, db2), List.of(db3));

        final RuntimeException sentinel = new RuntimeException("dataSource() invoked - transaction branch taken");
        when(dao.dataSource()).thenThrow(sentinel);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> dao.batchUpsert(List.of(e1, e2, e3), List.of("name"), 2));

        assertSame(sentinel, thrown);
        verify(dao).dataSource();
    }
}
