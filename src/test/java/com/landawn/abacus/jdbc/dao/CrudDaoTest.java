package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
