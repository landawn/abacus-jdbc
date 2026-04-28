package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.u.Optional;

public class UncheckedJoinEntityHelperTest extends TestBase {

    interface TestUncheckedJoinDao
            extends UncheckedDao<TestEntity, PSC, TestUncheckedJoinDao>, UncheckedJoinEntityHelper<TestEntity, PSC, TestUncheckedJoinDao> {
    }

    static final class TestEntity {
    }

    @Test
    public void testFindFirst_LoadsRequestedJoinEntity() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(null, condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadJoinEntities(entity, String.class);

        Optional<TestEntity> result = dao.findFirst(null, String.class, condition);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadJoinEntities(entity, String.class);
    }

    @Test
    public void testFindFirst_LoadsAllJoinEntitiesWhenRequested() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(List.of("id"), condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadAllJoinEntities(entity);

        Optional<TestEntity> result = dao.findFirst(List.of("id"), true, condition);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadAllJoinEntities(entity);
    }

    @Test
    public void testFindFirst_LoadsEachRequestedJoinEntity_CollectionOfClasses() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(null, condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadJoinEntities(eq(entity), eq(String.class));
        doNothing().when(dao).loadJoinEntities(eq(entity), eq(Integer.class));

        Optional<TestEntity> result = dao.findFirst(null, List.of(String.class, Integer.class), condition);

        assertTrue(result.isPresent());
        verify(dao).loadJoinEntities(entity, String.class);
        verify(dao).loadJoinEntities(entity, Integer.class);
    }

    @Test
    public void testFindOnlyOne_LoadsEachRequestedJoinEntity() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(null, condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadJoinEntities(eq(entity), eq(String.class));
        doNothing().when(dao).loadJoinEntities(eq(entity), eq(Integer.class));

        Optional<TestEntity> result = dao.findOnlyOne(null, List.of(String.class, Integer.class), condition);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadJoinEntities(entity, String.class);
        verify(dao).loadJoinEntities(entity, Integer.class);
    }

    @Test
    public void testFindOnlyOne_LoadsSingleJoinEntityClass() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(null, condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadJoinEntities(eq(entity), eq(String.class));

        Optional<TestEntity> result = dao.findOnlyOne(null, String.class, condition);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadJoinEntities(entity, String.class);
    }

    @Test
    public void testFindOnlyOne_LoadsAllJoinEntitiesWhenRequested() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(List.of("id"), condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadAllJoinEntities(entity);

        Optional<TestEntity> result = dao.findOnlyOne(List.of("id"), true, condition);

        assertTrue(result.isPresent());
        verify(dao).loadAllJoinEntities(entity);
    }

