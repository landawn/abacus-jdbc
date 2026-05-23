package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.JoinInfo;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SqlTransaction;
import com.landawn.abacus.jdbc.dao.DaoUtil;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.u.Optional;

public class UncheckedJoinEntityHelperTest extends TestBase {

    interface TestUncheckedJoinDao
            extends UncheckedDao<TestEntity, PSC, TestUncheckedJoinDao>, UncheckedJoinEntityHelper<TestEntity, PSC, TestUncheckedJoinDao> {
    }

    static final class TestEntity {
        private long id;

        private String orders;

        private String addresses;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getOrders() {
            return orders;
        }

        public void setOrders(String orders) {
            this.orders = orders;
        }

        public String getAddresses() {
            return addresses;
        }

        public void setAddresses(String addresses) {
            this.addresses = addresses;
        }
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

        dao.loadJoinEntities(entity, List.<String> of());
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

        dao.loadJoinEntities(entity, List.<String> of(), Runnable::run);
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

        dao.loadJoinEntities(List.<TestEntity> of(), List.of("orders"));
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(Mockito.<List<TestEntity>> any(), Mockito.anyString());
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

        dao.loadJoinEntities(List.<TestEntity> of(), List.of("orders"), Runnable::run);
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(Mockito.<List<TestEntity>> any(), Mockito.anyString());
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
        doNothing().when(dao).loadAllJoinEntities(Mockito.same(entity), Mockito.<java.util.concurrent.Executor> any());

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
        doNothing().when(dao).loadAllJoinEntities(Mockito.same(entities), Mockito.<java.util.concurrent.Executor> any());

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
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(String.class));

        List<TestEntity> result = dao.stream(selectPropNames, String.class, condition).toList();

