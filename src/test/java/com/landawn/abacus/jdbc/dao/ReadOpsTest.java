package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.stream.Stream;

/**
 * Regression tests for the default methods of {@link ReadOps}: argument validation happens before any
 * delegation, and the single-property {@code list}/{@code stream} overloads forward exactly a singleton
 * select list plus a row mapper typed by the entity property.
 */
public class ReadOpsTest extends TestBase {

    interface TestDao extends Dao<TestEntity, TestDao> {
    }

    static final class TestEntity {
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

    private static TestDao newDao() {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        return dao;
    }

    @Test
    public void testNotExists_NegatesExists() throws SQLException {
        final TestDao dao = newDao();
        final Condition cond = Mockito.mock(Condition.class);
        when(dao.exists(cond)).thenReturn(true, false);

        assertFalse(dao.notExists(cond));
        assertTrue(dao.notExists(cond));
    }

    @Test
    public void testList_SingleProp_ForwardsSingletonSelectListAndTypedMapper() throws SQLException {
        final TestDao dao = newDao();
        final Condition cond = Mockito.mock(Condition.class);
        final List<String> expected = List.of("alice");
        Mockito.doReturn(expected).when(dao).list(anyList(), same(cond), ArgumentMatchers.<Jdbc.RowMapper<?>> any());

        assertSame(expected, dao.list("name", cond));

        @SuppressWarnings({ "unchecked", "rawtypes" })
        final ArgumentCaptor<Jdbc.RowMapper<?>> mapperCaptor = ArgumentCaptor.forClass((Class) Jdbc.RowMapper.class);
        verify(dao).list(eq(List.of("name")), same(cond), mapperCaptor.capture());

        // The property "name" is a String, so the mapper reads column 1 as a String.
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("alice");
        when(rs.getObject(1)).thenReturn("alice");
        assertEquals("alice", mapperCaptor.getValue().apply(rs));
    }

    @Test
    public void testList_SingleProp_ValidatesBeforeDelegating() throws SQLException {
        final TestDao dao = newDao();
        final Condition cond = Mockito.mock(Condition.class);
        final Jdbc.RowMapper<String> rowMapper = rs -> rs.getString(1);
        final Jdbc.RowFilter rowFilter = rs -> true;

        assertThrows(IllegalArgumentException.class, () -> dao.list("", cond));
        assertThrows(IllegalArgumentException.class, () -> dao.list((String) null, cond));
        assertThrows(IllegalArgumentException.class, () -> dao.list("name", (Condition) null));
        assertThrows(IllegalArgumentException.class, () -> dao.list("name", cond, (Jdbc.RowMapper<String>) null));
        assertThrows(IllegalArgumentException.class, () -> dao.list("name", (Condition) null, rowMapper));
        assertThrows(IllegalArgumentException.class, () -> dao.list("name", cond, (Jdbc.RowFilter) null, rowMapper));
        assertThrows(IllegalArgumentException.class, () -> dao.list("name", cond, rowFilter, (Jdbc.RowMapper<String>) null));
        assertThrows(IllegalArgumentException.class, () -> dao.list("", cond, rowFilter, rowMapper));

        verify(dao, never()).list(anyList(), any(Condition.class), ArgumentMatchers.<Jdbc.RowMapper<?>> any());
        verify(dao, never()).list(anyList(), any(Condition.class), any(Jdbc.RowFilter.class), ArgumentMatchers.<Jdbc.RowMapper<?>> any());
    }

    @Test
    public void testList_SinglePropWithFilterAndMapper_ForwardsSingletonSelectList() throws SQLException {
        final TestDao dao = newDao();
        final Condition cond = Mockito.mock(Condition.class);
        final Jdbc.RowMapper<String> rowMapper = rs -> rs.getString(1);
        final Jdbc.RowFilter rowFilter = rs -> true;
        final List<String> expected = List.of("x");

        Mockito.doReturn(expected).when(dao).list(eq(List.of("name")), same(cond), same(rowFilter), same(rowMapper));

        assertSame(expected, dao.list("name", cond, rowFilter, rowMapper));
    }

