package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.u.Optional;

public class CrudJoinEntityHelperTest extends TestBase {

    interface TestCrudJoinDao extends CrudDao<TestEntity, Long, PSC, TestCrudJoinDao>, CrudJoinEntityHelper<TestEntity, Long, PSC, TestCrudJoinDao> {
    }

    static final class TestEntity {
    }

    @Test
    public void testGet_LoadsRequestedJoinEntity() throws SQLException {
        TestCrudJoinDao dao = Mockito.mock(TestCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(7L)).thenReturn(entity);
        doNothing().when(dao).loadJoinEntities(entity, String.class);

        Optional<TestEntity> result = dao.get(7L, String.class);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).gett(7L);
        verify(dao).loadJoinEntities(entity, String.class);
    }

    @Test
    public void testGet_LoadsAllJoinEntitiesWhenRequested() throws SQLException {
        TestCrudJoinDao dao = Mockito.mock(TestCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(8L, List.of("id"))).thenReturn(entity);
        doNothing().when(dao).loadAllJoinEntities(entity);

        Optional<TestEntity> result = dao.get(8L, List.of("id"), true);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadAllJoinEntities(entity);
    }

    @Test
    public void testBatchGet_UsesDefaultBatchSize() throws SQLException {
        TestCrudJoinDao dao = Mockito.mock(TestCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L, 2L);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchGet(ids, null, String.class, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(entities);

        assertEquals(entities, dao.batchGet(ids, String.class));
    }

    @Test
    public void testGet_UsesSelectedProperties() throws SQLException {
        TestCrudJoinDao dao = Mockito.mock(TestCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(9L, List.of("name"), String.class)).thenReturn(entity);

        Optional<TestEntity> result = dao.get(9L, List.of("name"), String.class);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
    }

    @Test
    public void testGet_UsesCollectionJoinEntityOverloads() throws SQLException {
        TestCrudJoinDao dao = Mockito.mock(TestCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(10L)).thenReturn(entity);
        when(dao.gett(11L, List.of("name"))).thenReturn(entity);
        when(dao.gett(12L, List.of("name"))).thenReturn(entity);
        doNothing().when(dao).loadAllJoinEntities(entity);
        doNothing().when(dao).loadJoinEntities(entity, String.class);
        doNothing().when(dao).loadJoinEntities(entity, Integer.class);

        assertSame(entity, dao.get(10L, true).orElseNull());
        assertSame(entity, dao.get(11L, List.of("name"), List.of(String.class, Integer.class)).orElseNull());
        assertSame(entity, dao.gett(12L, List.of("name"), List.of(String.class, Integer.class)));

        verify(dao).loadAllJoinEntities(entity);
        verify(dao, times(2)).loadJoinEntities(entity, String.class);
        verify(dao, times(2)).loadJoinEntities(entity, Integer.class);
    }

    @Test
    public void testGett_SkipsJoinLoadingForNullResultsOrDisabledFlags() throws SQLException {
        TestCrudJoinDao dao = Mockito.mock(TestCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.gett(13L)).thenReturn(null);
        when(dao.gett(14L, List.of("id"))).thenReturn(entity);
        when(dao.gett(15L, List.of("id"))).thenReturn(entity);

        assertNull(dao.gett(13L, true));
        assertSame(entity, dao.gett(14L, List.of("id"), false));
        assertSame(entity, dao.gett(15L, List.of("id"), List.of()));

        verify(dao, never()).loadAllJoinEntities(entity);
        verify(dao, never()).loadJoinEntities(eq(entity), eq(String.class));
    }

    @Test
    public void testBatchGet_UsesDefaultBatchSizeForAdditionalOverloads() throws SQLException {
        TestCrudJoinDao dao = Mockito.mock(TestCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L, 2L);
        List<String> selectPropNames = List.of("name");
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.batchGet(ids, null, true, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(entities);
        when(dao.batchGet(ids, selectPropNames, String.class, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(entities);
        when(dao.batchGet(ids, selectPropNames, List.of(String.class, Integer.class), JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(entities);
        when(dao.batchGet(ids, selectPropNames, true, JdbcUtil.DEFAULT_BATCH_SIZE)).thenReturn(entities);

        assertEquals(entities, dao.batchGet(ids, true));
        assertEquals(entities, dao.batchGet(ids, selectPropNames, String.class));
        assertEquals(entities, dao.batchGet(ids, selectPropNames, List.of(String.class, Integer.class)));
        assertEquals(entities, dao.batchGet(ids, selectPropNames, true));
    }

    @Test
    public void testBatchGet_LoadsJoinEntitiesWithinSingleBatch() throws SQLException {
        TestCrudJoinDao dao = Mockito.mock(TestCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L, 2L);
        List<String> selectPropNames = List.of("name");
        List<TestEntity> entities = List.of(new TestEntity(), new TestEntity());

        when(dao.batchGet(ids, selectPropNames, 2)).thenReturn(entities);
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(String.class));
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(Integer.class));
        doNothing().when(dao).loadAllJoinEntities(Mockito.<Collection<TestEntity>> any());

        assertEquals(entities, dao.batchGet(ids, selectPropNames, String.class, 2));
        assertEquals(entities, dao.batchGet(ids, selectPropNames, List.of(String.class, Integer.class), 2));
        assertEquals(entities, dao.batchGet(ids, selectPropNames, true, 2));

        verify(dao, times(2)).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(String.class));
        verify(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(Integer.class));
        verify(dao).loadAllJoinEntities(Mockito.<Collection<TestEntity>> any());
    }

    @Test
    public void testBatchGet_LoadsJoinEntitiesAcrossBatches() throws SQLException {
        TestCrudJoinDao dao = Mockito.mock(TestCrudJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<Long> ids = List.of(1L, 2L, 3L);
        List<String> selectPropNames = List.of("name");
        List<TestEntity> entities = List.of(new TestEntity(), new TestEntity(), new TestEntity());

        when(dao.batchGet(ids, selectPropNames, 2)).thenReturn(entities);
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(String.class));
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(Integer.class));
        doNothing().when(dao).loadAllJoinEntities(Mockito.<Collection<TestEntity>> any());

        assertEquals(3, dao.batchGet(ids, selectPropNames, String.class, 2).size());
        assertEquals(3, dao.batchGet(ids, selectPropNames, List.of(String.class, Integer.class), 2).size());
        assertEquals(3, dao.batchGet(ids, selectPropNames, true, 2).size());

        verify(dao, times(4)).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(String.class));
        verify(dao, times(2)).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(Integer.class));
        verify(dao, times(2)).loadAllJoinEntities(Mockito.<Collection<TestEntity>> any());
    }
}
