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
}
