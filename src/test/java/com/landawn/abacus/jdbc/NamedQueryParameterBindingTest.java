package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
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

import org.junit.jupiter.api.Test;

import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.ImmutableList;

class NamedQueryParameterBindingTest extends NamedQueryTestSupport {

    @Test
    public void testSetNullWithSqlType() throws SQLException {
        String paramName = "param1";
        int sqlType = Types.INTEGER;

        NamedQuery result = namedQuery.setNull(paramName, sqlType);

        verify(mockPreparedStatement).setNull(1, sqlType);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNullWithSqlTypeNotFound() throws SQLException {
        String paramName = "nonExistent";
        int sqlType = Types.INTEGER;

        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setNull(paramName, sqlType);
        });
    }

    @Test
    public void testSetNullWithSqlTypeAndTypeName() throws SQLException {
        String paramName = "param1";
        int sqlType = Types.STRUCT;
        String typeName = "MY_TYPE";

        NamedQuery result = namedQuery.setNull(paramName, sqlType, typeName);

        verify(mockPreparedStatement).setNull(1, sqlType, typeName);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNull_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setNull("a", Types.INTEGER);
        verify(mockPreparedStatement).setNull(1, Types.INTEGER);
        assertSame(q, result);
    }

    @Test
    public void testSetNullWithTypeName_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setNull("b", Types.STRUCT, "MY_TYPE");
        verify(mockPreparedStatement).setNull(2, Types.STRUCT, "MY_TYPE");
        assertSame(q, result);
    }

    @Test
    public void testSetNull_MapPath_NotFound() throws Exception {
        NamedQuery q = createManyParamQuery();
        assertThrows(IllegalArgumentException.class, () -> q.setNull("unknown", Types.INTEGER));
    }

    // Test quad duplicate params in index-by-map path (size>=4, uses for loop)
    @Test
    public void testSetNull_MapPath_QuadDuplicateParam() throws Exception {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "a", "a", "a", "b"));
        when(mockParsedSql.parameterCount()).thenReturn(5);
        when(mockParsedSql.originalSql()).thenReturn("SELECT 1");
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        q.setNull("a", Types.VARCHAR);
        verify(mockPreparedStatement, times(4)).setNull(anyInt(), eq(Types.VARCHAR));
    }

    @Test
    public void testSetNull_MapBranch_ParamNotFound() throws SQLException {
        // paramCount >= 5, param not in map → throws
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNull("unknown", Types.INTEGER));
    }

    @Test
    public void testSetNull_MapBranch_TwoOccurrences() throws SQLException {
        // paramCount >= 5, same param twice → lines 173-174
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNull("p", Types.INTEGER);
        verify(mockPreparedStatement).setNull(4, Types.INTEGER);
        verify(mockPreparedStatement).setNull(5, Types.INTEGER);
    }

    @Test
    public void testSetNull_MapBranch_ThreeOccurrences() throws SQLException {
        // paramCount >= 5, same param three times → lines 176-178
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNull("p", Types.INTEGER);
        verify(mockPreparedStatement, times(3)).setNull(anyInt(), eq(Types.INTEGER));
    }

    @Test
    public void testSetNull_MapBranch_FourOccurrences() throws SQLException {
        // paramCount >= 5, same param four times → for loop (line 180-182)
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNull("p", Types.INTEGER);
        verify(mockPreparedStatement, times(4)).setNull(anyInt(), eq(Types.INTEGER));
    }

    @Test
    public void testSetNullTypeName_LoopBranch_ParamNotFound() throws SQLException {
        // paramCount < 5, typeName overload, param not found → lines 221-222
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setNull("unknown", Types.STRUCT, "MY_TYPE"));
    }

    @Test
    public void testSetNullTypeName_MapBranch_ParamNotFound() throws SQLException {
        // paramCount >= 5, typeName overload, param not found → lines 232-233
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNull("unknown", Types.STRUCT, "MY_TYPE"));
    }

    @Test
    public void testSetNullTypeName_MapBranch_TwoOccurrences() throws SQLException {
        // paramCount >= 5, typeName overload, 2 occurrences → lines 238-239
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNull("p", Types.STRUCT, "MY_TYPE");
        verify(mockPreparedStatement).setNull(4, Types.STRUCT, "MY_TYPE");
        verify(mockPreparedStatement).setNull(5, Types.STRUCT, "MY_TYPE");
    }

    @Test
    public void testSetNullTypeName_MapBranch_ThreeOccurrences() throws SQLException {
        // paramCount >= 5, typeName overload, 3 occurrences → lines 241-244
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNull("p", Types.STRUCT, "MY_TYPE");
        verify(mockPreparedStatement, times(3)).setNull(anyInt(), eq(Types.STRUCT), eq("MY_TYPE"));
    }

    // --- setNull(typeName) map branch: 4+ occurrences (L246 for-loop) ---

    @Test
    public void testSetNullTypeName_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNull("p", Types.STRUCT, "MY_TYPE");
        verify(mockPreparedStatement, times(4)).setNull(anyInt(), eq(Types.STRUCT), eq("MY_TYPE"));
    }

    // setNullTypeName map branch - 1 occurrence (L235-236)
    @Test
    public void testSetNullTypeName_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "p"));
        q.setNull("p", Types.STRUCT, "MY_TYPE");
        verify(mockPreparedStatement).setNull(5, Types.STRUCT, "MY_TYPE");
    }

    @Test
    public void testSetParametersMap() throws SQLException {
        Map<String, Object> params = new HashMap<>();
        params.put("param1", "value1");
        params.put("param2", 123);

        NamedQuery result = namedQuery.setParameters(params);

        verify(mockPreparedStatement).setString(1, "value1");
        verify(mockPreparedStatement).setInt(2, 123);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetParametersMapBindsEachRepeatedPlaceholderOnce() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1", "param1"));
        when(mockParsedSql.parameterCount()).thenReturn(2);
        final NamedQuery queryWithRepeatedName = new NamedQuery(mockPreparedStatement, mockParsedSql);

        queryWithRepeatedName.setParameters(Map.of("param1", "value"));

        verify(mockPreparedStatement).setString(1, "value");
        verify(mockPreparedStatement).setString(2, "value");
    }

    @Test
    public void testSetParametersObject() throws SQLException {
        TestEntity entity = new TestEntity();
        entity.setParam1("value1");
        entity.setParam2(123);

        NamedQuery result = namedQuery.setParameters(entity);

        // Would need proper mock setup for entity parsing
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetParametersEntityWithNames() throws SQLException {
        TestEntity entity = new TestEntity();
        entity.setParam1("value1");
        entity.setParam2(123);

        Collection<String> paramNames = Arrays.asList("param1");

        NamedQuery result = namedQuery.setParameters(entity, paramNames);

        // Would need proper mock setup for entity parsing
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetParametersWithSetter() throws SQLException {
        String param = "test";
        Jdbc.TriParametersSetter<NamedQuery, String> setter = (sql, query, p) -> {
            query.setString("param1", p);
        };

        NamedQuery result = namedQuery.setParameters(param, setter);

        verify(mockPreparedStatement).setString(1, "test");
        assertSame(namedQuery, result);
    }

    // --- setParameters(Map) happy path ---

    @Test
    public void testSetParameters_Map_SetsMatchingParams() throws SQLException {
        // namedQuery has "param1" at index 1 and "param2" at index 2 (loop branch, paramCount=2)
        // null values route through stmt.setObject directly
        Map<String, Object> params = new HashMap<>();
        params.put("param1", null);
        params.put("param2", null);
        params.put("z", "ignored"); // not in query — should be ignored
        namedQuery.setParameters(params);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), isNull());
    }

    // --- setParameters(Object): various uncovered branches (L3955-3994) ---

    @Test
    public void testSetParameters_Object_Collection() throws SQLException {
        // Collection branch → delegates to setParameters(Collection)
        NamedQuery result = namedQuery.setParameters(Arrays.asList("value1", 123));
        assertSame(namedQuery, result);
        verify(mockPreparedStatement).setString(eq(1), eq("value1"));
        verify(mockPreparedStatement).setInt(eq(2), eq(123));
    }

    @Test
    public void testSetParameters_Object_Array() throws SQLException {
        // Object[] branch → delegates to setParameters(Object[])
        NamedQuery result = namedQuery.setParameters(new Object[] { "value1", 123 });
        assertSame(namedQuery, result);
        verify(mockPreparedStatement).setString(eq(1), eq("value1"));
        verify(mockPreparedStatement).setInt(eq(2), eq(123));
    }

    @Test
    public void testSetParameters_Object_EntityId() throws SQLException {
        // EntityId branch (L3984)
        final com.landawn.abacus.util.EntityId eid = com.landawn.abacus.util.EntityId.of("param1", "v1");
        NamedQuery result = namedQuery.setParameters((Object) eid);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetParameters_Object_SingleValue() throws SQLException {
        // Single parameter query, unsupported type → falls into parameterCount==1 branch (L3987)
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        NamedQuery result = q.setParameters(42);
        assertSame(q, result);
        verify(mockPreparedStatement).setInt(1, 42);
    }

    @Test
    public void testSetParameters_Object_SingleValueCalendarIsNotBoundAsBean() throws SQLException {
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
    public void testSetParameters_Object_SingleParameterBeanAndRecordStillBindProperty() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        TestEntity entity = new TestEntity();
        entity.setParam1("fromBean");

        new NamedQuery(mockPreparedStatement, mockParsedSql).setParameters(entity);
        new NamedQuery(mockPreparedStatement, mockParsedSql).setParameters(new SingleParamRecord("fromRecord"));

        verify(mockPreparedStatement).setString(1, "fromBean");
        verify(mockPreparedStatement).setString(1, "fromRecord");
    }

    // A bean class bound as ONE value by the Abacus type system (here: a registered string Type) keeps its bean
    // binding when it DOES have a property named after the single placeholder; only the case that used to fail
    // ("No property found") falls back to single-value binding through the registered Type.
    @Test
    public void testSetParameters_Object_SingleValueTypedBeanWithMatchingPropertyStillBoundAsBean() throws SQLException {
        TypeFactory.registerType(RegisteredMoney.class, m -> m.getAmount() + " " + m.getCurrency(), str -> {
            final RegisteredMoney m = new RegisteredMoney();
            m.setAmount(str.substring(0, str.indexOf(' ')));
            m.setCurrency(str.substring(str.indexOf(' ') + 1));
            return m;
        });
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

    @Test
    public void testSetParameters_Object_UnsupportedType() throws SQLException {
        // Multi-param query, unsupported non-bean type → throws (L3993-3994)
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setParameters(42));
    }

    // --- setParameters(Object, Collection<String>): uncovered paths (L4050-4085) ---

    @Test
    public void testSetParameters_EntityWithNames_PropNotFound() throws SQLException {
        // Bean class but the requested property doesn't exist → throws (L4050)
        TestEntity entity = new TestEntity();
        entity.setParam1("x");
        entity.setParam2(1);
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setParameters(entity, Arrays.asList("nonExistentProp")));
    }

    @Test
    public void testSetParameters_EntityWithNames_ParamNotInQuery() throws SQLException {
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
    public void testSetParameters_EntityWithNames_NonBeanType() throws SQLException {
        // Non-bean entity → throws (L4083-4085)
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setParameters("notABean", Arrays.asList("param1")));
    }

    // --- setParameters(entity, Collection): 2 occurrences of same param (L4065-4066) ---

    @Test
    public void testSetParameters_Entity_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        BeanWithP bean = new BeanWithP();
        bean.setP("val");
        NamedQuery result = q.setParameters(bean, Arrays.asList("p"));
        assertSame(q, result);
    }

    // --- setParameters(entity, Collection): 3 occurrences of same param (L4068-4070) ---

    @Test
    public void testSetParameters_Entity_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        BeanWithP bean = new BeanWithP();
        bean.setP("val");
        NamedQuery result = q.setParameters(bean, Arrays.asList("p"));
        assertSame(q, result);
    }

    // --- setParameters(entity, Collection): 4+ occurrences (L4072-4073) ---

    @Test
    public void testSetParameters_Entity_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        BeanWithP bean = new BeanWithP();
        bean.setP("val");
        NamedQuery result = q.setParameters(bean, Arrays.asList("p"));
        assertSame(q, result);
    }

    // --- setParameters(Object) when Map: routes to setParameters(Map) (L3968) ---

    @Test
    public void testSetParameters_Object_MapDelegates() throws SQLException {
        Map<String, Object> params = new HashMap<>();
        params.put("param1", "hello");
        NamedQuery result = namedQuery.setParameters((Object) params);
        assertSame(namedQuery, result);
        verify(mockPreparedStatement).setString(eq(1), eq("hello"));
    }

    // --- setParameters(Object) via Object overload: Collection branch (L3981) ---

    @Test
    public void testSetParameters_ObjectOverload_Collection() throws SQLException {
        Object params = Arrays.asList("value1", 123);
        NamedQuery result = namedQuery.setParameters(params);
        assertSame(namedQuery, result);
        verify(mockPreparedStatement).setString(eq(1), eq("value1"));
        verify(mockPreparedStatement).setInt(eq(2), eq(123));
    }

    // --- setParameters(Object) via Object overload: Object[] branch (L3988) ---

    @Test
    public void testSetParameters_ObjectOverload_Array() throws SQLException {
        Object params = new Object[] { "value1", 123 };
        NamedQuery result = namedQuery.setParameters(params);
        assertSame(namedQuery, result);
        verify(mockPreparedStatement).setString(eq(1), eq("value1"));
        verify(mockPreparedStatement).setInt(eq(2), eq(123));
    }

    @Test
    public void testSetParametersMapNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters((Map<String, ?>) null);
        });
    }

    @Test
    public void testSetParametersObjectNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters((Object) null);
        });
    }

    @Test
    public void testSetParametersEntityWithNamesNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters(null, Arrays.asList("param1"));
        });
    }

    @Test
    public void testSetParametersWithSetterNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters("test", (Collection<String>) null);
        });
    }

    @Test
    public void testSetParameters_Map_NullThrows() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setParameters((Map<String, ?>) null));
    }

    @Test
    public void testSetParameters_EntityWithNames_NullNamesOnBean() throws SQLException {
        // The two existing null-names tests pass a non-bean first argument, so they exit at the bean-class check
        // and never reach the parameterNamesToSet guard.
        final TestEntity entity = new TestEntity();
        entity.setParam1("x");

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setParameters(entity, (java.util.Collection<String>) null));

        assertTrue(failure.getMessage().contains("parameterNamesToSet"));
        verify(mockPreparedStatement).close();
    }

    // --- setParameters(T, TriParametersSetter): exception closes query (L4133) ---

    @Test
    public void testSetParameters_WithSetter_ExceptionClosesQuery() throws SQLException {
        assertThrows(RuntimeException.class, () -> namedQuery.setParameters("x", (sql, q, p) -> {
            throw new RuntimeException("setter error");
        }));
        verify(mockPreparedStatement).close();
    }

    // --- setParameters(EntityId): exception closes and rethrows (L3899-3900) ---

    @Test
    public void testSetParameters_Object_EntityId_ExceptionClosesAndRethrows() throws SQLException {
        doThrow(new RuntimeException("forced")).when(mockPreparedStatement).setObject(anyInt(), isNull());
        final com.landawn.abacus.util.EntityId eid = com.landawn.abacus.util.EntityId.of("param1", null);
        assertThrows(RuntimeException.class, () -> namedQuery.setParameters((Object) eid));
    }

    // --- setParameters(Map): exception in setObject closes and rethrows (L3877-3878) ---

    @Test
    public void testSetParameters_Map_ExceptionClosesAndRethrows() throws SQLException {
        doThrow(new RuntimeException("forced")).when(mockPreparedStatement).setObject(anyInt(), isNull());
        Map<String, Object> params = new HashMap<>();
        params.put("param1", null);
        assertThrows(RuntimeException.class, () -> namedQuery.setParameters(params));
    }

    @Test
    public void testSetParametersMapPreservesBindingFailureWhenCloseHandlerThrows() throws SQLException {
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

    // --- setParameters(Object) with bean missing property throws (L3955-3957, L3963-3965) ---

    @Test
    public void testSetParameters_Object_BeanMissingProp_Throws() throws SQLException {
        BeanNoProps bean = new BeanNoProps();
        bean.setFoo("x");
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setParameters(bean));
    }

    // --- setParameters(Object) with Collection, exception (L3971-3974) ---

    @Test
    public void testSetParameters_Object_Collection_ExceptionClosesAndRethrows() throws SQLException {
        doThrow(new RuntimeException("forced")).when(mockPreparedStatement).setObject(anyInt(), isNull());
        List<Object> params = Arrays.asList(null, null);
        assertThrows(RuntimeException.class, () -> namedQuery.setParameters(params));
    }

    // --- setParameters(Object) with Object[], exception (L3978-3981) ---

    @Test
    public void testSetParameters_Object_Array_ExceptionClosesAndRethrows() throws SQLException {
        doThrow(new RuntimeException("forced")).when(mockPreparedStatement).setObject(anyInt(), isNull());
        assertThrows(RuntimeException.class, () -> namedQuery.setParameters(new Object[] { null, null }));
    }

    // --- setParameters(Object) with single value, exception (L3989-3990) ---

    @Test
    public void testSetParameters_Object_SingleValue_ExceptionClosesAndRethrows() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        // Integer (not bean/Map/Collection/Object[]/EntityId), paramCount==1 → calls stmt.setInt
        doThrow(new RuntimeException("forced")).when(mockPreparedStatement).setInt(anyInt(), anyInt());
        assertThrows(RuntimeException.class, () -> q.setParameters(Integer.valueOf(42)));
    }

    // --- setParameters(Object) via Object overload: Collection exception (L3982-3984) ---

    @Test
    public void testSetParameters_ObjectOverload_CollectionExceptionClosesAndRethrows() throws SQLException {
        doThrow(new RuntimeException("forced")).when(mockPreparedStatement).setObject(anyInt(), isNull());
        Object params = Arrays.asList(null, null);
        assertThrows(RuntimeException.class, () -> namedQuery.setParameters(params));
        verify(mockPreparedStatement).close();
    }

    // --- setParameters(Object) via Object overload: Object[] exception (L3989-3991) ---

    @Test
    public void testSetParameters_ObjectOverload_ArrayExceptionClosesAndRethrows() throws SQLException {
        doThrow(new RuntimeException("forced")).when(mockPreparedStatement).setObject(anyInt(), isNull());
        Object params = new Object[] { null, null };
        assertThrows(RuntimeException.class, () -> namedQuery.setParameters(params));
        verify(mockPreparedStatement).close();
    }

    @Test
    public void testAddBatchParametersCollection() throws SQLException {
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
    public void testAddBatchParametersIterator() throws SQLException {
        List<Map<String, Object>> batchParams = new ArrayList<>();
        batchParams.add(Map.of("param1", "value1", "param2", 100));
        batchParams.add(Map.of("param1", "value2", "param2", 200));

        NamedQuery result = namedQuery.addBatchParameters(batchParams.iterator());

        verify(mockPreparedStatement, times(2)).setString(anyInt(), anyString());
        verify(mockPreparedStatement, times(2)).setInt(anyInt(), anyInt());
        verify(mockPreparedStatement, times(2)).addBatch();
        assertSame(namedQuery, result);
    }

    // Test addBatchParameters with Collection elements
    @Test
    public void testAddBatchParameters_CollectionElements() throws Exception {
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

    // Test addBatchParameters with Object[] elements
    @Test
    public void testAddBatchParameters_ArrayElements() throws Exception {
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

    // --- addBatchParameters(Iterator): EntityId elements (L4347-4355) ---

    @Test
    public void testAddBatchParameters_EntityId_Elements() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        final com.landawn.abacus.util.EntityId eid1 = com.landawn.abacus.util.EntityId.of("param1", "v1");
        final com.landawn.abacus.util.EntityId eid2 = com.landawn.abacus.util.EntityId.of("param1", "v2");
        List<com.landawn.abacus.util.EntityId> batch = Arrays.asList(eid1, eid2);
        q.addBatchParameters(batch.iterator());
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    // --- addBatchParameters(Iterator): single-value elements (L4357-4363) ---

    @Test
    public void testAddBatchParameters_SingleValue_Elements() throws SQLException {
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
    public void testAddBatchParameters_SingleValueCalendarElementsAreNotBoundAsBeans() throws SQLException {
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

    // --- addBatchParameters(Iterator): bean elements bind each named parameter via its PropInfo ---

    @Test
    public void testAddBatchParameters_BeanElements() throws SQLException {
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

    // --- addBatchParameters(Iterator): bean parameters for 2+ rows (L4274,4278,4286,4289,4292,4296,4298,4300,4303,4306,4310) ---

    @Test
    public void testAddBatchParameters_Iterator_BeanMultipleRows() throws SQLException {
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
    public void testAddBatchParametersCollectionNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.addBatchParameters((Collection<?>) null);
        });
    }

    @Test
    public void testAddBatchParametersIteratorNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.addBatchParameters((Iterator<?>) null);
        });
    }

    // Test addBatchParameters with null element
    @Test
    public void testAddBatchParameters_NullElement() throws Exception {
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

    // --- addBatchParameters(Collection): empty input returns early (L4189) ---

    @Test
    public void testAddBatchParameters_Collection_Empty() throws SQLException {
        NamedQuery result = namedQuery.addBatchParameters(new ArrayList<>());
        assertSame(namedQuery, result);
        verify(mockPreparedStatement, org.mockito.Mockito.never()).addBatch();
    }

    // --- addBatchParameters(Iterator): null first + paramCount != 1 throws (L4260) ---

    @Test
    public void testAddBatchParameters_NullFirst_MultiParam_Throws() throws SQLException {
        // paramCount=2 but first element is null → IAE (L4260)
        List<Object> batch = new ArrayList<>();
        batch.add(null);
        assertThrows(IllegalArgumentException.class, () -> namedQuery.addBatchParameters(batch.iterator()));
    }

    // --- addBatchParameters(Iterator): null first element followed by a non-null one — the
    // continuation clears stale bindings and binds the non-null row through the type system ---

    @Test
    public void testAddBatchParameters_NullFirstThenNonNull() throws SQLException {
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
    public void testAddBatchParameters_NullFirstThenStructuredRows() throws SQLException {
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

    // --- addBatchParameters(Iterator): empty iterator returns this (L4252-4253) ---

    @Test
    public void testAddBatchParameters_Iterator_Empty() throws SQLException {
        NamedQuery result = namedQuery.addBatchParameters(new ArrayList<>().iterator());
        assertSame(namedQuery, result);
        verify(mockPreparedStatement, never()).addBatch();
    }

    // --- addBatchParameters(Iterator): null second element when paramCount==1 (L4267-4268) ---

    @Test
    public void testAddBatchParameters_Iterator_NullFirst_NullSubsequent() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        // first is null (paramCount==1 → ok), second is also null → hits L4267-4268
        q.addBatchParameters(Arrays.asList(null, null).iterator());
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    @Test
    public void testAddBatchParameters_Iterator_MapThenNullSingleParameter() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);

        q.addBatchParameters(Arrays.asList(Map.of("id", 1), null).iterator());

        verify(mockPreparedStatement).setInt(1, 1);
        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    // --- addBatchParameters(Iterator) null-element handling branches (paramCount==1) ---

    // null first then a non-null follower exercises the continuation's setParameters(Object)
    // dispatch after clearParameters().
    @Test
    public void testAddBatchParameters_Iterator_NullFirst_NonNullSubsequent() throws SQLException {
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

    // bean first then null follower hits the bean-branch null-continue L4324-4325.
    @Test
    public void testAddBatchParameters_Iterator_BeanThenNull() throws SQLException {
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

    // Collection first then null follower hits the collection-branch null-continue L4365-4366.
    @Test
    public void testAddBatchParameters_Iterator_CollectionThenNull() throws SQLException {
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

    // Object[] first then null follower hits the array-branch null-continue L4382-4383.
    @Test
    public void testAddBatchParameters_Iterator_ArrayThenNull() throws SQLException {
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

    // EntityId first then null follower hits the EntityId-branch null-continue L4399-4400.
    @Test
    public void testAddBatchParameters_Iterator_EntityIdThenNull() throws SQLException {
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

    // --- addBatchParameters(Iterator): unsupported type throws (L4366) ---

    @Test
    public void testAddBatchParameters_UnsupportedType_Throws() throws SQLException {
        // Multi-param query, first element is not a bean/map/collection/array/EntityId → throws (L4366)
        List<Object> batch = new ArrayList<>();
        batch.add(42); // Integer, not a bean; paramCount=2
        assertThrows(IllegalArgumentException.class, () -> namedQuery.addBatchParameters(batch.iterator()));
    }

    // --- addBatchParameters(Iterator): bean missing property throws (L4281-4282) ---

    @Test
    public void testAddBatchParameters_Iterator_BeanMissingProp_Throws() throws SQLException {
        BeanNoProps bean = new BeanNoProps();
        bean.setFoo("x");
        assertThrows(IllegalArgumentException.class, () -> namedQuery.addBatchParameters(Arrays.asList(bean).iterator()));
    }

    @Test
    public void testParameterNotFound() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setString("nonExistentParam", "value");
        });

        assertTrue(ex.getMessage().contains("nonExistentParam"));
        assertTrue(ex.getMessage().contains("[param1, param2]"));
        assertTrue(ex.getMessage().contains("SELECT * FROM table WHERE col1 = :param1 AND col2 = :param2"));
    }

    @Test
    public void testUnknownParameterPreservesIllegalArgumentWhenCloseHandlerThrows() {
        final RuntimeException closeFailure = new RuntimeException("close handler failed");
        namedQuery.onClose(() -> {
            throw closeFailure;
        });

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> namedQuery.setNull("nonExistent", Types.INTEGER));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    // Helper test entity class
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
}
