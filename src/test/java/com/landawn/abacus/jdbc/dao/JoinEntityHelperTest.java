package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SqlTransaction;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.u.Optional;

public class JoinEntityHelperTest extends TestBase {

    interface TestJoinDao extends Dao<TestEntity, TestJoinDao>, JoinEntityHelper<TestEntity, TestJoinDao> {
    }

    static final class TestEntity {
        private int id;
        private Object orders;
        private Object addresses;

        public int getId() {
            return id;
        }

        public void setId(final int id) {
            this.id = id;
        }

        public Object getOrders() {
            return orders;
        }

        public void setOrders(final Object orders) {
            this.orders = orders;
        }

        public Object getAddresses() {
            return addresses;
        }

        public void setAddresses(final Object addresses) {
            this.addresses = addresses;
        }
    }

    @Test
    public void testFindFirst_LoadsRequestedJoinEntity() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(null, condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadJoinEntities(entity, String.class);

        Optional<TestEntity> result = dao.findFirst(null, String.class, condition);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).findFirst(null, condition);
        verify(dao).loadJoinEntities(entity, String.class);
    }

    @Test
    public void testFindFirst_LoadsAllJoinEntitiesWhenRequested() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
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
    public void testFindOnlyOne_LoadsEachRequestedJoinEntity() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
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

    // findFirst(selectPropNames, Collection<Class<?>>, cond) - loops over each class
    @Test
    public void testFindFirst_LoadsEachRequestedJoinEntity_CollectionOfClasses() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
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

    // findOnlyOne(selectPropNames, Class<?>, cond) - single class overload
    @Test
    public void testFindOnlyOne_LoadsSingleJoinEntityClass() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(null, condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadJoinEntities(eq(entity), eq(String.class));

        Optional<TestEntity> result = dao.findOnlyOne(null, String.class, condition);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao).loadJoinEntities(entity, String.class);
    }

    // findOnlyOne(selectPropNames, boolean includeAll, cond) - boolean overload
    @Test
    public void testFindOnlyOne_LoadsAllJoinEntitiesWhenRequested() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(List.of("id"), condition)).thenReturn(Optional.of(entity));
        doNothing().when(dao).loadAllJoinEntities(entity);

        Optional<TestEntity> result = dao.findOnlyOne(List.of("id"), true, condition);

        assertTrue(result.isPresent());
        verify(dao).loadAllJoinEntities(entity);
    }

    // list(selectPropNames, Class<?>, cond)
    @Test
    public void testList_LoadsSingleJoinEntityClass() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
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

    // list(selectPropNames, Collection<Class<?>>, cond)
    @Test
    public void testList_LoadsCollectionOfJoinEntityClasses() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
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

    // list(selectPropNames, boolean, cond)
    @Test
    public void testList_LoadsAllJoinEntitiesWhenRequested() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);
        List<TestEntity> entities = List.of(entity);

        when(dao.list((Collection<String>) null, condition)).thenReturn(entities);
        doNothing().when(dao).loadAllJoinEntities(entities);

        List<TestEntity> result = dao.list(null, true, condition);

        assertNotNull(result);
        verify(dao).loadAllJoinEntities(entities);
    }

    // loadJoinEntities(entity, Class<?>) delegates to loadJoinEntities(entity, class, null)
    @Test
    public void testLoadJoinEntities_SingleEntity_ClassOnly_DelegatesTo3Param() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        doNothing().when(dao).loadJoinEntities(eq(entity), eq(String.class), isNull());

        dao.loadJoinEntities(entity, String.class);

        verify(dao).loadJoinEntities(entity, String.class, null);
    }

    // loadJoinEntities(entities, Class<?>) delegates to loadJoinEntities(entities, class, null)
    @Test
    public void testLoadJoinEntities_CollectionEntity_ClassOnly_DelegatesTo3Param() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        doNothing().when(dao).loadJoinEntities(eq(entities), eq(String.class), isNull());

        dao.loadJoinEntities(entities, String.class);

        verify(dao).loadJoinEntities(entities, String.class, null);
    }

    // loadJoinEntities(entity, String) delegates to loadJoinEntities(entity, String, null)
    @Test
    public void testLoadJoinEntities_SingleEntity_PropNameOnly_DelegatesTo3Param() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        doNothing().when(dao).loadJoinEntities(eq(entity), eq("orders"), isNull());

        dao.loadJoinEntities(entity, "orders");

        verify(dao).loadJoinEntities(entity, "orders", null);
    }

    // loadJoinEntitiesIfAbsent(entity, Class<?>) delegates to loadJoinEntitiesIfAbsent(entity, class, null)
    @Test
    public void testloadJoinEntitiesIfAbsent_SingleEntity_ClassOnly_DelegatesTo3Param() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        doNothing().when(dao).loadJoinEntitiesIfAbsent(eq(entity), eq(String.class), isNull());

        dao.loadJoinEntitiesIfAbsent(entity, String.class);

        verify(dao).loadJoinEntitiesIfAbsent(entity, String.class, null);
    }

    // loadJoinEntitiesIfAbsent(entities, Class<?>) delegates to loadJoinEntitiesIfAbsent(entities, class, null)
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_ClassOnly_DelegatesTo3Param() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        doNothing().when(dao).loadJoinEntitiesIfAbsent(eq(entities), eq(String.class), isNull());

        dao.loadJoinEntitiesIfAbsent(entities, String.class);

        verify(dao).loadJoinEntitiesIfAbsent(entities, String.class, null);
    }

    // loadJoinEntities(entity, Collection<String> joinEntityPropNames) — loops over each name (lines 745-753).
    @Test
    public void testLoadJoinEntities_Entity_PropNames_LoopsEach() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntities(eq(entity), ArgumentMatchers.anyString(), isNull());
        Mockito.doCallRealMethod().when(dao).loadJoinEntities(eq(entity), ArgumentMatchers.anyString());

        dao.loadJoinEntities(entity, List.of("orders", "addresses"));

        verify(dao).loadJoinEntities(entity, "orders");
        verify(dao).loadJoinEntities(entity, "addresses");
    }

    @Test
    public void testLoadJoinEntities_Entity_PropNames_EmptyShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        // Empty propNames should not invoke per-name overload at all.
        dao.loadJoinEntities(entity, List.<String> of());
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.eq(entity), ArgumentMatchers.anyString());
    }

    // loadJoinEntities(entity, propNames, inParallel=false) — falls through to non-parallel path.
    @Test
    public void testLoadJoinEntities_Entity_PropNames_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.same(entity), ArgumentMatchers.anyCollection());

        dao.loadJoinEntities(entity, List.of("orders"), false);

        verify(dao).loadJoinEntities(entity, List.of("orders"));
    }

    // loadJoinEntities(entity, propNames, executor) — uses executor and completes futures.
    @Test
    public void testLoadJoinEntities_Entity_PropNames_WithExecutor_RunsLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntities(eq(entity), ArgumentMatchers.anyString());

        dao.loadJoinEntities(entity, List.of("orders", "addresses"), Runnable::run);

        verify(dao).loadJoinEntities(entity, "orders");
        verify(dao).loadJoinEntities(entity, "addresses");
    }

    @Test
    public void testLoadJoinEntities_Entity_PropNames_WithExecutor_EmptyShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        dao.loadJoinEntities(entity, List.<String> of(), Runnable::run);
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.same(entity), ArgumentMatchers.anyString());
    }

    @Test
    public void testLoadJoinEntities_Entity_PropNames_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadJoinEntities(eq(entity), ArgumentMatchers.anyString());

        dao.loadJoinEntities(entity, List.of("orders"), true);

        verify(dao).executor();
    }

    // loadJoinEntities(entities, Collection<String> joinEntityPropNames) — loops over each name.
    @Test
    public void testLoadJoinEntities_Entities_PropNames_LoopsEach() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.same(entities), ArgumentMatchers.anyString());

        dao.loadJoinEntities(entities, List.of("orders", "addresses"));

        verify(dao).loadJoinEntities(entities, "orders");
        verify(dao).loadJoinEntities(entities, "addresses");
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_EmptyEntitiesShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntities(List.<TestEntity> of(), List.of("orders"));
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<List<TestEntity>> any(), ArgumentMatchers.anyString());
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.same(entities), ArgumentMatchers.anyCollection());

        dao.loadJoinEntities(entities, List.of("orders"), false);

        verify(dao).loadJoinEntities(entities, List.of("orders"));
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_WithExecutor_RunsLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.same(entities), ArgumentMatchers.anyString());

        dao.loadJoinEntities(entities, List.of("orders", "addresses"), Runnable::run);

        verify(dao).loadJoinEntities(entities, "orders");
        verify(dao).loadJoinEntities(entities, "addresses");
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_WithExecutor_EmptyShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntities(List.<TestEntity> of(), List.of("orders"), Runnable::run);
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<List<TestEntity>> any(), ArgumentMatchers.anyString());
    }

    @Test
    public void testLoadJoinEntities_Entities_PropNames_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.same(entities), ArgumentMatchers.anyString());

        dao.loadJoinEntities(entities, List.of("orders"), true);

        verify(dao).executor();
    }

    // loadAllJoinEntities(entity, boolean) — both true/false branches.
    @Test
    public void testLoadAllJoinEntities_Entity_InParallelFalse_DelegatesToSerial() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadAllJoinEntities(entity);

        dao.loadAllJoinEntities(entity, false);

        verify(dao).loadAllJoinEntities(entity);
    }

    @Test
    public void testLoadAllJoinEntities_Entity_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadAllJoinEntities(ArgumentMatchers.same(entity), ArgumentMatchers.<java.util.concurrent.Executor> any());

        dao.loadAllJoinEntities(entity, true);

        verify(dao).executor();
    }

    @Test
    public void testLoadAllJoinEntities_Entities_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadAllJoinEntities(entities);

        dao.loadAllJoinEntities(entities, false);

        verify(dao).loadAllJoinEntities(entities);
    }

    @Test
    public void testLoadAllJoinEntities_Entities_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadAllJoinEntities(ArgumentMatchers.same(entities), ArgumentMatchers.<java.util.concurrent.Executor> any());

        dao.loadAllJoinEntities(entities, true);

        verify(dao).executor();
    }

    // loadJoinEntitiesIfAbsent variants.
    @Test
    public void testloadJoinEntitiesIfAbsent_Entity_PropNames_LoopsEach() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntitiesIfAbsent(eq(entity), ArgumentMatchers.anyString());

        dao.loadJoinEntitiesIfAbsent(entity, List.of("orders", "addresses"));

        verify(dao).loadJoinEntitiesIfAbsent(entity, "orders");
        verify(dao).loadJoinEntitiesIfAbsent(entity, "addresses");
    }

    @Test
    public void testloadJoinEntitiesIfAbsent_Entity_PropNames_WithExecutor_RechecksBeforeLoading() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        java.util.concurrent.Executor executor = command -> {
            entity.setOrders("already-loaded");
            command.run();
        };

        Mockito.doAnswer(invocation -> {
            entity.setOrders("loaded-by-dao");
            return null;
        }).when(dao).loadJoinEntities(entity, "orders");

        dao.loadJoinEntitiesIfAbsent(entity, List.of("orders"), executor);

        assertEquals("already-loaded", entity.getOrders());
        verify(dao, Mockito.never()).loadJoinEntities(entity, "orders");
    }

    @Test
    public void testloadJoinEntitiesIfAbsent_Entities_PropNames_LoopsEach() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntitiesIfAbsent(ArgumentMatchers.same(entities), ArgumentMatchers.anyString());

        dao.loadJoinEntitiesIfAbsent(entities, List.of("orders", "addresses"));

        verify(dao).loadJoinEntitiesIfAbsent(entities, "orders");
        verify(dao).loadJoinEntitiesIfAbsent(entities, "addresses");
    }

    // stream variants load joins while flattening split batches.
    @Test
    public void testStream_LoadsSingleJoinEntityClass() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity first = new TestEntity();
        TestEntity second = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);
        List<String> selectPropNames = List.of("id");

        when(dao.stream(selectPropNames, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(first, second));
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(String.class));

        List<TestEntity> result = dao.stream(selectPropNames, String.class, condition).toList();

        assertEquals(2, result.size());
        verify(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(String.class));
    }

    @Test
    public void testStream_LoadsCollectionOfJoinEntityClasses() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(String.class));
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(Integer.class));

        List<TestEntity> result = dao.stream(null, List.of(String.class, Integer.class), condition).toList();

        assertEquals(1, result.size());
        verify(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(String.class));
        verify(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(Integer.class));
    }

    @Test
    public void testStream_EmptyJoinEntityClasses_ReturnsBaseStream() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity),
                com.landawn.abacus.util.stream.Stream.of(entity));

        assertEquals(List.of(entity), dao.stream(null, (Collection<Class<?>>) null, condition).toList());
        assertEquals(List.of(entity), dao.stream(null, List.of(), condition).toList());
        verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), ArgumentMatchers.any(Class.class));
    }

    @Test
    public void testStream_LoadsAllJoinEntitiesWhenRequested() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));
        doNothing().when(dao).loadAllJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any());

        List<TestEntity> result = dao.stream(null, true, condition).toList();

        assertEquals(1, result.size());
        verify(dao).loadAllJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any());
    }

    @Test
    public void testStream_IncludeAllJoinEntitiesFalse() {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));

        List<TestEntity> result = dao.stream(null, false, condition).toList();

        assertEquals(1, result.size());
    }

    // delegation: loadJoinEntities(entities, String) -> loadJoinEntities(entities, String, null)
    @Test
    public void testLoadJoinEntities_CollectionEntity_PropNameOnly_DelegatesTo3Param() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        doNothing().when(dao).loadJoinEntities(eq(entities), eq("items"), isNull());

        dao.loadJoinEntities(entities, "items");

        verify(dao).loadJoinEntities(entities, "items", null);
    }

    // delegation: loadJoinEntitiesIfAbsent(entity, String) -> loadJoinEntitiesIfAbsent(entity, String, null)
    @Test
    public void testloadJoinEntitiesIfAbsent_SingleEntity_PropNameOnly_DelegatesTo3Param() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        doNothing().when(dao).loadJoinEntitiesIfAbsent(eq(entity), eq("orders"), isNull());

        dao.loadJoinEntitiesIfAbsent(entity, "orders");

        verify(dao).loadJoinEntitiesIfAbsent(entity, "orders", null);
    }

    // delegation: loadJoinEntitiesIfAbsent(entities, String) -> loadJoinEntitiesIfAbsent(entities, String, null)
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_PropNameOnly_DelegatesTo3Param() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        doNothing().when(dao).loadJoinEntitiesIfAbsent(eq(entities), eq("orders"), isNull());

        dao.loadJoinEntitiesIfAbsent(entities, "orders");

        verify(dao).loadJoinEntitiesIfAbsent(entities, "orders", null);
    }

    // edge: findFirst(selectPropNames, Class<?>, cond) - result not present
    @Test
    public void testFindFirst_Class_ResultNotPresent_ReturnsEmpty() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(null, condition)).thenReturn(Optional.empty());

        Optional<TestEntity> result = dao.findFirst(null, String.class, condition);

        assertEquals(false, result.isPresent());
        verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<TestEntity> any(), ArgumentMatchers.<Class<?>> any());
    }

    // edge: findFirst(selectPropNames, Collection<Class<?>>, cond) - result not present
    @Test
    public void testFindFirst_CollectionOfClasses_ResultNotPresent_ReturnsEmpty() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(null, condition)).thenReturn(Optional.empty());

        Optional<TestEntity> result = dao.findFirst(null, List.of(String.class, Integer.class), condition);

        assertEquals(false, result.isPresent());
        verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<TestEntity> any(), ArgumentMatchers.<Class<?>> any());
    }

    // edge: findFirst(selectPropNames, Collection<Class<?>>, cond) - joinEntitiesToLoad empty
    @Test
    public void testFindFirst_CollectionOfClasses_JoinEntitiesEmpty_NoLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(null, condition)).thenReturn(Optional.of(entity));

        Optional<TestEntity> result = dao.findFirst(null, List.<Class<?>> of(), condition);

        assertTrue(result.isPresent());
        assertSame(entity, result.orElseNull());
        verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<TestEntity> any(), ArgumentMatchers.<Class<?>> any());
    }

    // edge: findFirst(selectPropNames, boolean, cond) - includeAllJoinEntities=false
    @Test
    public void testFindFirst_IncludeAllFalse_NoLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(null, condition)).thenReturn(Optional.of(entity));

        Optional<TestEntity> result = dao.findFirst(null, false, condition);

        assertTrue(result.isPresent());
        verify(dao, Mockito.never()).loadAllJoinEntities(ArgumentMatchers.<TestEntity> any());
    }

    // edge: findFirst(selectPropNames, boolean, cond) - result not present when includeAll=true
    @Test
    public void testFindFirst_IncludeAll_ResultNotPresent_ReturnsEmpty() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findFirst(null, condition)).thenReturn(Optional.empty());

        Optional<TestEntity> result = dao.findFirst(null, true, condition);

        assertEquals(false, result.isPresent());
        verify(dao, Mockito.never()).loadAllJoinEntities(ArgumentMatchers.<TestEntity> any());
    }

    // edge: findOnlyOne(selectPropNames, Class<?>, cond) - result not present
    @Test
    public void testFindOnlyOne_Class_ResultNotPresent_ReturnsEmpty() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(null, condition)).thenReturn(Optional.empty());

        Optional<TestEntity> result = dao.findOnlyOne(null, String.class, condition);

        assertEquals(false, result.isPresent());
        verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<TestEntity> any(), ArgumentMatchers.<Class<?>> any());
    }

    // edge: findOnlyOne(selectPropNames, Collection<Class<?>>, cond) - result not present
    @Test
    public void testFindOnlyOne_CollectionOfClasses_ResultNotPresent_ReturnsEmpty() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(null, condition)).thenReturn(Optional.empty());

        Optional<TestEntity> result = dao.findOnlyOne(null, List.of(String.class, Integer.class), condition);

        assertEquals(false, result.isPresent());
        verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<TestEntity> any(), ArgumentMatchers.<Class<?>> any());
    }

    // edge: findOnlyOne(selectPropNames, Collection<Class<?>>, cond) - joinEntitiesToLoad empty
    @Test
    public void testFindOnlyOne_CollectionOfClasses_JoinEntitiesEmpty_NoLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(null, condition)).thenReturn(Optional.of(entity));

        Optional<TestEntity> result = dao.findOnlyOne(null, List.<Class<?>> of(), condition);

        assertTrue(result.isPresent());
        verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<TestEntity> any(), ArgumentMatchers.<Class<?>> any());
    }

    // edge: findOnlyOne(selectPropNames, boolean, cond) - includeAll=false
    @Test
    public void testFindOnlyOne_IncludeAllFalse_NoLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(null, condition)).thenReturn(Optional.of(entity));

        Optional<TestEntity> result = dao.findOnlyOne(null, false, condition);

        assertTrue(result.isPresent());
        verify(dao, Mockito.never()).loadAllJoinEntities(ArgumentMatchers.<TestEntity> any());
    }

    // edge: findOnlyOne(selectPropNames, boolean, cond) - result not present when includeAll=true
    @Test
    public void testFindOnlyOne_IncludeAll_ResultNotPresent_ReturnsEmpty() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);

        when(dao.findOnlyOne(null, condition)).thenReturn(Optional.empty());

        Optional<TestEntity> result = dao.findOnlyOne(null, true, condition);

        assertEquals(false, result.isPresent());
        verify(dao, Mockito.never()).loadAllJoinEntities(ArgumentMatchers.<TestEntity> any());
    }

    // edge: list(selectPropNames, Class<?>, cond) - result empty
    @Test
    public void testList_Class_ResultEmpty_NoLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);

        when(dao.list((Collection<String>) null, condition)).thenReturn(List.of());

        List<TestEntity> result = dao.list(null, String.class, condition);

        assertEquals(0, result.size());
        verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), ArgumentMatchers.<Class<?>> any());
    }

    // edge: list(selectPropNames, Collection<Class<?>>, cond) - result empty
    @Test
    public void testList_CollectionOfClasses_ResultEmpty_NoLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);

        when(dao.list((Collection<String>) null, condition)).thenReturn(List.of());

        List<TestEntity> result = dao.list(null, List.of(String.class, Integer.class), condition);

        assertEquals(0, result.size());
        verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), ArgumentMatchers.<Class<?>> any());
    }

    // edge: list(selectPropNames, Collection<Class<?>>, cond) - joinEntitiesToLoad empty
    @Test
    public void testList_CollectionOfClasses_JoinEntitiesEmpty_NoLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);
        List<TestEntity> entities = List.of(entity);

        when(dao.list((Collection<String>) null, condition)).thenReturn(entities);

        List<TestEntity> result = dao.list(null, List.<Class<?>> of(), condition);

        assertEquals(1, result.size());
        verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), ArgumentMatchers.<Class<?>> any());
    }

    // edge: list(selectPropNames, boolean, cond) - includeAll=false
    @Test
    public void testList_IncludeAllFalse_NoLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);
        List<TestEntity> entities = List.of(entity);

        when(dao.list((Collection<String>) null, condition)).thenReturn(entities);

        List<TestEntity> result = dao.list(null, false, condition);

        assertEquals(1, result.size());
        verify(dao, Mockito.never()).loadAllJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any());
    }

    // edge: list(selectPropNames, boolean, cond) - result empty when includeAll=true
    @Test
    public void testList_IncludeAll_ResultEmpty_NoLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);

        when(dao.list((Collection<String>) null, condition)).thenReturn(List.of());

        List<TestEntity> result = dao.list(null, true, condition);

        assertEquals(0, result.size());
        verify(dao, Mockito.never()).loadAllJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any());
    }

    // edge: loadJoinEntities(entities, Collection<String>) - empty entities
    @Test
    public void testLoadJoinEntities_Entities_PropNames_EmptyPropNames_ShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        dao.loadJoinEntities(entities, List.<String> of());
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<List<TestEntity>> any(), ArgumentMatchers.anyString());
    }

    // edge: loadJoinEntities(entities, Collection<String>) - both empty
    @Test
    public void testLoadJoinEntities_Entities_PropNames_BothEmpty_ShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntities(List.<TestEntity> of(), List.<String> of());
        Mockito.verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<List<TestEntity>> any(), ArgumentMatchers.anyString());
    }

    // edge: loadJoinEntitiesIfAbsent(entity, Collection<String>) - empty propNames
    @Test
    public void testloadJoinEntitiesIfAbsent_Entity_PropNames_EmptyPropNames_ShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        dao.loadJoinEntitiesIfAbsent(entity, List.<String> of());
        Mockito.verify(dao, Mockito.never()).loadJoinEntitiesIfAbsent(ArgumentMatchers.eq(entity), ArgumentMatchers.anyString());
    }

    // edge: loadJoinEntitiesIfAbsent(entities, Collection<String>) - empty propNames only
    @Test
    public void testloadJoinEntitiesIfAbsent_Entities_PropNames_EmptyPropNames_ShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        dao.loadJoinEntitiesIfAbsent(entities, List.<String> of());
        Mockito.verify(dao, Mockito.never()).loadJoinEntitiesIfAbsent(ArgumentMatchers.<List<TestEntity>> any(), ArgumentMatchers.anyString());
    }

    // edge: loadJoinEntitiesIfAbsent(entities, Collection<String>) - both empty
    @Test
    public void testloadJoinEntitiesIfAbsent_Entities_PropNames_BothEmpty_ShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntitiesIfAbsent(List.<TestEntity> of(), List.<String> of());
        Mockito.verify(dao, Mockito.never()).loadJoinEntitiesIfAbsent(ArgumentMatchers.<List<TestEntity>> any(), ArgumentMatchers.anyString());
    }

    // loadAllJoinEntities(entity) - calls loadJoinEntities with all join info keys
    @Test
    public void testLoadAllJoinEntities_SingleEntity() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        dao.loadAllJoinEntities(entity);
        // verify delegation happened (keySet will be empty for TestEntity with no @JoinedBy)
        verify(dao).targetDaoInterface();
    }

    // loadAllJoinEntities(entities) - non-empty entities
    @Test
    public void testLoadAllJoinEntities_CollectionEntity() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        dao.loadAllJoinEntities(entities);
        verify(dao).targetDaoInterface();
    }

    // edge: loadAllJoinEntities(entities) - empty entities early return
    @Test
    public void testLoadAllJoinEntities_CollectionEntity_EmptyEntities_ShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadAllJoinEntities(List.<TestEntity> of());
        // should NOT call targetDaoInterface() or loadJoinEntities
        verify(dao, Mockito.never()).targetDaoInterface();
    }

    // loadAllJoinEntities(entity, Executor)
    @Test
    public void testLoadAllJoinEntities_SingleEntity_WithExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        dao.loadAllJoinEntities(entity, Runnable::run);
        verify(dao).targetDaoInterface();
    }

    // loadAllJoinEntities(entities, Executor) - non-empty
    @Test
    public void testLoadAllJoinEntities_CollectionEntity_WithExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        dao.loadAllJoinEntities(entities, Runnable::run);
        verify(dao).targetDaoInterface();
    }

    // edge: loadAllJoinEntities(entities, Executor) - empty entities
    @Test
    public void testLoadAllJoinEntities_CollectionEntity_WithExecutor_EmptyEntities_ShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadAllJoinEntities(List.<TestEntity> of(), Runnable::run);
        verify(dao, Mockito.never()).targetDaoInterface();
    }

    // loadJoinEntitiesIfAbsent(entity, Collection<String>, boolean) - false branch
    @Test
    public void testloadJoinEntitiesIfAbsent_Entity_PropNames_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntitiesIfAbsent(ArgumentMatchers.same(entity), ArgumentMatchers.anyCollection());

        dao.loadJoinEntitiesIfAbsent(entity, List.of("orders"), false);

        verify(dao).loadJoinEntitiesIfAbsent(entity, List.of("orders"));
    }

    // loadJoinEntitiesIfAbsent(entity, Collection<String>, boolean) - true branch
    @Test
    public void testloadJoinEntitiesIfAbsent_Entity_PropNames_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao)
                .loadJoinEntitiesIfAbsent(ArgumentMatchers.eq(entity), ArgumentMatchers.anyCollection(),
                        ArgumentMatchers.<java.util.concurrent.Executor> any());

        dao.loadJoinEntitiesIfAbsent(entity, List.of("orders"), true);

        verify(dao).executor();
    }

    // loadJoinEntitiesIfAbsent(entity, Collection<String>, Executor) - non-empty
    @Test
    public void testloadJoinEntitiesIfAbsent_Entity_PropNames_WithExecutor_RunsLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntities(eq(entity), ArgumentMatchers.anyString(), isNull());

        dao.loadJoinEntitiesIfAbsent(entity, List.of("orders", "addresses"), Runnable::run);

        verify(dao).loadJoinEntities(entity, "orders", null);
        verify(dao).loadJoinEntities(entity, "addresses", null);
    }

    // edge: loadJoinEntitiesIfAbsent(entity, Collection<String>, Executor) - empty propNames
    @Test
    public void testloadJoinEntitiesIfAbsent_Entity_PropNames_WithExecutor_EmptyShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        dao.loadJoinEntitiesIfAbsent(entity, List.<String> of(), Runnable::run);
        Mockito.verify(dao, Mockito.never()).loadJoinEntitiesIfAbsent(ArgumentMatchers.eq(entity), ArgumentMatchers.anyString());
    }

    // loadJoinEntitiesIfAbsent(entities, Collection<String>, boolean) - false branch
    @Test
    public void testloadJoinEntitiesIfAbsent_Entities_PropNames_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntitiesIfAbsent(ArgumentMatchers.same(entities), ArgumentMatchers.anyCollection());

        dao.loadJoinEntitiesIfAbsent(entities, List.of("orders"), false);

        verify(dao).loadJoinEntitiesIfAbsent(entities, List.of("orders"));
    }

    // loadJoinEntitiesIfAbsent(entities, Collection<String>, boolean) - true branch
    @Test
    public void testloadJoinEntitiesIfAbsent_Entities_PropNames_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao)
                .loadJoinEntitiesIfAbsent(ArgumentMatchers.eq(entities), ArgumentMatchers.anyCollection(),
                        ArgumentMatchers.<java.util.concurrent.Executor> any());

        dao.loadJoinEntitiesIfAbsent(entities, List.of("orders"), true);

        verify(dao).executor();
    }

    // loadJoinEntitiesIfAbsent(entities, Collection<String>, Executor) - non-empty
    @Test
    public void testloadJoinEntitiesIfAbsent_Entities_PropNames_WithExecutor_RunsLoad() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntitiesIfAbsent(ArgumentMatchers.same(entities), ArgumentMatchers.anyString());

        dao.loadJoinEntitiesIfAbsent(entities, List.of("orders", "addresses"), Runnable::run);

        verify(dao).loadJoinEntitiesIfAbsent(entities, "orders");
        verify(dao).loadJoinEntitiesIfAbsent(entities, "addresses");
    }

    // edge: loadJoinEntitiesIfAbsent(entities, Collection<String>, Executor) - both empty
    @Test
    public void testloadJoinEntitiesIfAbsent_Entities_PropNames_WithExecutor_BothEmpty_ShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntitiesIfAbsent(List.<TestEntity> of(), List.<String> of(), Runnable::run);
        Mockito.verify(dao, Mockito.never()).loadJoinEntitiesIfAbsent(ArgumentMatchers.<List<TestEntity>> any(), ArgumentMatchers.anyString());
    }

    // loadJoinEntitiesIfAbsent(entity) - loads all join entities if null
    @Test
    public void testloadJoinEntitiesIfAbsent_SingleEntity_All() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        dao.loadJoinEntitiesIfAbsent(entity);
        verify(dao).targetDaoInterface();
    }

    // loadJoinEntitiesIfAbsent(entity, boolean) - false branch
    @Test
    public void testloadJoinEntitiesIfAbsent_SingleEntity_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntitiesIfAbsent(entity);

        dao.loadJoinEntitiesIfAbsent(entity, false);

        verify(dao).loadJoinEntitiesIfAbsent(entity);
    }

    // loadJoinEntitiesIfAbsent(entity, boolean) - true branch
    @Test
    public void testloadJoinEntitiesIfAbsent_SingleEntity_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadJoinEntitiesIfAbsent(ArgumentMatchers.eq(entity), ArgumentMatchers.<java.util.concurrent.Executor> any());

        dao.loadJoinEntitiesIfAbsent(entity, true);

        verify(dao).executor();
    }

    // loadJoinEntitiesIfAbsent(entity, Executor)
    @Test
    public void testloadJoinEntitiesIfAbsent_SingleEntity_WithExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        dao.loadJoinEntitiesIfAbsent(entity, Runnable::run);
        verify(dao).targetDaoInterface();
    }

    // loadJoinEntitiesIfAbsent(entities) - non-empty
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_All() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        dao.loadJoinEntitiesIfAbsent(entities);
        verify(dao).targetDaoInterface();
    }

    // edge: loadJoinEntitiesIfAbsent(entities) - empty entities
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_All_EmptyEntities_ShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntitiesIfAbsent(List.<TestEntity> of());
        verify(dao, Mockito.never()).targetDaoInterface();
    }

    // loadJoinEntitiesIfAbsent(entities, boolean) - false branch
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doNothing().when(dao).loadJoinEntitiesIfAbsent(entities);

        dao.loadJoinEntitiesIfAbsent(entities, false);

        verify(dao).loadJoinEntitiesIfAbsent(entities);
    }

    // loadJoinEntitiesIfAbsent(entities, boolean) - true branch
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.executor()).thenReturn(Runnable::run);
        doNothing().when(dao).loadJoinEntitiesIfAbsent(ArgumentMatchers.eq(entities), ArgumentMatchers.<java.util.concurrent.Executor> any());

        dao.loadJoinEntitiesIfAbsent(entities, true);

        verify(dao).executor();
    }

    // loadJoinEntitiesIfAbsent(entities, Executor) - non-empty
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_WithExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        dao.loadJoinEntitiesIfAbsent(entities, Runnable::run);
        verify(dao).targetDaoInterface();
    }

    // edge: loadJoinEntitiesIfAbsent(entities, Executor) - empty entities
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_WithExecutor_EmptyEntities_ShortCircuits() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntitiesIfAbsent(List.<TestEntity> of(), Runnable::run);
        verify(dao, Mockito.never()).targetDaoInterface();
    }

    // deleteJoinEntities(entity, Collection<String>, boolean) - false branch
    @Test
    public void testDeleteJoinEntities_Entity_PropNames_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doReturn(5).when(dao).deleteJoinEntities(ArgumentMatchers.same(entity), ArgumentMatchers.anyCollection());

        int result = dao.deleteJoinEntities(entity, List.of("orders"), false);

        assertEquals(5, result);
        verify(dao).deleteJoinEntities(entity, List.of("orders"));
    }

    // deleteJoinEntities(entity, Collection<String>, boolean) - true branch
    @Test
    public void testDeleteJoinEntities_Entity_PropNames_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.executor()).thenReturn(Runnable::run);
        doReturn(3).when(dao)
                .deleteJoinEntities(ArgumentMatchers.eq(entity), ArgumentMatchers.anyCollection(), ArgumentMatchers.<java.util.concurrent.Executor> any());

        int result = dao.deleteJoinEntities(entity, List.of("orders"), true);

        assertEquals(3, result);
        verify(dao).executor();
    }

    // deleteJoinEntities(entity, Collection<String>, Executor)
    @Test
    public void testDeleteJoinEntities_Entity_PropNames_WithExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doReturn(2).when(dao).deleteJoinEntities(ArgumentMatchers.eq(entity), ArgumentMatchers.anyString());

        int result = dao.deleteJoinEntities(entity, List.of("orders"), Runnable::run);

        assertEquals(2, result);
        verify(dao).deleteJoinEntities(entity, "orders");
    }

    // edge: deleteJoinEntities(entity, Collection<String>, Executor) - empty propNames
    @Test
    public void testDeleteJoinEntities_Entity_PropNames_WithExecutor_EmptyPropNames_ReturnsZero() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        int result = dao.deleteJoinEntities(entity, List.<String> of(), Runnable::run);

        assertEquals(0, result);
        verify(dao, Mockito.never()).deleteJoinEntities(ArgumentMatchers.eq(entity), ArgumentMatchers.anyString());
    }

    // deleteJoinEntities(entities, Collection<String>, boolean) - false branch
    @Test
    public void testDeleteJoinEntities_Entities_PropNames_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doReturn(5).when(dao).deleteJoinEntities(ArgumentMatchers.same(entities), ArgumentMatchers.anyCollection());

        int result = dao.deleteJoinEntities(entities, List.of("orders"), false);

        assertEquals(5, result);
        verify(dao).deleteJoinEntities(entities, List.of("orders"));
    }

    // deleteJoinEntities(entities, Collection<String>, boolean) - true branch
    @Test
    public void testDeleteJoinEntities_Entities_PropNames_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.executor()).thenReturn(Runnable::run);
        doReturn(3).when(dao)
                .deleteJoinEntities(ArgumentMatchers.eq(entities), ArgumentMatchers.anyCollection(), ArgumentMatchers.<java.util.concurrent.Executor> any());

        int result = dao.deleteJoinEntities(entities, List.of("orders"), true);

        assertEquals(3, result);
        verify(dao).executor();
    }

    // deleteJoinEntities(entities, Collection<String>, Executor)
    @Test
    public void testDeleteJoinEntities_Entities_PropNames_WithExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doReturn(3).when(dao).deleteJoinEntities(ArgumentMatchers.eq(entities), ArgumentMatchers.anyString());

        int result = dao.deleteJoinEntities(entities, List.of("orders"), Runnable::run);

        assertEquals(3, result);
        verify(dao).deleteJoinEntities(entities, "orders");
    }

    // edge: deleteJoinEntities(entities, Collection<String>, Executor) - both empty
    @Test
    public void testDeleteJoinEntities_Entities_PropNames_WithExecutor_BothEmpty_ReturnsZero() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        int result = dao.deleteJoinEntities(List.<TestEntity> of(), List.<String> of(), Runnable::run);

        assertEquals(0, result);
        verify(dao, Mockito.never()).deleteJoinEntities(ArgumentMatchers.<List<TestEntity>> any(), ArgumentMatchers.anyString());
    }

    // loadJoinEntitiesIfAbsent(entity, String, Collection<String>) - property is null, calls load
    @Test
    public void testloadJoinEntitiesIfAbsent_SingleEntity_PropName_SelectProps_PropertyNull() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doNothing().when(dao).loadJoinEntities(eq(entity), eq("orders"), isNull());

        dao.loadJoinEntitiesIfAbsent(entity, "orders", null);

        verify(dao).loadJoinEntities(entity, "orders", null);
    }

    // edge: loadJoinEntitiesIfAbsent(entity, String, Collection<String>) - property is not null, skips load
    @Test
    public void testloadJoinEntitiesIfAbsent_SingleEntity_PropName_SelectProps_PropertyNotNull_Skips() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        entity.setOrders(new Object()); // non-null

        dao.loadJoinEntitiesIfAbsent(entity, "orders", null);

        Mockito.verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<TestEntity> any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    // loadJoinEntitiesIfAbsent(entities, String, Collection<String>) - all properties null, loads all
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_PropName_SelectProps_PropertyNull() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity(), new TestEntity());
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq("orders"), isNull());

        dao.loadJoinEntitiesIfAbsent(entities, "orders", null);

        verify(dao).loadJoinEntities(entities, "orders", null);
    }

    // loadJoinEntitiesIfAbsent(entities, String, Collection<String>) - no entity has null, skips
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_PropName_SelectProps_PropertyNotNull_Skips() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity e1 = new TestEntity();
        e1.setOrders(new Object());
        List<TestEntity> entities = List.of(e1);

        dao.loadJoinEntitiesIfAbsent(entities, "orders", null);

        Mockito.verify(dao, Mockito.never())
                .loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    // edge: loadJoinEntitiesIfAbsent(entities, String, Collection<String>) - empty entities
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_PropName_SelectProps_EmptyEntities() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntitiesIfAbsent(List.<TestEntity> of(), "orders", null);

        Mockito.verify(dao, Mockito.never())
                .loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    // deleteAllJoinEntities(entity)
    @Test
    public void testDeleteAllJoinEntities_SingleEntity() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");
        doReturn(0).when(dao).deleteJoinEntities(ArgumentMatchers.eq(entity), ArgumentMatchers.anyCollection());

        int result = dao.deleteAllJoinEntities(entity);

        assertEquals(0, result);
        verify(dao).targetDaoInterface();
    }

    // deleteAllJoinEntities(entity, boolean) - false branch
    @Test
    public void testDeleteAllJoinEntities_SingleEntity_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doReturn(5).when(dao).deleteAllJoinEntities(entity);

        int result = dao.deleteAllJoinEntities(entity, false);

        assertEquals(5, result);
        verify(dao).deleteAllJoinEntities(entity);
    }

    // deleteAllJoinEntities(entity, boolean) - true branch
    @Test
    public void testDeleteAllJoinEntities_SingleEntity_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.executor()).thenReturn(Runnable::run);
        doReturn(3).when(dao).deleteAllJoinEntities(ArgumentMatchers.eq(entity), ArgumentMatchers.<java.util.concurrent.Executor> any());

        int result = dao.deleteAllJoinEntities(entity, true);

        assertEquals(3, result);
        verify(dao).executor();
    }

    // deleteAllJoinEntities(entity, Executor)
    @Test
    public void testDeleteAllJoinEntities_SingleEntity_WithExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");
        doReturn(0).when(dao)
                .deleteJoinEntities(ArgumentMatchers.eq(entity), ArgumentMatchers.anyCollection(), ArgumentMatchers.<java.util.concurrent.Executor> any());

        int result = dao.deleteAllJoinEntities(entity, Runnable::run);

        assertEquals(0, result);
        verify(dao).targetDaoInterface();
    }

    // deleteAllJoinEntities(entities) - non-empty
    @Test
    public void testDeleteAllJoinEntities_CollectionEntity() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");
        doReturn(0).when(dao).deleteJoinEntities(ArgumentMatchers.eq(entities), ArgumentMatchers.anyCollection());

        int result = dao.deleteAllJoinEntities(entities);

        assertEquals(0, result);
        verify(dao).targetDaoInterface();
    }

    // edge: deleteAllJoinEntities(entities) - empty entities
    @Test
    public void testDeleteAllJoinEntities_CollectionEntity_EmptyEntities_ReturnsZero() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        int result = dao.deleteAllJoinEntities(List.<TestEntity> of());

        assertEquals(0, result);
        verify(dao, Mockito.never()).targetDaoInterface();
    }

    // deleteAllJoinEntities(entities, boolean) - false branch
    @Test
    public void testDeleteAllJoinEntities_CollectionEntity_InParallelFalse() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doReturn(5).when(dao).deleteAllJoinEntities(entities);

        int result = dao.deleteAllJoinEntities(entities, false);

        assertEquals(5, result);
        verify(dao).deleteAllJoinEntities(entities);
    }

    // deleteAllJoinEntities(entities, boolean) - true branch
    @Test
    public void testDeleteAllJoinEntities_CollectionEntity_InParallelTrue_UsesExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.executor()).thenReturn(Runnable::run);
        doReturn(3).when(dao).deleteAllJoinEntities(ArgumentMatchers.eq(entities), ArgumentMatchers.<java.util.concurrent.Executor> any());

        int result = dao.deleteAllJoinEntities(entities, true);

        assertEquals(3, result);
        verify(dao).executor();
    }

    // deleteAllJoinEntities(entities, Executor) - non-empty
    @Test
    public void testDeleteAllJoinEntities_CollectionEntity_WithExecutor() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");
        doReturn(0).when(dao)
                .deleteJoinEntities(ArgumentMatchers.eq(entities), ArgumentMatchers.anyCollection(), ArgumentMatchers.<java.util.concurrent.Executor> any());

        int result = dao.deleteAllJoinEntities(entities, Runnable::run);

        assertEquals(0, result);
        verify(dao).targetDaoInterface();
    }

    // edge: deleteAllJoinEntities(entities, Executor) - empty entities
    @Test
    public void testDeleteAllJoinEntities_CollectionEntity_WithExecutor_EmptyEntities_ReturnsZero() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        int result = dao.deleteAllJoinEntities(List.<TestEntity> of(), Runnable::run);

        assertEquals(0, result);
        verify(dao, Mockito.never()).targetDaoInterface();
    }

    // list(selectPropNames, Class<?>, cond) - large result triggers batch path (line 328)
    @Test
    public void testList_SingleJoinEntityClass_LargeBatch() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);
        List<TestEntity> largeEntities = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            largeEntities.add(new TestEntity());
        }

        when(dao.list((Collection<String>) null, condition)).thenReturn(largeEntities);
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(String.class));

        List<TestEntity> result = dao.list(null, String.class, condition);

        assertNotNull(result);
        assertEquals(201, result.size());
    }

    // list(selectPropNames, Collection<Class<?>>, cond) - large result triggers batch path (lines 362-366)
    @Test
    public void testList_CollectionOfJoinEntityClasses_LargeBatch() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);
        List<TestEntity> largeEntities = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            largeEntities.add(new TestEntity());
        }

        when(dao.list((Collection<String>) null, condition)).thenReturn(largeEntities);
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(String.class));
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(Integer.class));

        List<TestEntity> result = dao.list(null, List.of(String.class, Integer.class), condition);

        assertNotNull(result);
        assertEquals(201, result.size());
    }

    // list(selectPropNames, boolean, cond) - large result triggers batch path (line 399)
    @Test
    public void testList_AllJoinEntities_LargeBatch() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);
        List<TestEntity> largeEntities = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            largeEntities.add(new TestEntity());
        }

        when(dao.list((Collection<String>) null, condition)).thenReturn(largeEntities);
        doNothing().when(dao).loadAllJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any());

        List<TestEntity> result = dao.list(null, true, condition);

        assertNotNull(result);
        assertEquals(201, result.size());
    }

    // stream(selectPropNames, Class<?>, cond) - SQLException wrapped as UncheckedSQLException (lines 434-435)
    @Test
    public void testStream_SingleClass_ThrowsUncheckedSQLException() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));
        Mockito.doThrow(new SQLException("db error")).when(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(String.class));

        assertThrows(UncheckedSQLException.class, () -> dao.stream(null, String.class, condition).toList());
    }

    // stream(selectPropNames, Collection<Class<?>>, cond) - SQLException wrapping (lines 471-472)
    @Test
    public void testStream_CollectionOfClasses_ThrowsUncheckedSQLException() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));
        doNothing().when(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(String.class));
        Mockito.doThrow(new SQLException("db error")).when(dao).loadJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any(), eq(Integer.class));

        assertThrows(UncheckedSQLException.class, () -> dao.stream(null, List.of(String.class, Integer.class), condition).toList());
    }

    // stream(selectPropNames, boolean, cond) - SQLException wrapping (lines 509-510)
    @Test
    public void testStream_IncludeAll_ThrowsUncheckedSQLException() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        Condition condition = Mockito.mock(Condition.class);

        when(dao.stream((Collection<String>) null, condition)).thenReturn(com.landawn.abacus.util.stream.Stream.of(entity));
        Mockito.doThrow(new SQLException("db error")).when(dao).loadAllJoinEntities(ArgumentMatchers.<Collection<TestEntity>> any());

        assertThrows(UncheckedSQLException.class, () -> dao.stream(null, true, condition).toList());
    }

    // loadJoinEntities(T, Class<?>, Collection<String>) - full path with mocked DaoUtil (lines 560, 562-569)
    @Test
    public void testLoadJoinEntities_Entity_Class_SelectProps() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            daoUtil.when(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(List.of("orders"));
            doNothing().when(dao).loadJoinEntities(eq(entity), eq("orders"), eq(null));

            dao.loadJoinEntities(entity, String.class, null);

            verify(dao).loadJoinEntities(entity, "orders", null);
        }
    }

    // loadJoinEntities(Collection<T>, Class<?>, Collection<String>) - full path (lines 611, 613, 615, 617-624)
    @Test
    public void testLoadJoinEntities_CollectionEntity_Class_SelectProps() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            daoUtil.when(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(List.of("orders"));
            doNothing().when(dao).loadJoinEntities(eq(entities), eq("orders"), eq(null));

            dao.loadJoinEntities(entities, String.class, null);

            verify(dao).loadJoinEntities(entities, "orders", null);
        }
    }

    // loadJoinEntities(Collection<T>, Class<?>, Collection<String>) - empty entities early return (line 617-618)
    @Test
    public void testLoadJoinEntities_CollectionEntity_Class_SelectProps_EmptyEntities() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntities(List.<TestEntity> of(), String.class, null);

        Mockito.verify(dao, Mockito.never()).loadJoinEntities(ArgumentMatchers.<List<TestEntity>> any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    // loadJoinEntitiesIfAbsent(T, Class<?>, Collection<String>) - full path (lines 1082, 1084, 1086, 1088-1091)
    @Test
    public void testloadJoinEntitiesIfAbsent_Entity_Class_SelectProps() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            daoUtil.when(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(List.of("orders"));
            doNothing().when(dao).loadJoinEntitiesIfAbsent(eq(entity), eq("orders"), eq(null));

            dao.loadJoinEntitiesIfAbsent(entity, String.class, null);

            verify(dao).loadJoinEntitiesIfAbsent(entity, "orders", null);
        }
    }

    // loadJoinEntitiesIfAbsent(Collection<T>, Class<?>, Collection<String>) - full path with multiple props (lines 1136-1154)
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_Class_SelectProps() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            daoUtil.when(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(List.of("orders", "addresses"));
            doNothing().when(dao)
                    .loadJoinEntitiesIfAbsent(ArgumentMatchers.<Collection<TestEntity>> any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());

            dao.loadJoinEntitiesIfAbsent(entities, String.class, null);

            verify(dao).loadJoinEntitiesIfAbsent(entities, "orders", null);
            verify(dao).loadJoinEntitiesIfAbsent(entities, "addresses", null);
        }
    }

    // loadJoinEntitiesIfAbsent(Collection<T>, Class<?>, Collection<String>) - empty entities early return
    @Test
    public void testloadJoinEntitiesIfAbsent_CollectionEntity_Class_SelectProps_EmptyEntities() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        dao.loadJoinEntitiesIfAbsent(List.<TestEntity> of(), String.class, null);

        Mockito.verify(dao, Mockito.never())
                .loadJoinEntitiesIfAbsent(ArgumentMatchers.<List<TestEntity>> any(), ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    // deleteJoinEntities(T, Class<?>) - single propName path (lines 1587-1588)
    @Test
    public void testDeleteJoinEntities_Entity_Class_SingleProp() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            daoUtil.when(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(List.of("orders"));
            doReturn(5).when(dao).deleteJoinEntities(entity, "orders");

            int result = dao.deleteJoinEntities(entity, String.class);

            assertEquals(5, result);
            verify(dao).deleteJoinEntities(entity, "orders");
        }
    }

    // deleteJoinEntities(T, Class<?>) - multi propName transaction path (lines 1589-1604)
    @Test
    public void testDeleteJoinEntities_Entity_Class_MultiProp() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        DataSource dataSource = Mockito.mock(DataSource.class);
        SqlTransaction tran = Mockito.mock(SqlTransaction.class);

        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");
        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class);
             MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            daoUtil.when(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(List.of("orders", "addresses"));
            daoUtil.when(() -> DaoUtil.getReadOps(dao)).thenReturn(dao);
            jdbcUtil.when(() -> JdbcUtil.beginTransaction(dataSource)).thenReturn(tran);
            doReturn(3).when(dao).deleteJoinEntities(entity, "orders");
            doReturn(4).when(dao).deleteJoinEntities(entity, "addresses");

            int result = dao.deleteJoinEntities(entity, String.class);

            assertEquals(7, result);
            verify(tran).commit();
            verify(tran).rollbackIfNotCommitted();
        }
    }

    @Test
    public void testDeleteJoinEntities_Entity_Class_MultiProp_ThrowsOnOverflow() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        DataSource dataSource = Mockito.mock(DataSource.class);
        SqlTransaction tran = Mockito.mock(SqlTransaction.class);

        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");
        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class);
             MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            daoUtil.when(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(List.of("orders", "addresses"));
            daoUtil.when(() -> DaoUtil.getReadOps(dao)).thenReturn(dao);
            jdbcUtil.when(() -> JdbcUtil.beginTransaction(dataSource)).thenReturn(tran);
            doReturn(Integer.MAX_VALUE).when(dao).deleteJoinEntities(entity, "orders");
            doReturn(1).when(dao).deleteJoinEntities(entity, "addresses");

            assertThrows(ArithmeticException.class, () -> dao.deleteJoinEntities(entity, String.class));

            verify(tran, Mockito.never()).commit();
            verify(tran).rollbackIfNotCommitted();
        }
    }

    // deleteJoinEntities(Collection<T>, Class<?>) - single propName path
    @Test
    public void testDeleteJoinEntities_CollectionEntity_Class_SingleProp() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            daoUtil.when(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(List.of("orders"));
            doReturn(5).when(dao).deleteJoinEntities(entities, "orders");

            int result = dao.deleteJoinEntities(entities, String.class);

            assertEquals(5, result);
            verify(dao).deleteJoinEntities(entities, "orders");
        }
    }

    // deleteJoinEntities(Collection<T>, Class<?>) - empty entities early return (line 1633-1634)
    @Test
    public void testDeleteJoinEntities_CollectionEntity_Class_EmptyEntities_ReturnsZero() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        int result = dao.deleteJoinEntities(List.<TestEntity> of(), String.class);

        assertEquals(0, result);
    }

    // deleteJoinEntities(Collection<T>, Class<?>) - multi propName transaction path
    @Test
    public void testDeleteJoinEntities_CollectionEntity_Class_MultiProp() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        DataSource dataSource = Mockito.mock(DataSource.class);
        SqlTransaction tran = Mockito.mock(SqlTransaction.class);

        when(dao.targetDaoInterface()).thenReturn(TestJoinDao.class);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.targetTableName()).thenReturn("test");
        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class);
             MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            daoUtil.when(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(List.of("orders", "addresses"));
            daoUtil.when(() -> DaoUtil.getReadOps(dao)).thenReturn(dao);
            jdbcUtil.when(() -> JdbcUtil.beginTransaction(dataSource)).thenReturn(tran);
            doReturn(2).when(dao).deleteJoinEntities(entities, "orders");
            doReturn(3).when(dao).deleteJoinEntities(entities, "addresses");

            int result = dao.deleteJoinEntities(entities, String.class);

            assertEquals(5, result);
            verify(tran).commit();
            verify(tran).rollbackIfNotCommitted();
        }
    }

    // deleteJoinEntities(T, Collection<String>) - empty propNames (line 1757-1758)
    @Test
    public void testDeleteJoinEntities_Entity_PropNames_EmptyPropNames_ReturnsZero() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();

        int result = dao.deleteJoinEntities(entity, List.<String> of());

        assertEquals(0, result);
    }

    // deleteJoinEntities(T, Collection<String>) - single propName path (lines 1761-1762)
    @Test
    public void testDeleteJoinEntities_Entity_PropNames_SingleProp() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        doReturn(5).when(dao).deleteJoinEntities(ArgumentMatchers.same(entity), ArgumentMatchers.anyString());

        int result = dao.deleteJoinEntities(entity, List.of("orders"));

        assertEquals(5, result);
        verify(dao).deleteJoinEntities(entity, "orders");
    }

    // deleteJoinEntities(T, Collection<String>) - multi propName transaction path (lines 1764-1778)
    @Test
    public void testDeleteJoinEntities_Entity_PropNames_MultiProp() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        DataSource dataSource = Mockito.mock(DataSource.class);
        SqlTransaction tran = Mockito.mock(SqlTransaction.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.beginTransaction(dataSource)).thenReturn(tran);
            doReturn(3).when(dao).deleteJoinEntities(entity, "orders");
            doReturn(4).when(dao).deleteJoinEntities(entity, "addresses");

            int result = dao.deleteJoinEntities(entity, List.of("orders", "addresses"));

            assertEquals(7, result);
            verify(tran).commit();
            verify(tran).rollbackIfNotCommitted();
        }
    }

    @Test
    public void testDeleteJoinEntities_Entity_PropNames_MultiProp_ThrowsOnOverflow() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        DataSource dataSource = Mockito.mock(DataSource.class);
        SqlTransaction tran = Mockito.mock(SqlTransaction.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.beginTransaction(dataSource)).thenReturn(tran);
            doReturn(Integer.MAX_VALUE).when(dao).deleteJoinEntities(entity, "orders");
            doReturn(1).when(dao).deleteJoinEntities(entity, "addresses");

            assertThrows(ArithmeticException.class, () -> dao.deleteJoinEntities(entity, List.of("orders", "addresses")));

            verify(tran, Mockito.never()).commit();
            verify(tran).rollbackIfNotCommitted();
        }
    }

    // deleteJoinEntities(Collection<T>, Collection<String>) - empty entities or propNames (lines 1862-1863)
    @Test
    public void testDeleteJoinEntities_Entities_PropNames_EmptyEntities_ReturnsZero() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        int result = dao.deleteJoinEntities(List.<TestEntity> of(), List.of("orders"));

        assertEquals(0, result);
    }

    // deleteJoinEntities(Collection<T>, Collection<String>) - empty propNames
    @Test
    public void testDeleteJoinEntities_Entities_PropNames_EmptyPropNames_ReturnsZero() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        int result = dao.deleteJoinEntities(entities, List.<String> of());

        assertEquals(0, result);
    }

    // deleteJoinEntities(Collection<T>, Collection<String>) - single propName path (lines 1866-1867)
    @Test
    public void testDeleteJoinEntities_Entities_PropNames_SingleProp() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        doReturn(5).when(dao).deleteJoinEntities(ArgumentMatchers.same(entities), ArgumentMatchers.anyString());

        int result = dao.deleteJoinEntities(entities, List.of("orders"));

        assertEquals(5, result);
        verify(dao).deleteJoinEntities(entities, "orders");
    }

    // deleteJoinEntities(Collection<T>, Collection<String>) - multi propName transaction path (lines 1869-1883)
    @Test
    public void testDeleteJoinEntities_Entities_PropNames_MultiProp() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        DataSource dataSource = Mockito.mock(DataSource.class);
        SqlTransaction tran = Mockito.mock(SqlTransaction.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.beginTransaction(dataSource)).thenReturn(tran);
            doReturn(2).when(dao).deleteJoinEntities(entities, "orders");
            doReturn(3).when(dao).deleteJoinEntities(entities, "addresses");

            int result = dao.deleteJoinEntities(entities, List.of("orders", "addresses"));

            assertEquals(5, result);
            verify(tran).commit();
            verify(tran).rollbackIfNotCommitted();
        }
    }

    @Test
    public void testDeleteJoinEntities_Entities_PropNames_MultiProp_ThrowsOnOverflow() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());
        DataSource dataSource = Mockito.mock(DataSource.class);
        SqlTransaction tran = Mockito.mock(SqlTransaction.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.beginTransaction(dataSource)).thenReturn(tran);
            doReturn(Integer.MAX_VALUE).when(dao).deleteJoinEntities(entities, "orders");
            doReturn(1).when(dao).deleteJoinEntities(entities, "addresses");

            assertThrows(ArithmeticException.class, () -> dao.deleteJoinEntities(entities, List.of("orders", "addresses")));

            verify(tran, Mockito.never()).commit();
            verify(tran).rollbackIfNotCommitted();
        }
    }

    // deleteJoinEntities(Collection<T>, Class<?>) — empty input is a no-op that short-circuits before prop-name
    // resolution. This documents the contract the unchecked twin must match (see UncheckedJoinEntityHelper).
    @Test
    public void testDeleteJoinEntities_EntitiesByClass_EmptyShortCircuitsBeforePropLookup() throws SQLException {
        TestJoinDao dao = Mockito.mock(TestJoinDao.class, Mockito.CALLS_REAL_METHODS);

        try (MockedStatic<DaoUtil> daoUtil = Mockito.mockStatic(DaoUtil.class)) {
            daoUtil.when(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(new ArrayList<>());

            int result = dao.deleteJoinEntities(new ArrayList<>(), String.class);

            assertEquals(0, result);
            daoUtil.verify(
                    () -> DaoUtil.getJoinEntityPropNamesByType(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()),
                    Mockito.never());
        }
    }
}