    @Test
    public void testStream_SingleProp_ForwardsSingletonSelectListAndTypedMapper() throws SQLException {
        final TestDao dao = newDao();
        final Condition cond = Mockito.mock(Condition.class);
        @SuppressWarnings("unchecked")
        final Stream<Object> expected = Mockito.mock(Stream.class);
        Mockito.doReturn(expected).when(dao).stream(anyList(), same(cond), ArgumentMatchers.<Jdbc.RowMapper<?>> any());

        assertSame(expected, dao.stream("unknownProp", cond));

        @SuppressWarnings({ "unchecked", "rawtypes" })
        final ArgumentCaptor<Jdbc.RowMapper<?>> mapperCaptor = ArgumentCaptor.forClass((Class) Jdbc.RowMapper.class);
        verify(dao).stream(eq(List.of("unknownProp")), same(cond), mapperCaptor.capture());

        // An unknown property falls back to a plain column-1 object read.
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(42);
        assertEquals(42, mapperCaptor.getValue().apply(rs));
    }

    @Test
    public void testStream_SingleProp_ValidatesBeforeDelegating() {
        final TestDao dao = newDao();
        final Condition cond = Mockito.mock(Condition.class);
        final Jdbc.RowMapper<String> rowMapper = rs -> rs.getString(1);
        final Jdbc.RowFilter rowFilter = rs -> true;

        assertThrows(IllegalArgumentException.class, () -> dao.stream("", cond));
        assertThrows(IllegalArgumentException.class, () -> dao.stream("name", (Condition) null));
        assertThrows(IllegalArgumentException.class, () -> dao.stream("name", cond, (Jdbc.RowMapper<String>) null));
        assertThrows(IllegalArgumentException.class, () -> dao.stream("name", cond, (Jdbc.RowFilter) null, rowMapper));
        assertThrows(IllegalArgumentException.class, () -> dao.stream("name", cond, rowFilter, (Jdbc.RowMapper<String>) null));

        verify(dao, never()).stream(anyList(), any(Condition.class), ArgumentMatchers.<Jdbc.RowMapper<?>> any());
        verify(dao, never()).stream(anyList(), any(Condition.class), any(Jdbc.RowFilter.class), ArgumentMatchers.<Jdbc.RowMapper<?>> any());
    }

    @Test
    public void testForeach_ValidatesBeforeDelegating() throws SQLException {
        final TestDao dao = newDao();
        final Condition cond = Mockito.mock(Condition.class);
        final Consumer<DisposableObjArray> consumer = row -> {
        };

        assertThrows(IllegalArgumentException.class, () -> dao.foreach((Condition) null, consumer));
        assertThrows(IllegalArgumentException.class, () -> dao.foreach(cond, (Consumer<DisposableObjArray>) null));
        assertThrows(IllegalArgumentException.class, () -> dao.foreach(List.of("name"), null, consumer));
        assertThrows(IllegalArgumentException.class, () -> dao.foreach(List.of("name"), cond, null));

        verify(dao, never()).forEach(any(Condition.class), any(Jdbc.RowConsumer.class));
        verify(dao, never()).forEach(anyList(), any(Condition.class), any(Jdbc.RowConsumer.class));
    }

    @Test
    public void testForeach_NullSelectPropNames_IsForwardedAsSelectAll() throws SQLException {
        final TestDao dao = newDao();
        final Condition cond = Mockito.mock(Condition.class);
        final Consumer<DisposableObjArray> consumer = row -> {
        };
        Mockito.doNothing().when(dao).forEach(ArgumentMatchers.<List<String>> isNull(), same(cond), any(Jdbc.RowConsumer.class));

        dao.foreach(null, cond, consumer);

        verify(dao).forEach(ArgumentMatchers.<List<String>> isNull(), same(cond), any(Jdbc.RowConsumer.class));
    }
}