    @Test
    public void testList_LoadsSingleJoinEntityClass() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);
        List<TestEntity> entities = List.of(entity);

        when(dao.list((Collection<String>) null, condition)).thenReturn(entities);
        doNothing().when(dao).loadJoinEntities(eq(entities), eq(String.class));

        List<TestEntity> result = dao.list(null, String.class, condition);

        assertNotNull(result);
        assertEquals(1, result.size());
        verify(dao).loadJoinEntities(entities, String.class);
    }

    @Test
    public void testList_LoadsCollectionOfJoinEntityClasses() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);
        List<TestEntity> entities = List.of(entity);

        when(dao.list((Collection<String>) null, condition)).thenReturn(entities);
        doNothing().when(dao).loadJoinEntities(eq(entities), eq(String.class));
        doNothing().when(dao).loadJoinEntities(eq(entities), eq(Integer.class));

        List<TestEntity> result = dao.list(null, List.of(String.class, Integer.class), condition);

        assertNotNull(result);
        verify(dao).loadJoinEntities(entities, String.class);
        verify(dao).loadJoinEntities(entities, Integer.class);
    }

    @Test
    public void testList_LoadsAllJoinEntitiesWhenRequested() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);
        List<TestEntity> entities = List.of(entity);

        when(dao.list((Collection<String>) null, condition)).thenReturn(entities);
        doNothing().when(dao).loadAllJoinEntities(entities);

        List<TestEntity> result = dao.list(null, true, condition);

        assertNotNull(result);
        verify(dao).loadAllJoinEntities(entities);
    }

    @Test
    public void testLoadJoinEntities_SingleEntity_ClassOnly_DelegatesTo3Param() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        doNothing().when(dao).loadJoinEntities(eq(entity), eq(String.class), isNull());

        dao.loadJoinEntities(entity, String.class);

        verify(dao).loadJoinEntities(entity, String.class, null);
    }

    @Test
    public void testLoadJoinEntities_CollectionEntity_ClassOnly_DelegatesTo3Param() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        doNothing().when(dao).loadJoinEntities(eq(entities), eq(String.class), isNull());

        dao.loadJoinEntities(entities, String.class);

        verify(dao).loadJoinEntities(entities, String.class, null);
    }

    @Test
    public void testLoadJoinEntities_SingleEntity_PropNameOnly_DelegatesTo3Param() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        doNothing().when(dao).loadJoinEntities(eq(entity), eq("orders"), isNull());

        dao.loadJoinEntities(entity, "orders");

        verify(dao).loadJoinEntities(entity, "orders", null);
    }

    @Test
    public void testLoadJoinEntitiesIfNull_SingleEntity_ClassOnly_DelegatesTo3Param() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        doNothing().when(dao).loadJoinEntitiesIfNull(eq(entity), eq(String.class), isNull());

        dao.loadJoinEntitiesIfNull(entity, String.class);

        verify(dao).loadJoinEntitiesIfNull(entity, String.class, null);
    }

    @Test
    public void testLoadJoinEntitiesIfNull_CollectionEntity_ClassOnly_DelegatesTo3Param() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        doNothing().when(dao).loadJoinEntitiesIfNull(eq(entities), eq(String.class), isNull());

        dao.loadJoinEntitiesIfNull(entities, String.class);

        verify(dao).loadJoinEntitiesIfNull(entities, String.class, null);
    }

    // loadJoinEntities(entity, Collection<String>) — loops over each property name.
    @Test
    public void testLoadJoinEntities_Entity_PropNames_LoopsEach() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntities(eq(entity), Mockito.anyString());

        dao.loadJoinEntities(entity, List.of("orders", "addresses"));

        verify(dao).loadJoinEntities(entity, "orders");
        verify(dao).loadJoinEntities(entity, "addresses");
    }

    @Test
    public void testLoadJoinEntities_Entity_PropNames_EmptyShortCircuits() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        dao.loadJoinEntities(entity, List.<String>of());
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(Mockito.eq(entity), Mockito.anyString());
    }

    @Test
    public void testLoadJoinEntities_Entity_PropNames_InParallelFalse() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntities(Mockito.same(entity), Mockito.anyCollection());

        dao.loadJoinEntities(entity, List.of("orders"), false);

        verify(dao).loadJoinEntities(entity, List.of("orders"));
    }

    @Test
    public void testLoadJoinEntities_Entity_PropNames_WithExecutor_RunsLoad() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntities(eq(entity), Mockito.anyString());

        dao.loadJoinEntities(entity, List.of("orders", "addresses"), Runnable::run);

        verify(dao).loadJoinEntities(entity, "orders");
        verify(dao).loadJoinEntities(entity, "addresses");
    }

    @Test
    public void testLoadJoinEntities_Entity_PropNames_WithExecutor_EmptyShortCircuits() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        dao.loadJoinEntities(entity, List.<String>of(), Runnable::run);
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(Mockito.same(entity), Mockito.anyString());
    }

    @Test
    public void testLoadJoinEntities_Entity_PropNames_InParallelTrue_UsesExecutor() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadJoinEntities(eq(entity), Mockito.anyString());

        dao.loadJoinEntities(entity, List.of("orders"), true);

        verify(dao).executor();
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_LoopsEach() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntities(Mockito.same(entities), Mockito.anyString());

        dao.loadJoinEntities(entities, List.of("orders", "addresses"));

        verify(dao).loadJoinEntities(entities, "orders");
        verify(dao).loadJoinEntities(entities, "addresses");
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_EmptyEntitiesShortCircuits() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntities(List.<TestEntity>of(), List.of("orders"));
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(Mockito.<List<TestEntity>>any(), Mockito.anyString());
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_InParallelFalse() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntities(Mockito.same(entities), Mockito.anyCollection());

        dao.loadJoinEntities(entities, List.of("orders"), false);

        verify(dao).loadJoinEntities(entities, List.of("orders"));
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_WithExecutor_RunsLoad() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntities(Mockito.same(entities), Mockito.anyString());

        dao.loadJoinEntities(entities, List.of("orders", "addresses"), Runnable::run);

        verify(dao).loadJoinEntities(entities, "orders");
        verify(dao).loadJoinEntities(entities, "addresses");
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_WithExecutor_EmptyShortCircuits() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntities(List.<TestEntity>of(), List.of("orders"), Runnable::run);
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(Mockito.<List<TestEntity>>any(), Mockito.anyString());
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_InParallelTrue_UsesExecutor() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadJoinEntities(Mockito.same(entities), Mockito.anyString());

        dao.loadJoinEntities(entities, List.of("orders"), true);

        verify(dao).executor();
    }

    @Test
    public void testLoadAllJoinEntities_Entity_InParallelFalse() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadAllJoinEntities(entity);

        dao.loadAllJoinEntities(entity, false);

        verify(dao).loadAllJoinEntities(entity);
    }

    @Test
    public void testLoadAllJoinEntities_Entity_InParallelTrue_UsesExecutor() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadAllJoinEntities(Mockito.same(entity), Mockito.<java.util.concurrent.Executor>any());

        dao.loadAllJoinEntities(entity, true);

        verify(dao).executor();
    }

    @Test
    public void testLoadAllJoinEntities_Entities_InParallelFalse() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadAllJoinEntities(entities);

        dao.loadAllJoinEntities(entities, false);

        verify(dao).loadAllJoinEntities(entities);
    }

    @Test
    public void testLoadAllJoinEntities_Entities_InParallelTrue_UsesExecutor() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadAllJoinEntities(Mockito.same(entities), Mockito.<java.util.concurrent.Executor>any());

        dao.loadAllJoinEntities(entities, true);

        verify(dao).executor();
    }

    @Test
    public void testLoadJoinEntitiesIfNull_Entity_PropNames_LoopsEach() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntitiesIfNull(eq(entity), Mockito.anyString());

        dao.loadJoinEntitiesIfNull(entity, List.of("orders", "addresses"));

        verify(dao).loadJoinEntitiesIfNull(entity, "orders");
        verify(dao).loadJoinEntitiesIfNull(entity, "addresses");
    }

    @Test
    public void testLoadJoinEntitiesIfNull_Entities_PropNames_LoopsEach() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntitiesIfNull(Mockito.same(entities), Mockito.anyString());

        dao.loadJoinEntitiesIfNull(entities, List.of("orders", "addresses"));

        verify(dao).loadJoinEntitiesIfNull(entities, "orders");
        verify(dao).loadJoinEntitiesIfNull(entities, "addresses");
    }

    // stream variants load joins while flattening split batches.
    @Test
    public void testStream_LoadsSingleJoinEntityClass() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity first = new TestEntity();
        TestEntity second = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);
        List<String> selectPropNames = List.of("id");

        when(dao.stream(selectPropNames, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(first, second));
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>>any(), eq(String.class));

        List<TestEntity> result = dao.stream(selectPropNames, String.class, condition).toList();

        assertEquals(2, result.size());
        verify(dao).loadJoinEntities(Mockito.<Collection<TestEntity>>any(), eq(String.class));
    }

    @Test
    public void testStream_LoadsCollectionOfJoinEntityClasses() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>>any(), eq(String.class));
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>>any(), eq(Integer.class));

        List<TestEntity> result = dao.stream(null, List.of(String.class, Integer.class), condition).toList();

        assertEquals(1, result.size());
        verify(dao).loadJoinEntities(Mockito.<Collection<TestEntity>>any(), eq(String.class));
        verify(dao).loadJoinEntities(Mockito.<Collection<TestEntity>>any(), eq(Integer.class));
    }

    @Test
    public void testStream_LoadsAllJoinEntitiesWhenRequested() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));
        doNothing().when(dao).loadAllJoinEntities(Mockito.<Collection<TestEntity>>any());

        List<TestEntity> result = dao.stream(null, true, condition).toList();

        assertEquals(1, result.size());
        verify(dao).loadAllJoinEntities(Mockito.<Collection<TestEntity>>any());
    }

    @Test
    public void testStream_IncludeAllJoinEntitiesFalse() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));

        List<TestEntity> result = dao.stream(null, false, condition).toList();

        assertEquals(1, result.size());
    }
}