        assertEquals(2, result.size());
        verify(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(String.class));
    }

    @Test
    public void testStream_LoadsCollectionOfJoinEntityClasses() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(String.class));
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(Integer.class));

        List<TestEntity> result = dao.stream(null, List.of(String.class, Integer.class), condition).toList();

        assertEquals(1, result.size());
        verify(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(String.class));
        verify(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), eq(Integer.class));
    }

    @Test
    public void testStream_LoadsAllJoinEntitiesWhenRequested() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));
        doNothing().when(dao).loadAllJoinEntities(Mockito.<Collection<TestEntity>> any());

        List<TestEntity> result = dao.stream(null, true, condition).toList();

        assertEquals(1, result.size());
        verify(dao).loadAllJoinEntities(Mockito.<Collection<TestEntity>> any());
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

    // loadJoinEntities(Collection, String) — delegates to 3-param
    @Test
    public void testLoadJoinEntities_Entities_PropNameOnly_DelegatesTo3Param() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        doNothing().when(dao).loadJoinEntities(eq(entities), eq("orders"), isNull());

        dao.loadJoinEntities(entities, "orders");

        verify(dao).loadJoinEntities(entities, "orders", null);
    }

    // loadJoinEntitiesIfNull(T, String) — delegates to 3-param
    @Test
    public void testLoadJoinEntitiesIfNull_Entity_PropNameOnly_DelegatesTo3Param() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        doNothing().when(dao).loadJoinEntitiesIfNull(eq(entity), eq("profile"), isNull());

        dao.loadJoinEntitiesIfNull(entity, "profile");

        verify(dao).loadJoinEntitiesIfNull(entity, "profile", null);
    }

    // loadJoinEntitiesIfNull(Collection, String) — delegates to 3-param
    @Test
    public void testLoadJoinEntitiesIfNull_Entities_PropNameOnly_DelegatesTo3Param() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        doNothing().when(dao).loadJoinEntitiesIfNull(eq(entities), eq("orders"), isNull());

        dao.loadJoinEntitiesIfNull(entities, "orders");

        verify(dao).loadJoinEntitiesIfNull(entities, "orders", null);
    }

    // loadJoinEntitiesIfNull(T, Collection<String>) — empty prop names short-circuits
    @Test
    public void testLoadJoinEntitiesIfNull_Entity_PropNames_EmptyShortCircuits() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        dao.loadJoinEntitiesIfNull(entity, List.<String> of());
        Mockito.verify(dao, Mockito.never()).loadJoinEntitiesIfNull(Mockito.eq(entity), Mockito.anyString());
    }

    // loadJoinEntitiesIfNull(T, Collection<String>, boolean) — false branch
    @Test
    public void testLoadJoinEntitiesIfNull_Entity_PropNames_InParallelFalse() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntitiesIfNull(Mockito.same(entity), Mockito.anyCollection());

        dao.loadJoinEntitiesIfNull(entity, List.of("orders"), false);

        verify(dao).loadJoinEntitiesIfNull(entity, List.of("orders"));
    }

    // loadJoinEntitiesIfNull(T, Collection<String>, boolean) — true branch
    @Test
    public void testLoadJoinEntitiesIfNull_Entity_PropNames_InParallelTrue_UsesExecutor() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadJoinEntitiesIfNull(Mockito.same(entity), Mockito.anyCollection(), Mockito.<java.util.concurrent.Executor> any());

        dao.loadJoinEntitiesIfNull(entity, List.of("orders"), true);

        verify(dao).executor();
    }

    // loadJoinEntitiesIfNull(T, Collection<String>, Executor) — runs load in parallel
    @Test
    public void testLoadJoinEntitiesIfNull_Entity_PropNames_WithExecutor_RunsLoad() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        entity.setOrders("existing"); // non-null so filter skips
        entity.setAddresses(null); // null so filter includes
        doNothing().when(dao).loadJoinEntities(eq(entity), Mockito.anyString());

        dao.loadJoinEntitiesIfNull(entity, List.of("orders", "addresses"), Runnable::run);

        verify(dao).loadJoinEntities(entity, "addresses");
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(eq(entity), eq("orders"));
    }

    // loadJoinEntitiesIfNull(Collection, Collection<String>, boolean) — false branch
    @Test
    public void testLoadJoinEntitiesIfNull_Entities_PropNames_InParallelFalse() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntitiesIfNull(Mockito.same(entities), Mockito.anyCollection());

        dao.loadJoinEntitiesIfNull(entities, List.of("orders"), false);

        verify(dao).loadJoinEntitiesIfNull(entities, List.of("orders"));
    }

    // loadJoinEntitiesIfNull(Collection, Collection<String>, boolean) — true branch
    @Test
    public void testLoadJoinEntitiesIfNull_Entities_PropNames_InParallelTrue_UsesExecutor() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadJoinEntitiesIfNull(Mockito.same(entities), Mockito.anyCollection(), Mockito.<java.util.concurrent.Executor> any());

        dao.loadJoinEntitiesIfNull(entities, List.of("orders"), true);

        verify(dao).executor();
    }

    // loadJoinEntitiesIfNull(Collection, Collection<String>, Executor) — runs load in parallel
    @Test
    public void testLoadJoinEntitiesIfNull_Entities_PropNames_WithExecutor_RunsLoad() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntitiesIfNull(Mockito.same(entities), Mockito.anyString());

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            dao.loadJoinEntitiesIfNull(entities, List.of("orders", "addresses"), Runnable::run);

            verify(dao).loadJoinEntitiesIfNull(entities, "orders");
            verify(dao).loadJoinEntitiesIfNull(entities, "addresses");
        }
    }

    // loadJoinEntitiesIfNull(T, boolean) — false branch
    @Test
    public void testLoadJoinEntitiesIfNull_Entity_InParallelFalse() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntitiesIfNull(entity);

        dao.loadJoinEntitiesIfNull(entity, false);

        verify(dao).loadJoinEntitiesIfNull(entity);
    }

    // loadJoinEntitiesIfNull(T, boolean) — true branch
    @Test
    public void testLoadJoinEntitiesIfNull_Entity_InParallelTrue_UsesExecutor() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadJoinEntitiesIfNull(Mockito.same(entity), Mockito.<java.util.concurrent.Executor> any());

        dao.loadJoinEntitiesIfNull(entity, true);

        verify(dao).executor();
    }

    // loadJoinEntitiesIfNull(T, Executor) — delegates to propNames overload
    @Test
    public void testLoadJoinEntitiesIfNull_Entity_WithExecutor_DelegatesToPropNames() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            Map<String, JoinInfo> joinInfoMap = Map.of("orders", Mockito.mock(JoinInfo.class));
            daoUtil.when(() -> DaoUtil.getEntityJoinInfo(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(joinInfoMap);
            doNothing().when(dao)
                    .loadJoinEntitiesIfNull(Mockito.same(entity), Mockito.<Collection<String>> any(), Mockito.<java.util.concurrent.Executor> any());

            dao.loadJoinEntitiesIfNull(entity, Runnable::run);

            verify(dao).loadJoinEntitiesIfNull(Mockito.eq(entity), Mockito.<Collection<String>> any(), Mockito.<java.util.concurrent.Executor> any());
        }
    }

    // loadJoinEntitiesIfNull(T) — delegates to propNames overload via DaoUtil
    @Test
    public void testLoadJoinEntitiesIfNull_Entity_DelegatesToJoinEntityPropNames() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            Map<String, JoinInfo> joinInfoMap = Map.of("orders", Mockito.mock(JoinInfo.class));
            daoUtil.when(() -> DaoUtil.getEntityJoinInfo(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(joinInfoMap);
            doNothing().when(dao).loadJoinEntitiesIfNull(Mockito.same(entity), Mockito.<Collection<String>> any());

            dao.loadJoinEntitiesIfNull(entity);

            verify(dao).loadJoinEntitiesIfNull(entity, joinInfoMap.keySet());
        }
    }

    // loadJoinEntitiesIfNull(Collection, boolean) — false branch
    @Test
    public void testLoadJoinEntitiesIfNull_Entities_InParallelFalse() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntitiesIfNull(entities);

        dao.loadJoinEntitiesIfNull(entities, false);

        verify(dao).loadJoinEntitiesIfNull(entities);
    }

    // loadJoinEntitiesIfNull(Collection, boolean) — true branch
    @Test
    public void testLoadJoinEntitiesIfNull_Entities_InParallelTrue_UsesExecutor() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadJoinEntitiesIfNull(Mockito.same(entities), Mockito.<java.util.concurrent.Executor> any());

        dao.loadJoinEntitiesIfNull(entities, true);

        verify(dao).executor();
    }

    // loadJoinEntitiesIfNull(Collection) — empty entities short-circuits
    @Test
    public void testLoadJoinEntitiesIfNull_Entities_EmptyShortCircuits() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntitiesIfNull(List.<TestEntity> of());
        Mockito.verify(dao, Mockito.never()).loadJoinEntitiesIfNull(Mockito.<Collection<TestEntity>> any(), Mockito.<Collection<String>> any());
    }

    // loadJoinEntitiesIfNull(Collection) — delegates to propNames overload via DaoUtil
    @Test
    public void testLoadJoinEntitiesIfNull_Entities_DelegatesToJoinEntityPropNames() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            Map<String, JoinInfo> joinInfoMap = Map.of("orders", Mockito.mock(JoinInfo.class));
            daoUtil.when(() -> DaoUtil.getEntityJoinInfo(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(joinInfoMap);
            doNothing().when(dao).loadJoinEntitiesIfNull(Mockito.same(entities), Mockito.<Collection<String>> any());

            dao.loadJoinEntitiesIfNull(entities);

            verify(dao).loadJoinEntitiesIfNull(entities, joinInfoMap.keySet());
        }
    }

    // loadJoinEntitiesIfNull(Collection, Executor) — empty entities short-circuits
    @Test
    public void testLoadJoinEntitiesIfNull_Entities_WithExecutor_EmptyShortCircuits() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntitiesIfNull(List.<TestEntity> of(), Runnable::run);
        Mockito.verify(dao, Mockito.never())
                .loadJoinEntitiesIfNull(Mockito.<Collection<TestEntity>> any(), Mockito.<Collection<String>> any(),
                        Mockito.<java.util.concurrent.Executor> any());
    }

    // loadJoinEntitiesIfNull(Collection, Executor) — delegates to propNames overload via DaoUtil
    @Test
    public void testLoadJoinEntitiesIfNull_Entities_WithExecutor_DelegatesToPropNames() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            Map<String, JoinInfo> joinInfoMap = Map.of("orders", Mockito.mock(JoinInfo.class));
            daoUtil.when(() -> DaoUtil.getEntityJoinInfo(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(joinInfoMap);
            doNothing().when(dao)
                    .loadJoinEntitiesIfNull(Mockito.same(entities), Mockito.<Collection<String>> any(), Mockito.<java.util.concurrent.Executor> any());

            dao.loadJoinEntitiesIfNull(entities, Runnable::run);

            verify(dao).loadJoinEntitiesIfNull(Mockito.eq(entities), Mockito.<Collection<String>> any(), Mockito.<java.util.concurrent.Executor> any());
        }
    }

    // list(Collection, Class, Condition) — large result set triggers runByBatch
    @Test
    public void testList_SingleJoinEntityClass_LargeBatch() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);
        final java.util.List<TestEntity> largeEntities = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            largeEntities.add(new TestEntity());
        }

        when(dao.list((Collection<String>) null, condition)).thenReturn(largeEntities);
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), Mockito.<Class<?>> any());

        List<TestEntity> result = dao.list(null, String.class, condition);

        assertNotNull(result);
        assertEquals(201, result.size());
    }

    // list(Collection, Collection<Class>, Condition) — large result set triggers runByBatch
    @Test
    public void testList_CollectionOfJoinEntityClasses_LargeBatch() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);
        final java.util.List<TestEntity> largeEntities = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            largeEntities.add(new TestEntity());
        }

        when(dao.list((Collection<String>) null, condition)).thenReturn(largeEntities);
        doNothing().when(dao).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), Mockito.<Class<?>> any());

        List<TestEntity> result = dao.list(null, List.of(String.class, Integer.class), condition);

        assertNotNull(result);
        assertEquals(201, result.size());
    }

    // list(Collection, boolean, Condition) — large result set triggers runByBatch for all entities
    @Test
    public void testList_AllJoinEntities_LargeBatch() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);
        final java.util.List<TestEntity> largeEntities = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            largeEntities.add(new TestEntity());
        }

        when(dao.list((Collection<String>) null, condition)).thenReturn(largeEntities);
        doNothing().when(dao).loadAllJoinEntities(Mockito.<Collection<TestEntity>> any());

        List<TestEntity> result = dao.list(null, true, condition);

        assertNotNull(result);
        assertEquals(201, result.size());
    }

    // ---- loadJoinEntities(T, Class<?>, Collection<String>) — UncheckedJoinEntityHelper L470-480 ----

    // Resolves prop names by type and delegates to the per-prop overload for each match.
    @Test
    public void testLoadJoinEntities_EntityByClass_WithSelectProps_RunsLoop() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        List<String> selectProps = List.of("id");
        Mockito.doReturn(TestEntity.class).when(dao).targetEntityClass();
        Mockito.doReturn(TestUncheckedJoinDao.class).when(dao).targetDaoInterface();
        Mockito.doReturn("test_entity").when(dao).targetTableName();
        doNothing().when(dao).loadJoinEntities(Mockito.same(entity), Mockito.anyString(), Mockito.same(selectProps));

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            daoUtil.when(() -> DaoUtil.getJoinEntityPropNamesByType(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(String.class)))
                    .thenReturn(List.of("orders", "addresses"));

            dao.loadJoinEntities(entity, String.class, selectProps);

            verify(dao).loadJoinEntities(entity, "orders", selectProps);
            verify(dao).loadJoinEntities(entity, "addresses", selectProps);
        }
    }

    // No joined property of the requested type → IllegalArgumentException from N.checkArgument (L475).
    @Test
    public void testLoadJoinEntities_EntityByClass_NoJoinPropOfType_Throws() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Mockito.doReturn(TestEntity.class).when(dao).targetEntityClass();
        Mockito.doReturn(TestUncheckedJoinDao.class).when(dao).targetDaoInterface();
        Mockito.doReturn("test_entity").when(dao).targetTableName();

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            daoUtil.when(() -> DaoUtil.getJoinEntityPropNamesByType(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(new ArrayList<>());

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> dao.loadJoinEntities(entity, Integer.class, List.of("id")));
        }
    }

    // ---- loadJoinEntities(Collection<T>, Class<?>, Collection<String>) — L526-540 ----

    // Empty input collection short-circuits before any prop-name resolution (L529).
    @Test
    public void testLoadJoinEntities_EntitiesByClass_EmptyShortCircuits() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntities(new ArrayList<TestEntity>(), String.class, List.of("id"));

        // No interaction with the per-prop overload because the collection was empty.
        verify(dao, Mockito.never()).loadJoinEntities(Mockito.<Collection<TestEntity>> any(), Mockito.anyString(), Mockito.<Collection<String>> any());
    }

    // Non-empty collection resolves prop names and delegates to the per-prop batch overload (L532-540).
    @Test
    public void testLoadJoinEntities_EntitiesByClass_WithSelectProps_RunsLoop() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        List<String> selectProps = List.of("id");
        Mockito.doReturn(TestEntity.class).when(dao).targetEntityClass();
        Mockito.doReturn(TestUncheckedJoinDao.class).when(dao).targetDaoInterface();
        Mockito.doReturn("test_entity").when(dao).targetTableName();
        doNothing().when(dao).loadJoinEntities(Mockito.same(entities), Mockito.anyString(), Mockito.same(selectProps));

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            daoUtil.when(() -> DaoUtil.getJoinEntityPropNamesByType(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.eq(String.class)))
                    .thenReturn(List.of("orders"));

            dao.loadJoinEntities(entities, String.class, selectProps);

            verify(dao).loadJoinEntities(entities, "orders", selectProps);
        }
    }

    // ---- deleteJoinEntities(Collection<T>, Class<?>) — empty input must short-circuit BEFORE prop-name resolution ----

    // Regression: the unchecked twin used to resolve join-prop names (and throw IllegalArgumentException when none of
    // the requested type existed) BEFORE checking for an empty collection, diverging from the checked JoinEntityHelper
    // twin which returns 0 for empty input regardless of join configuration. An empty collection must be a pure no-op.
    @Test
    public void testDeleteJoinEntities_EntitiesByClass_EmptyShortCircuitsBeforePropLookup() {
        TestUncheckedJoinDao dao = Mockito.mock(TestUncheckedJoinDao.class, Mockito.CALLS_REAL_METHODS);

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            // Even when no join property of the requested type exists, an empty collection returns 0 without throwing.
            daoUtil.when(() -> DaoUtil.getJoinEntityPropNamesByType(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(new ArrayList<>());

            int result = dao.deleteJoinEntities(new ArrayList<TestEntity>(), String.class);

            assertEquals(0, result);
            // The short-circuit must happen before any prop-name resolution.
            daoUtil.verify(() -> DaoUtil.getJoinEntityPropNamesByType(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()), Mockito.never());
        }
    }
}
