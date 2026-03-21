package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.SqlBuilder;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.u.Optional;

public class UncheckedDaoTest extends TestBase {

    private interface TestUncheckedDao extends UncheckedDao<TestEntity, SqlBuilder.PSC, TestUncheckedDao> {
    }

    public static final class TestEntity {
        private String name;
        private String status;

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(final String status) {
            this.status = status;
        }
    }

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedDao.class.isInterface());
    }

    @Test
    public void testExtendsDao() {
        assertTrue(Dao.class.isAssignableFrom(UncheckedDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, UncheckedDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(UncheckedDao.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testBatchSave_DefaultOverloads() {
        TestUncheckedDao dao = Mockito.mock(TestUncheckedDao.class, Mockito.CALLS_REAL_METHODS);
        List<TestEntity> entities = List.of(new TestEntity());

        doNothing().when(dao).batchSave(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        doNothing().when(dao).batchSave(entities, List.of("name"), JdbcUtil.DEFAULT_BATCH_SIZE);
        doNothing().when(dao).batchSave("insertUser", entities, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertDoesNotThrow(() -> dao.batchSave(entities));
        assertDoesNotThrow(() -> dao.batchSave(entities, List.of("name")));
        assertDoesNotThrow(() -> dao.batchSave("insertUser", entities));

        verify(dao).batchSave(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        verify(dao).batchSave(entities, List.of("name"), JdbcUtil.DEFAULT_BATCH_SIZE);
        verify(dao).batchSave("insertUser", entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    @Test
    public void testNotExists_AndListSingleProperty() {
        TestUncheckedDao dao = Mockito.mock(TestUncheckedDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);

        when(dao.exists(condition)).thenReturn(false);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.list(eq("name"), same(condition), Mockito.<Jdbc.RowMapper<String>>any())).thenReturn(List.of("alice"));

        assertTrue(dao.notExists(condition));
        assertEquals(List.of("alice"), dao.list("name", condition));
    }

    @Test
    public void testList_AndForeach_ConvenienceOverloads() {
        TestUncheckedDao dao = Mockito.mock(TestUncheckedDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);
        Jdbc.RowMapper<String> rowMapper = rs -> "mapped";
        Jdbc.RowFilter rowFilter = rs -> true;
        Consumer<DisposableObjArray> consumer = row -> {
        };
        List<String> expected = List.of("mapped");

        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.list(eq(List.of("name")), same(condition), same(rowMapper))).thenReturn(expected);
        when(dao.list(eq(List.of("name")), same(condition), same(rowFilter), same(rowMapper))).thenReturn(expected);

        assertEquals(expected, dao.list("name", condition, rowMapper));
        assertEquals(expected, dao.list("name", condition, rowFilter, rowMapper));

        dao.foreach(condition, consumer);
        dao.foreach(List.of("name"), condition, consumer);

        ArgumentCaptor<Jdbc.RowConsumer> rowConsumerCaptor = ArgumentCaptor.forClass(Jdbc.RowConsumer.class);
        ArgumentCaptor<Jdbc.RowConsumer> selectRowConsumerCaptor = ArgumentCaptor.forClass(Jdbc.RowConsumer.class);

        verify(dao).forEach(same(condition), rowConsumerCaptor.capture());
        verify(dao).forEach(eq(List.of("name")), same(condition), selectRowConsumerCaptor.capture());

        assertNotNull(rowConsumerCaptor.getValue());
        assertNotNull(selectRowConsumerCaptor.getValue());
    }

    @Test
    public void testUpdate_ConvenienceOverloads() {
        TestUncheckedDao dao = Mockito.mock(TestUncheckedDao.class, Mockito.CALLS_REAL_METHODS);
        Condition condition = Mockito.mock(Condition.class);
        TestEntity entity = new TestEntity();
        entity.setName("updated");
        entity.setStatus("active");

        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.update(anyMap(), same(condition))).thenReturn(2);
        when(dao.update(same(entity), Mockito.<Collection<String>>any(), same(condition))).thenReturn(3);

        assertEquals(2, dao.update("status", "active", condition));
        assertEquals(3, dao.update(entity, condition));

        ArgumentCaptor<Map<String, Object>> updatePropsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Collection<String>> propNamesCaptor = ArgumentCaptor.forClass(Collection.class);

        verify(dao).update(updatePropsCaptor.capture(), same(condition));
        verify(dao).update(same(entity), propNamesCaptor.capture(), same(condition));

        assertEquals("active", updatePropsCaptor.getValue().get("status"));
        assertTrue(propNamesCaptor.getValue().contains("name"));
    }

    @Test
    public void testUpsert_UniquePropertiesBuildsCondition() {
        TestUncheckedDao dao = Mockito.mock(TestUncheckedDao.class, Mockito.CALLS_REAL_METHODS);
        TestEntity entity = new TestEntity();
        entity.setName("alice");

        doReturn(entity).when(dao).upsert(same(entity), any(Condition.class));

        assertSame(entity, dao.upsert(entity, List.of("name")));
    }

    @Test
    public void testUpsert_Condition_InsertAndUpdatePaths() {
        TestUncheckedDao insertDao = Mockito.mock(TestUncheckedDao.class, Mockito.CALLS_REAL_METHODS);
        Condition insertCondition = Mockito.mock(Condition.class);
        TestEntity entityToInsert = new TestEntity();
        entityToInsert.setName("inserted");

        when(insertDao.findOnlyOne(insertCondition)).thenReturn(Optional.empty());
        doNothing().when(insertDao).save(entityToInsert);

        assertSame(entityToInsert, insertDao.upsert(entityToInsert, insertCondition));
        verify(insertDao).save(entityToInsert);

        TestUncheckedDao updateDao = Mockito.mock(TestUncheckedDao.class, Mockito.CALLS_REAL_METHODS);
        Condition updateCondition = Mockito.mock(Condition.class);
        TestEntity entityToMerge = new TestEntity();
        entityToMerge.setName("new-name");
        entityToMerge.setStatus("active");
        TestEntity dbEntity = new TestEntity();
        dbEntity.setName("old-name");
        dbEntity.setStatus("inactive");

        when(updateDao.targetEntityClass()).thenReturn(TestEntity.class);
        when(updateDao.findOnlyOne(updateCondition)).thenReturn(Optional.of(dbEntity));
        when(updateDao.update(same(dbEntity), Mockito.<Collection<String>>any(), same(updateCondition))).thenReturn(1);

        TestEntity result = updateDao.upsert(entityToMerge, updateCondition);

        assertSame(dbEntity, result);
        assertEquals("new-name", dbEntity.getName());
        assertEquals("active", dbEntity.getStatus());
    }
}
