package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.N;

class NamedQueryParameterBindingTest extends NamedQueryBindingTestSupport {

    @Test
    void testSetNullWithSqlType() throws SQLException {
        String paramName = "param1";
        int sqlType = Types.INTEGER;

        NamedQuery result = namedQuery.setNull(paramName, sqlType);

        verify(mockPreparedStatement).setNull(1, sqlType);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetNullWithSqlTypeAndTypeName() throws SQLException {
        String paramName = "param1";
        int sqlType = Types.STRUCT;
        String typeName = "MY_TYPE";

        NamedQuery result = namedQuery.setNull(paramName, sqlType, typeName);

        verify(mockPreparedStatement).setNull(1, sqlType, typeName);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setNull_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setNull(parameter, Types.INTEGER),
                (statement, index) -> statement.setNull(index, Types.INTEGER));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setNullTypeName_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setNull(parameter, Types.STRUCT, "MY_TYPE"),
                (statement, index) -> statement.setNull(index, Types.STRUCT, "MY_TYPE"));
    }

    @Test
    void testSetNullWithSqlTypeNotFound() throws SQLException {
        String paramName = "nonExistent";
        int sqlType = Types.INTEGER;

        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setNull(paramName, sqlType);
        });
    }

    @Test
    void testUnknownParameterPreservesIllegalArgumentWhenCloseHandlerThrows() {
        final RuntimeException closeFailure = new RuntimeException("close handler failed");
        namedQuery.onClose(() -> {
            throw closeFailure;
        });

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> namedQuery.setNull("nonExistent", Types.INTEGER));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void testSetParametersWithSetter() throws SQLException {
        String param = "test";
        Jdbc.TriParametersSetter<NamedQuery, String> setter = (sql, query, p) -> {
            query.setString("param1", p);
        };

        NamedQuery result = namedQuery.setParameters(param, setter);

        verify(mockPreparedStatement).setString(1, "test");
        assertSame(namedQuery, result);
    }

    @Test
    void testParameterNotFound() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setString("nonExistentParam", "value");
        });

        assertTrue(ex.getMessage().contains("nonExistentParam"));
        assertTrue(ex.getMessage().contains("[param1, param2]"));
        assertTrue(ex.getMessage().contains("SELECT * FROM table WHERE col1 = :param1 AND col2 = :param2"));
    }

    @Test
    void testSetParametersMap() throws SQLException {
        Map<String, Object> params = new HashMap<>();
        params.put("param1", "value1");
        params.put("param2", 123);

        NamedQuery result = namedQuery.setParameters(params);

        verify(mockPreparedStatement).setString(1, "value1");
        verify(mockPreparedStatement).setInt(2, 123);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetParametersObject() throws SQLException {
        TestEntity entity = new TestEntity();
        entity.setParam1("value1");
        entity.setParam2(123);

        NamedQuery result = namedQuery.setParameters(entity);

        // Would need proper mock setup for entity parsing
        assertSame(namedQuery, result);
    }

    @Test
    void testSetParametersEntityWithNames() throws SQLException {
        TestEntity entity = new TestEntity();
        entity.setParam1("value1");
        entity.setParam2(123);

        Collection<String> paramNames = Arrays.asList("param1");

        NamedQuery result = namedQuery.setParameters(entity, paramNames);

        // Would need proper mock setup for entity parsing
        assertSame(namedQuery, result);
    }

    @Test
    void testSetParameters_Map_SetsMatchingParams() throws SQLException {
        // namedQuery has "param1" at index 1 and "param2" at index 2 (loop branch, paramCount=2)
        // null values route through stmt.setObject directly
        Map<String, Object> params = new HashMap<>();
        params.put("param1", null);
        params.put("param2", null);
        params.put("z", "ignored"); // not in query — should be ignored
        namedQuery.setParameters(params);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), isNull());
    }

    @Test
    void setParameters_collectionOverload_bindsValuesInOrder() throws SQLException {
        NamedQuery result = namedQuery.setParameters(Arrays.asList("value1", 123));
        assertSame(namedQuery, result);
        verify(mockPreparedStatement).setString(eq(1), eq("value1"));
        verify(mockPreparedStatement).setInt(eq(2), eq(123));
    }

    @Test
    void setParameters_arrayOverload_bindsValuesInOrder() throws SQLException {
        NamedQuery result = namedQuery.setParameters(new Object[] { "value1", 123 });
        assertSame(namedQuery, result);
        verify(mockPreparedStatement).setString(eq(1), eq("value1"));
        verify(mockPreparedStatement).setInt(eq(2), eq(123));
    }

    @Test
    void testSetParameters_Object_EntityId() throws SQLException {
        // EntityId branch (L3984)
        final com.landawn.abacus.util.EntityId eid = com.landawn.abacus.util.EntityId.of("param1", "v1");
        NamedQuery result = namedQuery.setParameters((Object) eid);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetParameters_Object_SingleValue() throws SQLException {
        // Single parameter query, unsupported type → falls into parameterCount==1 branch (L3987)
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        NamedQuery result = q.setParameters(42);
        assertSame(q, result);
        verify(mockPreparedStatement).setInt(1, 42);
    }

    @Test
    void testSetParameters_Object_SingleValueCalendarIsNotBoundAsBean() throws SQLException {
        // GregorianCalendar passes Beans.isBeanClass (getter/setter pairs such as getTimeInMillis/setTimeInMillis),
        // but it is a single value in the Abacus type system: with one placeholder it must be bound directly
        // instead of failing with "No property found with name: since".
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("since"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        java.util.GregorianCalendar since = new java.util.GregorianCalendar();
        since.setTimeInMillis(1_000L);

        assertSame(q, q.setParameters(since));

        verify(mockPreparedStatement).setTimestamp(1, new Timestamp(1_000L));
        verify(mockPreparedStatement, never()).close();
    }

    @Test
    void testSetParameters_EntityWithNames_ParamNotInQuery() throws SQLException {
        // Property exists but not in query parameter list → throws (L4060)
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "b", "c", "d", "e"));
        when(mockParsedSql.parameterCount()).thenReturn(5);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        // TestEntity has param1/param2; query has a/b/c/d/e → "param1" not in query
        TestEntity entity = new TestEntity();
        entity.setParam1("x");
        assertThrows(IllegalArgumentException.class, () -> q.setParameters(entity, Arrays.asList("param1")));
    }

    @Test
    void testSetParameters_EntityWithNames_NonBeanType() throws SQLException {
        // Non-bean entity → throws (L4083-4085)
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setParameters("notABean", Arrays.asList("param1")));
    }

    @Test
    void testSetParameters_Entity_TwoOccurrences() throws SQLException {
        NamedQuery q = createQueryWithParameters(ImmutableList.of("a", "b", "c", "p", "p"));
        BeanWithP bean = new BeanWithP();
        bean.setP("val");
        NamedQuery result = q.setParameters(bean, Arrays.asList("p"));
        assertSame(q, result);
    }

    @Test
    void testSetParameters_Entity_ThreeOccurrences() throws SQLException {
        NamedQuery q = createQueryWithParameters(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        BeanWithP bean = new BeanWithP();
        bean.setP("val");
        NamedQuery result = q.setParameters(bean, Arrays.asList("p"));
        assertSame(q, result);
    }

    @Test
    void testSetParameters_Entity_FourOccurrences() throws SQLException {
        NamedQuery q = createQueryWithParameters(ImmutableList.of("a", "p", "p", "p", "p"));
        BeanWithP bean = new BeanWithP();
        bean.setP("val");
        NamedQuery result = q.setParameters(bean, Arrays.asList("p"));
        assertSame(q, result);
    }

    @Test
    void testSetParameters_Object_MapDelegates() throws SQLException {
        Map<String, Object> params = new HashMap<>();
        params.put("param1", "hello");
        NamedQuery result = namedQuery.setParameters((Object) params);
        assertSame(namedQuery, result);
        verify(mockPreparedStatement).setString(eq(1), eq("hello"));
    }

    @Test
    void setParameters_objectContainingCollection_bindsValuesInOrder() throws SQLException {
        Object params = Arrays.asList("value1", 123);
        NamedQuery result = namedQuery.setParameters(params);
        assertSame(namedQuery, result);
        verify(mockPreparedStatement).setString(eq(1), eq("value1"));
        verify(mockPreparedStatement).setInt(eq(2), eq(123));
    }

    @Test
    void setParameters_objectContainingArray_bindsValuesInOrder() throws SQLException {
        Object params = new Object[] { "value1", 123 };
        NamedQuery result = namedQuery.setParameters(params);
        assertSame(namedQuery, result);
        verify(mockPreparedStatement).setString(eq(1), eq("value1"));
        verify(mockPreparedStatement).setInt(eq(2), eq(123));
    }

    @Test
    void nullEntityIdRemainsANoOpWhenTheQueryHasNoParameters() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select 1"));

        query.setParameters((EntityId) null);

        verifyNoInteractions(stmt);

        query.close();
        clearInvocations(stmt);
        query.setParameters((EntityId) null);
        verifyNoInteractions(stmt);
    }

    @Test
    void testSetParametersMapNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters((Map<String, ?>) null);
        });
    }

    @Test
    void testSetParametersObjectNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters((Object) null);
        });
    }

    @Test
    void testSetParametersEntityWithNamesNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters(null, Arrays.asList("param1"));
        });
    }

    @Test
    void testSetParametersWithSetterNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters("test", (Collection<String>) null);
        });
    }

    @Test
    void testSetParameters_EntityWithNames_NullNamesOnBean() throws SQLException {
        // The two existing null-names tests pass a non-bean first argument, so they exit at the bean-class check
        // and never reach the parameterNamesToSet guard.
        final TestEntity entity = new TestEntity();
        entity.setParam1("x");

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setParameters(entity, (java.util.Collection<String>) null));

        assertTrue(failure.getMessage().contains("parameterNamesToSet"));
        verify(mockPreparedStatement).close();
    }

    @Test
    void entityIdNullCheckStillRunsAfterQueryClose() {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select :id"));
        query.close();
        clearInvocations(stmt);

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> query.setParameters((EntityId) null));
        assertTrue(failure.getMessage().contains("entityId"));

        verifyNoInteractions(stmt);
    }

    @Test
    void nullEntityIdValuesStillBindSqlNull() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select :id"));

        query.setParameters(EntityId.of("id", null));

        verify(stmt).setObject(1, null);
        verifyNoMoreInteractions(stmt);
    }

    @Test
    void testSetParameters_Object_UnsupportedType() throws SQLException {
        // Multi-param query, unsupported non-bean type → throws (L3993-3994)
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setParameters(42));
    }

    @Test
    void testSetParameters_EntityWithNames_PropNotFound() throws SQLException {
        // Bean class but the requested property doesn't exist → throws (L4050)
        TestEntity entity = new TestEntity();
        entity.setParam1("x");
        entity.setParam2(1);
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setParameters(entity, Arrays.asList("nonExistentProp")));
    }

    @Test
    void testSetParameters_WithSetter_ExceptionClosesQuery() throws SQLException {
        assertThrows(RuntimeException.class, () -> namedQuery.setParameters("x", (sql, q, p) -> {
            throw new RuntimeException("setter error");
        }));
        verify(mockPreparedStatement).close();
    }

    @Test
    void testSetParameters_Object_EntityId_ExceptionClosesAndRethrows() throws SQLException {
        doThrow(new RuntimeException("forced")).when(mockPreparedStatement).setObject(anyInt(), isNull());
        final com.landawn.abacus.util.EntityId eid = com.landawn.abacus.util.EntityId.of("param1", null);
        assertThrows(RuntimeException.class, () -> namedQuery.setParameters((Object) eid));
    }

    @Test
    void testSetParameters_Map_ExceptionClosesAndRethrows() throws SQLException {
        doThrow(new RuntimeException("forced")).when(mockPreparedStatement).setObject(anyInt(), isNull());
        Map<String, Object> params = new HashMap<>();
        params.put("param1", null);
        assertThrows(RuntimeException.class, () -> namedQuery.setParameters(params));
    }

    @Test
    void testSetParametersMapPreservesBindingFailureWhenCloseHandlerThrows() throws SQLException {
        final RuntimeException bindingFailure = new RuntimeException("binding failed");
        final RuntimeException closeFailure = new RuntimeException("close handler failed");
        doThrow(bindingFailure).when(mockPreparedStatement).setObject(anyInt(), isNull());
        namedQuery.onClose(() -> {
            throw closeFailure;
        });
        final Map<String, Object> params = new HashMap<>();
        params.put("param1", null);

        final RuntimeException thrown = assertThrows(RuntimeException.class, () -> namedQuery.setParameters(params));

        assertSame(bindingFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void testSetParameters_Object_BeanMissingProp_Throws() throws SQLException {
        BeanNoProps bean = new BeanNoProps();
        bean.setFoo("x");
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setParameters(bean));
    }

    @Test
    void setParameters_collectionOverload_preservesFailureAndCallerOwnership() throws SQLException {
        final RuntimeException failure = new RuntimeException("binding failed");
        doThrow(failure).when(mockPreparedStatement).setObject(1, null);
        final List<Object> parameters = Arrays.asList(null, null);

        assertSame(failure, assertThrows(RuntimeException.class, () -> namedQuery.setParameters(parameters)));

        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement, never()).setObject(eq(2), isNull());
        verify(mockPreparedStatement, never()).close();
    }

    @Test
    void setParameters_arrayOverload_preservesFailureAndCallerOwnership() throws SQLException {
        final RuntimeException failure = new RuntimeException("binding failed");
        doThrow(failure).when(mockPreparedStatement).setObject(1, null);
        final Object[] parameters = { null, null };

        assertSame(failure, assertThrows(RuntimeException.class, () -> namedQuery.setParameters(parameters)));

        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement, never()).setObject(eq(2), isNull());
        verify(mockPreparedStatement, never()).close();
    }

    @Test
    void testSetParameters_Object_SingleValue_ExceptionClosesAndRethrows() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        // Integer (not bean/Map/Collection/Object[]/EntityId), paramCount==1 → calls stmt.setInt
        doThrow(new RuntimeException("forced")).when(mockPreparedStatement).setInt(anyInt(), anyInt());
        assertThrows(RuntimeException.class, () -> q.setParameters(Integer.valueOf(42)));
    }

    @Test
    void setParameters_objectContainingCollection_preservesFailureAndClosesQuery() throws SQLException {
        final RuntimeException failure = new RuntimeException("binding failed");
        doThrow(failure).when(mockPreparedStatement).setObject(1, null);
        final Object parameters = Arrays.asList(null, null);

        assertSame(failure, assertThrows(RuntimeException.class, () -> namedQuery.setParameters(parameters)));

        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement, never()).setObject(eq(2), isNull());
        verify(mockPreparedStatement).close();
    }

    @Test
    void setParameters_objectContainingArray_preservesFailureAndClosesQuery() throws SQLException {
        final RuntimeException failure = new RuntimeException("binding failed");
        doThrow(failure).when(mockPreparedStatement).setObject(1, null);
        final Object parameters = new Object[] { null, null };

        assertSame(failure, assertThrows(RuntimeException.class, () -> namedQuery.setParameters(parameters)));

        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement, never()).setObject(eq(2), isNull());
        verify(mockPreparedStatement).close();
    }

    @Test
    void requiredEntityIdIsRejectedWithItsParameterNameAndClosesQuery() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select :id"));

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> query.setParameters((EntityId) null));

        assertTrue(failure.getMessage().contains("entityId"));
        assertTrue(query.isClosed);
        verify(stmt).close();
        verifyNoMoreInteractions(stmt);
    }

    @Test
    void testAddBatchParametersCollection() throws SQLException {
        List<Map<String, Object>> batchParams = new ArrayList<>();
        batchParams.add(Map.of("param1", "value1", "param2", 100));
        batchParams.add(Map.of("param1", "value2", "param2", 200));

        NamedQuery result = namedQuery.addBatchParameters(batchParams);

        verify(mockPreparedStatement, times(2)).setString(anyInt(), anyString());
        verify(mockPreparedStatement, times(2)).setInt(anyInt(), anyInt());
        verify(mockPreparedStatement, times(2)).addBatch();
        assertSame(namedQuery, result);
    }

    @Test
    void testAddBatchParametersIterator() throws SQLException {
        List<Map<String, Object>> batchParams = new ArrayList<>();
        batchParams.add(Map.of("param1", "value1", "param2", 100));
        batchParams.add(Map.of("param1", "value2", "param2", 200));

        NamedQuery result = namedQuery.addBatchParameters(batchParams.iterator());

        verify(mockPreparedStatement, times(2)).setString(anyInt(), anyString());
        verify(mockPreparedStatement, times(2)).setInt(anyInt(), anyInt());
        verify(mockPreparedStatement, times(2)).addBatch();
        assertSame(namedQuery, result);
    }

    @Test
    void testAddBatchParameters_CollectionElements() throws Exception {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "b"));
        when(mockParsedSql.parameterCount()).thenReturn(2);
        when(mockParsedSql.originalSql()).thenReturn("SELECT :a, :b");
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        List<Collection<?>> batch = new ArrayList<>();
        batch.add(Arrays.asList("val1", "val2"));
        batch.add(Arrays.asList("val3", "val4"));
        q.addBatchParameters(batch.iterator());
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParameters_ArrayElements() throws Exception {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "b"));
        when(mockParsedSql.parameterCount()).thenReturn(2);
        when(mockParsedSql.originalSql()).thenReturn("SELECT :a, :b");
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        List<Object[]> batch = new ArrayList<>();
        batch.add(new Object[] { "v1", 1 });
        batch.add(new Object[] { "v2", 2 });
        q.addBatchParameters(batch.iterator());
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParameters_EntityId_Elements() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        final com.landawn.abacus.util.EntityId eid1 = com.landawn.abacus.util.EntityId.of("param1", "v1");
        final com.landawn.abacus.util.EntityId eid2 = com.landawn.abacus.util.EntityId.of("param1", "v2");
        List<com.landawn.abacus.util.EntityId> batch = Arrays.asList(eid1, eid2);
        q.addBatchParameters(batch.iterator());
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParameters_SingleValue_Elements() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        q.addBatchParameters(Arrays.asList(1, 2, 3).iterator());
        // Single-value batch rows bind through the Abacus type system (like the non-batch path),
        // so an Integer is dispatched to setInt(1, x) rather than raw setObject(1, x).
        verify(mockPreparedStatement, times(3)).setInt(eq(1), anyInt());
        verify(mockPreparedStatement, times(3)).addBatch();
    }

    @Test
    void testAddBatchParameters_SingleValueCalendarElementsAreNotBoundAsBeans() throws SQLException {
        // See testSetParameters_Object_SingleValueCalendarIsNotBoundAsBean: the first-row dispatch must also treat
        // a Calendar as a single value when the SQL has exactly one placeholder.
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("since"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        java.util.GregorianCalendar first = new java.util.GregorianCalendar();
        first.setTimeInMillis(1_000L);
        java.util.GregorianCalendar second = new java.util.GregorianCalendar();
        second.setTimeInMillis(2_000L);

        q.addBatchParameters(Arrays.asList(first, second).iterator());

        verify(mockPreparedStatement).setTimestamp(1, new Timestamp(1_000L));
        verify(mockPreparedStatement).setTimestamp(1, new Timestamp(2_000L));
        verify(mockPreparedStatement, times(2)).addBatch();
        verify(mockPreparedStatement, never()).close();
    }

    @Test
    void testAddBatchParameters_BeanElements() throws SQLException {
        final TestEntity e1 = new TestEntity();
        e1.setParam1("v1");
        e1.setParam2(1);
        final TestEntity e2 = new TestEntity();
        e2.setParam1("v2");
        e2.setParam2(2);

        namedQuery.addBatchParameters(Arrays.asList(e1, e2).iterator());

        verify(mockPreparedStatement).setString(1, "v1");
        verify(mockPreparedStatement).setString(1, "v2");
        verify(mockPreparedStatement).setInt(2, 1);
        verify(mockPreparedStatement).setInt(2, 2);
        verify(mockPreparedStatement, times(2)).addBatch();
        // The bean branch deliberately never clears parameters between rows, so pre-bound
        // non-property named parameters survive across bean rows.
        verify(mockPreparedStatement, never()).clearParameters();
    }

    @Test
    void testAddBatchParameters_Iterator_BeanMultipleRows() throws SQLException {
        TestEntity e1 = new TestEntity();
        e1.setParam1("v1");
        e1.setParam2(1);
        TestEntity e2 = new TestEntity();
        e2.setParam1("v2");
        e2.setParam2(2);
        namedQuery.addBatchParameters(Arrays.asList(e1, e2).iterator());
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParametersCollectionNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.addBatchParameters((Collection<?>) null);
        });
    }

    @Test
    void testAddBatchParametersIteratorNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.addBatchParameters((Iterator<?>) null);
        });
    }

    @Test
    void testAddBatchParameters_NullElement() throws Exception {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        when(mockParsedSql.originalSql()).thenReturn("SELECT :a");
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        List<Object> batch = new ArrayList<>();
        batch.add(null);
        q.addBatchParameters(batch.iterator());
        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement).addBatch();
    }

    @Test
    void testAddBatchParameters_Collection_Empty() throws SQLException {
        NamedQuery result = namedQuery.addBatchParameters(new ArrayList<>());
        assertSame(namedQuery, result);
        verify(mockPreparedStatement, org.mockito.Mockito.never()).addBatch();
    }

    @Test
    void testAddBatchParameters_NullFirstThenNonNull() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);

        List<Object> batch = new ArrayList<>();
        batch.add(null);
        batch.add(7);
        q.addBatchParameters(batch.iterator());

        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement).setInt(1, 7);
        verify(mockPreparedStatement, times(2)).addBatch();
        // Once by addNullBatchParameter for the null row, once by the non-null continuation.
        verify(mockPreparedStatement, times(2)).clearParameters();
    }

    @Test
    void testAddBatchParameters_NullFirstThenStructuredRows() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);

        final Map<String, Object> mapRow = Map.of("param1", "map-value");
        final TestEntity beanRow = new TestEntity();
        beanRow.setParam1("bean-value");

        final List<Object> batch = new ArrayList<>();
        batch.add(null);
        batch.add(mapRow);
        batch.add(beanRow);

        q.addBatchParameters(batch.iterator());

        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement).setString(1, "map-value");
        verify(mockPreparedStatement).setString(1, "bean-value");
        verify(mockPreparedStatement, times(3)).clearParameters();
        verify(mockPreparedStatement, times(3)).addBatch();
    }

    @Test
    void testAddBatchParameters_Iterator_Empty() throws SQLException {
        NamedQuery result = namedQuery.addBatchParameters(new ArrayList<>().iterator());
        assertSame(namedQuery, result);
        verify(mockPreparedStatement, never()).addBatch();
    }

    @Test
    void testAddBatchParameters_Iterator_NullFirst_NullSubsequent() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        // first is null (paramCount==1 → ok), second is also null → hits L4267-4268
        q.addBatchParameters(Arrays.asList(null, null).iterator());
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParameters_Iterator_MapThenNullSingleParameter() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);

        q.addBatchParameters(Arrays.asList(Map.of("id", 1), null).iterator());

        verify(mockPreparedStatement).setInt(1, 1);
        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParameters_Iterator_NullFirst_NonNullSubsequent() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);

        NamedQuery result = q.addBatchParameters(Arrays.asList(null, 42).iterator());

        assertSame(q, result);
        verify(mockPreparedStatement).setObject(1, null);
        // The non-null follower binds through the Abacus type system, so an Integer is dispatched to setInt(1, 42).
        verify(mockPreparedStatement).setInt(1, 42);
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParameters_Iterator_BeanThenNull() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);

        TestEntity e1 = new TestEntity();
        e1.setParam1("v1");

        NamedQuery result = q.addBatchParameters(Arrays.asList(e1, null).iterator());

        assertSame(q, result);
        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParameters_Iterator_CollectionThenNull() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);

        List<Object> batch = new ArrayList<>();
        batch.add(Arrays.asList(7));
        batch.add(null);

        NamedQuery result = q.addBatchParameters(batch.iterator());

        assertSame(q, result);
        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParameters_Iterator_ArrayThenNull() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);

        List<Object> batch = new ArrayList<>();
        batch.add(new Object[] { 7 });
        batch.add(null);

        NamedQuery result = q.addBatchParameters(batch.iterator());

        assertSame(q, result);
        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParameters_Iterator_EntityIdThenNull() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);

        List<Object> batch = new ArrayList<>();
        batch.add(com.landawn.abacus.util.EntityId.of("param1", "v1"));
        batch.add(null);

        NamedQuery result = q.addBatchParameters(batch.iterator());

        assertSame(q, result);
        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void testAddBatchParameters_NullFirst_MultiParam_Throws() throws SQLException {
        // paramCount=2 but first element is null → IAE (L4260)
        List<Object> batch = new ArrayList<>();
        batch.add(null);
        assertThrows(IllegalArgumentException.class, () -> namedQuery.addBatchParameters(batch.iterator()));
    }

    @Test
    void testAddBatchParameters_UnsupportedType_Throws() throws SQLException {
        // Multi-param query, first element is not a bean/map/collection/array/EntityId → throws (L4366)
        List<Object> batch = new ArrayList<>();
        batch.add(42); // Integer, not a bean; paramCount=2
        assertThrows(IllegalArgumentException.class, () -> namedQuery.addBatchParameters(batch.iterator()));
    }

    @Test
    void testAddBatchParameters_Iterator_BeanMissingProp_Throws() throws SQLException {
        BeanNoProps bean = new BeanNoProps();
        bean.setFoo("x");
        assertThrows(IllegalArgumentException.class, () -> namedQuery.addBatchParameters(Arrays.asList(bean).iterator()));
    }

    @Test
    void testSetParameters_Object_SingleParameterBeanAndRecordStillBindProperty() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        TestEntity entity = new TestEntity();
        entity.setParam1("fromBean");

        new NamedQuery(mockPreparedStatement, mockParsedSql).setParameters(entity);
        new NamedQuery(mockPreparedStatement, mockParsedSql).setParameters(new SingleParamRecord("fromRecord"));

        verify(mockPreparedStatement).setString(1, "fromBean");
        verify(mockPreparedStatement).setString(1, "fromRecord");
    }

    @Test
    void testSetParameters_Object_SingleValueTypedBeanWithMatchingPropertyStillBoundAsBean() throws SQLException {
        final RegisteredMoney money = new RegisteredMoney();
        money.setAmount("10");
        money.setCurrency("USD");

        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("amount"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        new NamedQuery(mockPreparedStatement, mockParsedSql).setParameters(money);
        new NamedQuery(mockPreparedStatement, mockParsedSql).addBatchParameters(Arrays.asList(money));
        verify(mockPreparedStatement, times(2)).setString(1, "10");

        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("price"));
        new NamedQuery(mockPreparedStatement, mockParsedSql).setParameters(money);
        verify(mockPreparedStatement).setString(1, "10 USD");
        verify(mockPreparedStatement, never()).close();
    }

    // Registering a scalar Type must not make a valid nested name bind the whole root for a single placeholder.
    // A null intermediate still binds through the leaf's declared type, and classification must not call its getter.
    @ParameterizedTest(name = "path={0}, null address={1}")
    @CsvSource({ "address.street, false", "address.street, true", "address.zip, false", "address.zip, true" })
    void customTypeBean_singleNestedPlaceholderBindsLeaf(final String path, final boolean nullAddress) throws SQLException {
        final RegisteredMoney money = moneyWithAddress("10", nullAddress ? null : "Main");
        final ParsedSql sql = ParsedSql.parse("SELECT :" + path);

        new NamedQuery(mockPreparedStatement, sql).setParameters(money);
        new NamedQuery(mockPreparedStatement, sql).addBatchParameters(List.of(money));

        if (path.equals("address.street")) {
            verify(mockPreparedStatement, times(2)).setString(1, nullAddress ? null : "Main");
        } else if (nullAddress) {
            verify(mockPreparedStatement, times(2)).setNull(1, Types.INTEGER);
        } else {
            verify(mockPreparedStatement, times(2)).setInt(1, 12345);
        }
        assertEquals(2, money.addressReads);
        verify(mockPreparedStatement).addBatch();
        verify(mockPreparedStatement, never()).setString(1, "10 USD");
        verify(mockPreparedStatement, never()).close();
    }

    @ParameterizedTest(name = "leading null row={0}")
    @ValueSource(booleans = { false, true })
    void customTypeBean_batchNestedPathsHandleMultipleAndNullRows(final boolean leadingNull) throws SQLException {
        final RegisteredMoney first = moneyWithAddress("10", "Main");
        final RegisteredMoney withoutAddress = moneyWithAddress("20", null);
        final RegisteredMoney last = moneyWithAddress("30", "Second");
        final List<RegisteredMoney> rows = new ArrayList<>(Arrays.asList(first, withoutAddress, null, last));
        if (leadingNull) {
            rows.add(0, null);
        }

        new NamedQuery(mockPreparedStatement, ParsedSql.parse("INSERT INTO streets(name) VALUES (:address.street)")).addBatchParameters(rows.iterator());

        verify(mockPreparedStatement).setString(1, "Main");
        verify(mockPreparedStatement).setString(1, null);
        verify(mockPreparedStatement).setString(1, "Second");
        verify(mockPreparedStatement, times(leadingNull ? 2 : 1)).setObject(1, null);
        verify(mockPreparedStatement, times(rows.size())).addBatch();
        assertEquals(1, first.addressReads);
        assertEquals(1, withoutAddress.addressReads);
        assertEquals(1, last.addressReads);
        verify(mockPreparedStatement, never()).close();
    }

    @ParameterizedTest
    @ValueSource(strings = { "price", "address.country", "missing.street", "addresses.street" })
    void customTypeBean_unresolvedNamesKeepWholeValueBinding(final String name) throws SQLException {
        final RegisteredMoney money = moneyWithAddress("10", "Main");
        final ParsedSql sql = ParsedSql.parse("SELECT :" + name);

        new NamedQuery(mockPreparedStatement, sql).setParameters(money);
        new NamedQuery(mockPreparedStatement, sql).addBatchParameters(List.of(money));

        verify(mockPreparedStatement, times(2)).setString(1, "10 USD");
        assertEquals(0, money.addressReads);
        verify(mockPreparedStatement).addBatch();
        verify(mockPreparedStatement, never()).close();
    }

    @Test
    void customTypeBean_mappedPropertyPrecedesNestedPath() throws SQLException {
        final RegisteredMoneyWithStreet money = new RegisteredMoneyWithStreet();
        money.setAmount("10");
        money.setCurrency("USD");
        final NestedAddress address = new NestedAddress();
        address.setStreet("Nested");
        money.setAddress(address);
        money.setDirectStreet("Direct");
        final ParsedSql sql = ParsedSql.parse("SELECT :address.street");

        new NamedQuery(mockPreparedStatement, sql).setParameters(money);
        new NamedQuery(mockPreparedStatement, sql).addBatchParameters(List.of(money));

        verify(mockPreparedStatement, times(2)).setString(1, "Direct");
        assertEquals(0, ((RegisteredMoney) money).addressReads);
        verify(mockPreparedStatement, never()).setString(1, "Nested");
    }

    @Test
    void customTypeBean_explicitSetObjectKeepsWholeValueBinding() throws SQLException {
        final RegisteredMoney money = moneyWithAddress("10", "Main");
        final ParsedSql sql = ParsedSql.parse("SELECT :address.street");

        new NamedQuery(mockPreparedStatement, sql).setObject(1, money);
        new NamedQuery(mockPreparedStatement, sql).setObject("address.street", money);

        verify(mockPreparedStatement, times(2)).setString(1, "10 USD");
        assertEquals(0, money.addressReads);
    }

    @BeforeAll
    static void registerMoneyTypes() {
        TypeFactory.registerType(RegisteredMoney.class, m -> m.getAmount() + " " + m.getCurrency(), str -> {
            final RegisteredMoney m = new RegisteredMoney();
            m.setAmount(str.substring(0, str.indexOf(' ')));
            m.setCurrency(str.substring(str.indexOf(' ') + 1));
            return m;
        });
        assertFalse(N.typeOf(RegisteredMoney.class).isBean());
        TypeFactory.registerType(RegisteredMoneyWithStreet.class, m -> m.getAmount() + " " + m.getCurrency(), str -> {
            final RegisteredMoneyWithStreet m = new RegisteredMoneyWithStreet();
            m.setAmount(str.substring(0, str.indexOf(' ')));
            m.setCurrency(str.substring(str.indexOf(' ') + 1));
            return m;
        });
        assertFalse(N.typeOf(RegisteredMoneyWithStreet.class).isBean());
    }

    private static RegisteredMoney moneyWithAddress(final String amount, final String street) {
        final RegisteredMoney money = new RegisteredMoney();
        money.setAmount(amount);
        money.setCurrency("USD");
        if (street != null) {
            final NestedAddress address = new NestedAddress();
            address.setStreet(street);
            address.setZip(12345);
            money.setAddress(address);
            money.setAddresses(List.of(address));
        }
        return money;
    }

    @Test
    void testSetParametersMapBindsEachRepeatedPlaceholderOnce() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1", "param1"));
        when(mockParsedSql.parameterCount()).thenReturn(2);
        final NamedQuery queryWithRepeatedName = new NamedQuery(mockPreparedStatement, mockParsedSql);

        queryWithRepeatedName.setParameters(Map.of("param1", "value"));

        verify(mockPreparedStatement).setString(1, "value");
        verify(mockPreparedStatement).setString(2, "value");
    }

    // A non-idempotent getter must still bind one value to every occurrence of its name. Both name-index strategies
    // (short list scan, long list map) are covered.
    @Test
    void setParameters_beanReadsEachRepeatedNameOnce() throws SQLException {
        final List<String> shortNames = List.of("p", "q", "p", "p");
        final List<String> longNames = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            longNames.add(i % 3 == 0 ? "p" : "q");
        }

        for (final List<String> names : List.of(shortNames, longNames)) {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.copyOf(names));
            when(mockParsedSql.parameterCount()).thenReturn(names.size());
            final CountingBean bean = new CountingBean();

            new NamedQuery(stmt, mockParsedSql).setParameters(bean);

            assertEquals(1, bean.pReads);
            assertEquals(1, bean.qReads);
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equals("p")) {
                    verify(stmt).setString(i + 1, "p1");
                } else if (names.get(i).equals("q")) {
                    verify(stmt).setString(i + 1, "q1");
                }
            }
            verify(stmt, never()).setString(anyInt(), eq("p2"));
            verify(stmt, never()).close();
        }
    }

    @Test
    void addBatchParameters_beanRowsReadEachRepeatedNameOncePerRow() throws SQLException {
        final NamedQuery query = createQueryWithParameters(ImmutableList.of("p", "q", "p"));
        final CountingBean first = new CountingBean();
        final CountingBean second = new CountingBean();
        second.prefix = "r";

        query.addBatchParameters(List.of(first, second));

        assertEquals(1, first.pReads);
        assertEquals(1, second.pReads);
        verify(mockPreparedStatement).setString(1, "p1");
        verify(mockPreparedStatement).setString(3, "p1");
        verify(mockPreparedStatement).setString(1, "r1");
        verify(mockPreparedStatement).setString(3, "r1");
        verify(mockPreparedStatement, never()).setString(anyInt(), eq("p2"));
        verify(mockPreparedStatement, never()).setString(anyInt(), eq("r2"));
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    void setParameters_beanBindsNestedPropertyPaths() throws SQLException {
        final NamedQuery query = new NamedQuery(mockPreparedStatement,
                ParsedSql.parse("UPDATE users SET street = :address.street, zip = :address.zip WHERE id = :id OR street = :address.street"));

        query.setParameters(NestedUser.of(7L, "Main", 12345));

        verify(mockPreparedStatement).setString(1, "Main");
        verify(mockPreparedStatement).setInt(2, 12345);
        verify(mockPreparedStatement).setLong(3, 7L);
        verify(mockPreparedStatement).setString(4, "Main");
        verify(mockPreparedStatement, never()).close();
    }

    @Test
    void setParameters_nullIntermediateBindsSqlNullOfLeafType() throws SQLException {
        final NamedQuery query = new NamedQuery(mockPreparedStatement,
                ParsedSql.parse("UPDATE users SET street = :address.street, zip = :address.zip WHERE id = :id"));
        final NestedUser user = NestedUser.of(7L, "Main", 12345);
        user.setAddress(null);

        query.setParameters(user);

        verify(mockPreparedStatement).setString(1, null);
        verify(mockPreparedStatement).setNull(2, Types.INTEGER);
        verify(mockPreparedStatement).setLong(3, 7L);
        verify(mockPreparedStatement, never()).close();
    }

    @Test
    void setParameters_unresolvableNestedPathIsRejectedAndClosesQuery() throws SQLException {
        for (final String name : List.of("address.country", "nothing.street", "addresses.street")) {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("SELECT * FROM users WHERE id = :id AND street = :" + name));

            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> query.setParameters(NestedUser.of(7L, "Main", 1)));

            assertTrue(failure.getMessage().contains("No property found with name: " + name), failure.getMessage());
            verify(stmt).close();
            verify(stmt, never()).setLong(anyInt(), anyLong());
        }
    }

    @Test
    void setParametersWithNames_bindsNestedPropertyPath() throws SQLException {
        final NamedQuery query = new NamedQuery(mockPreparedStatement,
                ParsedSql.parse("UPDATE users SET street = :address.street WHERE id = :id AND street <> :address.street"));

        query.setParameters(NestedUser.of(7L, "Main", 1), List.of("address.street")).setLong("id", 7L);

        verify(mockPreparedStatement).setString(1, "Main");
        verify(mockPreparedStatement).setString(3, "Main");
        verify(mockPreparedStatement).setLong(2, 7L);

        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery rejecting = new NamedQuery(stmt, ParsedSql.parse("UPDATE users SET street = :address.street WHERE id = :id"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> rejecting.setParameters(NestedUser.of(7L, "Main", 1), List.of("address.country")))
                .getMessage()
                .contains("No property found with name: address.country"));
        verify(stmt).close();
    }

    @Test
    void addBatchParameters_beanRowsBindNestedPropertyPaths() throws SQLException {
        final NamedQuery query = new NamedQuery(mockPreparedStatement, ParsedSql.parse("UPDATE users SET street = :address.street WHERE id = :id"));
        final NestedUser withoutAddress = NestedUser.of(8L, "ignored", 1);
        withoutAddress.setAddress(null);

        query.addBatchParameters(List.of(NestedUser.of(7L, "Main", 1), withoutAddress));

        verify(mockPreparedStatement).setString(1, "Main");
        verify(mockPreparedStatement).setLong(2, 7L);
        verify(mockPreparedStatement).setString(1, null);
        verify(mockPreparedStatement).setLong(2, 8L);
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    // A record root resolves nested paths like a bean: its components are its properties.
    @Test
    void setParameters_recordRootBindsNestedPropertyPaths() throws SQLException {
        final NamedQuery query = new NamedQuery(mockPreparedStatement,
                ParsedSql.parse("UPDATE users SET street = :address.street, zip = :address.zip WHERE id = :id OR street = :address.street"));
        final NestedAddress address = new NestedAddress();
        address.setStreet("Main");
        address.setZip(12345);

        query.setParameters(new RecordUser(7L, address));

        verify(mockPreparedStatement).setString(1, "Main");
        verify(mockPreparedStatement).setInt(2, 12345);
        verify(mockPreparedStatement).setLong(3, 7L);
        verify(mockPreparedStatement).setString(4, "Main");
        verify(mockPreparedStatement, never()).close();
    }

    // A record intermediate (a record-typed property of a bean, or a record component of a record) is read through its
    // accessors; a null record intermediate binds SQL NULL through the leaf's type.
    @Test
    void setParameters_recordIntermediateBindsNestedPropertyPaths() throws SQLException {
        final NamedQuery query = new NamedQuery(mockPreparedStatement, ParsedSql.parse("UPDATE users SET city = :home.city, zip = :home.zip WHERE id = :id"));
        final BeanWithRecordAddress bean = new BeanWithRecordAddress();
        bean.setId(7L);
        bean.setHome(new RecordAddress("Berlin", 10115));

        query.setParameters(bean);

        verify(mockPreparedStatement).setString(1, "Berlin");
        verify(mockPreparedStatement).setInt(2, 10115);
        verify(mockPreparedStatement).setLong(3, 7L);

        final PreparedStatement recordStmt = mock(PreparedStatement.class);
        final NamedQuery recordQuery = new NamedQuery(recordStmt, ParsedSql.parse("UPDATE users SET city = :home.city, zip = :home.zip WHERE id = :id"));

        recordQuery.setParameters(new RecordUserWithRecordAddress(8L, new RecordAddress("Paris", 75001)));

        verify(recordStmt).setString(1, "Paris");
        verify(recordStmt).setInt(2, 75001);
        verify(recordStmt).setLong(3, 8L);

        final PreparedStatement nullStmt = mock(PreparedStatement.class);
        final NamedQuery nullQuery = new NamedQuery(nullStmt, ParsedSql.parse("UPDATE users SET city = :home.city, zip = :home.zip WHERE id = :id"));

        nullQuery.setParameters(new RecordUserWithRecordAddress(9L, null));

        verify(nullStmt).setString(1, null);
        verify(nullStmt).setNull(2, Types.INTEGER);
        verify(nullStmt).setLong(3, 9L);
        verify(nullStmt, never()).close();

        // A missing component of the record intermediate is unresolvable, like a missing bean property.
        final PreparedStatement rejecting = mock(PreparedStatement.class);
        final NamedQuery rejectingQuery = new NamedQuery(rejecting, ParsedSql.parse("UPDATE users SET c = :home.country WHERE id = :id"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> rejectingQuery.setParameters(new RecordUserWithRecordAddress(8L, new RecordAddress("Paris", 1)))).getMessage()
                        .contains("No property found with name: home.country"));
        verify(rejecting).close();
    }

    // Documented precedence: "A name that is itself a property of the bean takes precedence" over reading it as a nested
    // path. Here the dotted name resolves both ways: as the column name of the bean's own 'street' property and as the
    // path address -> street; the bean's own property wins at every occurrence.
    @Test
    void setParameters_nameThatIsAPropertyOfTheBeanTakesPrecedenceOverTheNestedPath() throws SQLException {
        final NamedQuery query = new NamedQuery(mockPreparedStatement,
                ParsedSql.parse("UPDATE users SET street = :address.street WHERE id = :id OR street = :address.street"));
        final UserWithStreetColumn user = new UserWithStreetColumn();
        user.setId(7L);
        user.setStreet("Direct");
        final NestedAddress address = new NestedAddress();
        address.setStreet("Nested");
        user.setAddress(address);

        query.setParameters(user);

        verify(mockPreparedStatement).setString(1, "Direct");
        verify(mockPreparedStatement).setLong(2, 7L);
        verify(mockPreparedStatement).setString(3, "Direct");
        verify(mockPreparedStatement, never()).setString(anyInt(), eq("Nested"));

        // The same rule for setParameters(entity, names) and batch rows.
        final PreparedStatement namesStmt = mock(PreparedStatement.class);
        new NamedQuery(namesStmt, ParsedSql.parse("UPDATE users SET street = :address.street WHERE id = :id")).setParameters(user, List.of("address.street"))
                .setLong("id", 7L);
        verify(namesStmt).setString(1, "Direct");

        final PreparedStatement batchStmt = mock(PreparedStatement.class);
        new NamedQuery(batchStmt, ParsedSql.parse("UPDATE users SET street = :address.street WHERE id = :id")).addBatchParameters(List.of(user));
        verify(batchStmt).setString(1, "Direct");
        verify(batchStmt).setLong(2, 7L);
        verify(batchStmt).addBatch();

        // Without a property of its own for the name, the same SQL reads the nested path.
        final PreparedStatement nestedStmt = mock(PreparedStatement.class);
        new NamedQuery(nestedStmt, ParsedSql.parse("UPDATE users SET street = :address.street WHERE id = :id")).setParameters(NestedUser.of(7L, "Nested", 1));
        verify(nestedStmt).setString(1, "Nested");
    }

    // 32 placeholders or more are indexed with a map (JdbcUtil.firstIndexesOfRepeatedNames): each distinct name is still
    // read once per bean and every occurrence binds that value.
    @Test
    void setParameters_beanWithManyPlaceholdersReadsEachRepeatedNameOnce() throws SQLException {
        final StringBuilder sql = new StringBuilder("SELECT * FROM t WHERE a = :p");
        for (int i = 1; i < 40; i++) {
            sql.append(i % 9 == 0 ? " AND a = :p" : " AND b = :q");
        }
        final NamedQuery query = new NamedQuery(mockPreparedStatement, ParsedSql.parse(sql.toString()));
        final CountingBean bean = new CountingBean();

        query.setParameters(bean);

        assertEquals(1, bean.pReads);
        assertEquals(1, bean.qReads);
        for (int i = 0; i < 40; i++) {
            verify(mockPreparedStatement).setString(i + 1, i % 9 == 0 ? "p1" : "q1");
        }

        // A second bean (a batch row) reads its own values once more.
        query.addBatchParameters(List.of(new CountingBean()));
        verify(mockPreparedStatement, times(2)).setString(1, "p1");
        verify(mockPreparedStatement, times(2)).setString(2, "q1");
        verify(mockPreparedStatement).addBatch();
    }

    public record RecordUser(long id, NestedAddress address) {
    }

    public record RecordAddress(String city, int zip) {
    }

    public record RecordUserWithRecordAddress(long id, RecordAddress home) {
    }

    public static class BeanWithRecordAddress {
        private long id;
        private RecordAddress home;

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }

        public RecordAddress getHome() {
            return home;
        }

        public void setHome(final RecordAddress home) {
            this.home = home;
        }
    }

    /** Its own 'street' property is also reachable as "address.street" (its column name), like the nested path. */
    public static class UserWithStreetColumn {
        private long id;
        private NestedAddress address;
        @Column("address.street")
        private String street;

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }

        public NestedAddress getAddress() {
            return address;
        }

        public void setAddress(final NestedAddress address) {
            this.address = address;
        }

        public String getStreet() {
            return street;
        }

        public void setStreet(final String street) {
            this.street = street;
        }
    }

    private static class BeanWithP {
        private String p;

        public String getP() {
            return p;
        }

        public void setP(String p) {
            this.p = p;
        }
    }

    private static class BeanNoProps {
        private String foo;

        public String getFoo() {
            return foo;
        }

        public void setFoo(String v) {
            this.foo = v;
        }
    }

    private static class TestEntity {
        private String param1;
        private int param2;

        public String getParam1() {
            return param1;
        }

        public void setParam1(String param1) {
            this.param1 = param1;
        }

        public int getParam2() {
            return param2;
        }

        public void setParam2(int param2) {
            this.param2 = param2;
        }
    }

    public record SingleParamRecord(String param1) {
    }

    public static class RegisteredMoney {
        private String amount;
        private String currency;
        // No field named "address": reading that property must invoke its getter, so duplicate reads are observable.
        private NestedAddress shippingAddress;
        private List<NestedAddress> addresses;
        private int addressReads;

        public String getAmount() {
            return amount;
        }

        public void setAmount(final String amount) {
            this.amount = amount;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(final String currency) {
            this.currency = currency;
        }

        public NestedAddress getAddress() {
            addressReads++;
            return shippingAddress;
        }

        public void setAddress(final NestedAddress address) {
            shippingAddress = address;
        }

        public List<NestedAddress> getAddresses() {
            return addresses;
        }

        public void setAddresses(final List<NestedAddress> addresses) {
            this.addresses = addresses;
        }
    }

    public static class RegisteredMoneyWithStreet extends RegisteredMoney {
        @Column("address.street")
        private String directStreet;

        public String getDirectStreet() {
            return directStreet;
        }

        public void setDirectStreet(final String directStreet) {
            this.directStreet = directStreet;
        }
    }

    /** Each getter call returns a new value, so a second read of a name would be visible. */
    public static class CountingBean {
        private String prefix = "p";
        private int pReads;
        private int qReads;

        public String getP() {
            return prefix + (++pReads);
        }

        public void setP(final String p) {
            // read-only for the test
        }

        public String getQ() {
            return "q" + (++qReads);
        }

        public void setQ(final String q) {
            // read-only for the test
        }
    }

    public static class NestedAddress {
        private String street;
        private int zip;

        public String getStreet() {
            return street;
        }

        public void setStreet(final String street) {
            this.street = street;
        }

        public int getZip() {
            return zip;
        }

        public void setZip(final int zip) {
            this.zip = zip;
        }
    }

    public static class NestedUser {
        private long id;
        private NestedAddress address;
        private List<NestedAddress> addresses;

        static NestedUser of(final long id, final String street, final int zip) {
            final NestedAddress address = new NestedAddress();
            address.setStreet(street);
            address.setZip(zip);
            final NestedUser user = new NestedUser();
            user.setId(id);
            user.setAddress(address);
            user.setAddresses(List.of(address));
            return user;
        }

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }

        public NestedAddress getAddress() {
            return address;
        }

        public void setAddress(final NestedAddress address) {
            this.address = address;
        }

        public List<NestedAddress> getAddresses() {
            return addresses;
        }

        public void setAddresses(final List<NestedAddress> addresses) {
            this.addresses = addresses;
        }
    }
}
